package ysyx_26030103.infra

import chisel3._
import chisel3.util._

/** AXI5 地址/数据宽度边界桥。
  *
  * 上游 `in` 是请求方一侧（slave 端口），下游 `out` 是响应方一侧
  * （master 端口）。NPC 内部 SoC 总线固定 32 位数据；ysyxSoC 的 CPU
  * 边界在 `SOC_XLEN=64` 时使用 64 位地址/数据，因此这里必须显式转换，
  * 不能把高位地址或宽数据静默截断。
  *
  * 支持的能力：
  *   - 数据宽度 32<->64（比例最多 2），包括 4 字节单拍、窄传输，
  *     以及 RV64 LD/SD 在 32 位总线上拆出的 8 字节两拍访问；
  *   - 地址宽度 32<->64；下游更窄时对整笔 burst 做越界检查，
  *     越界请求在本地返回 DECERR，绝不把高 32 位丢掉后访问低地址；
  *   - AW/W/B/AR/R 五通道、INCR/FIXED/WRAP burst、WSTRB、RLAST
  *     和错误响应；
  *   - 每一侧同一时刻只处理一笔事务，用反压保证通道稳定。这与
  *     ysyxSoC 的 AXI4Downsizer 串行化策略一致，不要求下游支持
  *     多笔同 ID 在途事务。
  *
  * 64->32 时把每个上游 beat 拆成若干单拍 4 字节事务，逐笔收完所有
  * 下游 B 后再聚合成一个上游 B；读方向则逐块收齐并拼回上游宽 beat。
  * 32->64 时一个上游 beat 直接对应一个下游 beat，只把数据搬到正确
  * 的字节半区，保持 AWSIZE/ARLEN 不变，由下游宽总线继续处理。
  *
  * 响应完成语义：只要某笔事务真的向下游发过 AW/AR，上游 B/R 就必须
  * 来自下游实际返回的响应（64->32 聚合或 32->64 透传）。只有完全没有
  * 下发下游事务的本地拒绝（地址越界 DECERR）才允许本地生成响应。
  * `AddressNarrows` 只改变地址位宽，不是本地响应的理由。
  */
class ysyx_26030103_AXI5WidthBridge(
    UpstreamAddressWidth: Int = 64,
    DownstreamAddressWidth: Int = 32,
    UpstreamDataWidth: Int = 32,
    DownstreamDataWidth: Int = 64,
    IdWidth: Int = 4
) extends Module {
  require(
    UpstreamAddressWidth == 32 || UpstreamAddressWidth == 64,
    s"上游地址宽度只支持 32/64，收到 $UpstreamAddressWidth"
  )
  require(
    DownstreamAddressWidth == 32 || DownstreamAddressWidth == 64,
    s"下游地址宽度只支持 32/64，收到 $DownstreamAddressWidth"
  )
  require(
    UpstreamDataWidth == 32 || UpstreamDataWidth == 64,
    s"上游数据宽度只支持 32/64，收到 $UpstreamDataWidth"
  )
  require(
    DownstreamDataWidth == 32 || DownstreamDataWidth == 64,
    s"下游数据宽度只支持 32/64，收到 $DownstreamDataWidth"
  )
  private val UpBytes = UpstreamDataWidth / 8
  private val DownBytes = DownstreamDataWidth / 8
  private val UpLaneBits = log2Ceil(UpBytes)
  private val DownLaneBits = log2Ceil(DownBytes)
  private val Downsize = UpstreamDataWidth > DownstreamDataWidth
  private val Upsize = UpstreamDataWidth < DownstreamDataWidth
  private val AddressNarrows = DownstreamAddressWidth < UpstreamAddressWidth
  private val OKAY = 0.U(2.W)
  private val DECERR = 3.U(2.W)

  val io = IO(new Bundle {
    val in =
      Flipped(new ysyx_26030103_AXI5IO(
        UpstreamAddressWidth,
        UpstreamDataWidth,
        IdWidth
      ))
    val out =
      new ysyx_26030103_AXI5IO(
        DownstreamAddressWidth,
        DownstreamDataWidth,
        IdWidth
      )
  })

  /** 把上游地址映射到下游地址宽度。仅在 `!OutOfRange` 时使用；
    * 越界事务不会走到下游，因此这里的低位切片不构成静默截断。
    */
  private def DownAddress(Address: UInt): UInt = {
    if (DownstreamAddressWidth >= UpstreamAddressWidth) {
      Address.pad(DownstreamAddressWidth)
    } else {
      Address(DownstreamAddressWidth - 1, 0)
    }
  }

  /** 检查整笔 burst 访问的最后一个字节是否越过下游地址空间。
    *
    * 只看起始地址的高位不够：`0xfffffffc + 4B` 这类跨 4GiB 边界的
    * burst 起始高位全 0，但最后一拍会回绕。这里按 AXI 地址规则分别
    * 计算 INCR/WRAP/FIXED 的末字节：
    *   - INCR：首拍可以非对齐，后续拍从 size 对齐边界开始；
    *   - WRAP：整笔落在 wrap 容器内；
    *   - FIXED：每拍地址不变，只占一个 size 窗口。
    *
    * `Len + 1` 显式扩到 9 位，避免 AWLEN=255（256 拍）在 8 位加法里
    * 回绕成 0 后把末字节算错。越界事务不会走到下游，因此
    * `DownAddress` 的高位切片不构成静默地址丢失。
    */
  private def OutOfRange(
      Address: UInt,
      Len: UInt,
      Size: UInt,
      Burst: UInt
  ): Bool = {
    if (!AddressNarrows) {
      false.B
    } else {
      val Width = UpstreamAddressWidth + 1
      val Start = Cat(0.U(1.W), Address)
      val SizeBytes = (1.U << Size).pad(Width)
      val TotalBytes = ((Len.pad(9) + 1.U) << Size).pad(Width)
      val SizeMask = SizeBytes - 1.U
      val WrapMask = TotalBytes - 1.U
      val AlignedStart = (Start & (~SizeMask).asUInt)(Width - 1, 0)
      val WrapBase = (Start & (~WrapMask).asUInt)(Width - 1, 0)
      val LastByte = Mux(
        Burst === 0.U,
        (AlignedStart + SizeMask)(Width - 1, 0),
        Mux(
          Burst === 2.U,
          (WrapBase + WrapMask)(Width - 1, 0),
          (AlignedStart + TotalBytes - 1.U)(Width - 1, 0)
        )
      )
      Start(UpstreamAddressWidth, DownstreamAddressWidth).orR ||
      LastByte(UpstreamAddressWidth, DownstreamAddressWidth).orR
    }
  }

  /** 计算 burst 中第 Beat 拍的起始地址。
    *
    * AXI 对非对齐 INCR 首拍的规定是：首拍从原始地址开始，只传输到该
    * size 自然窗口的末尾；后续拍从 `align_down(Base, size)` 开始按
    * size 递增。每拍都保留首拍偏移会把非对齐 burst 的后续拍搬到错误
    * 地址。
    */
  private def BeatAddress(
      Base: UInt,
      Beat: UInt,
      Size: UInt,
      Burst: UInt,
      Len: UInt
  ): UInt = {
    val Width = math.max(UpstreamAddressWidth, DownstreamAddressWidth) + 1
    val BaseExt = Base.pad(Width)
    val SizeMask = ((1.U << Size) - 1.U).pad(Width)
    val AlignedBase = (BaseExt & (~SizeMask).asUInt)(Width - 1, 0)
    val Step = (Beat.pad(Width) << Size)(Width - 1, 0)
    val Inc = (AlignedBase + Step)(Width - 1, 0)
    val IncAddress = Mux(Beat === 0.U, BaseExt, Inc)
    val WrapMask = ((((Len.pad(9) + 1.U) << Size) - 1.U)).pad(Width)
    val Wrapped =
      ((BaseExt & (~WrapMask).asUInt) | (Inc & WrapMask))(Width - 1, 0)
    Mux(
      Burst === 2.U,
      Wrapped,
      Mux(Burst === 1.U, IncAddress, BaseExt)
    )
  }

  /** 上游数据总线上一个 beat 的字节起始地址（按总线宽度对齐）。 */
  private def BeatByteBase(Address: UInt): UInt =
    ((Address >> UpLaneBits) << UpLaneBits)

  /** 64->32 拆分时，第 Chunk 个 4 字节块的下游地址。 */
  private def ChunkAddress(BeatAddr: UInt, Chunk: UInt): UInt = {
    val Base = (BeatAddr >> DownLaneBits) << DownLaneBits
    Base + (Chunk << DownLaneBits)
  }

  /** 一个上游 beat 需要拆成多少个下游 beat（下游更窄时）。
    *
    * 正常对齐访问只需要 1 个；宽 beat（8 字节）或跨 4 字节边界的
    * 非对齐 beat 需要 2 个。INCR 首拍和 FIXED 的每一拍只传输到该
    * size 自然窗口的末尾，所以有效字节数要从 `地址 % size` 算起，
    * 不能无条件使用 `1 << Size`，否则会多发全零 WSTRB 的空写。
    */
  private def BeatChunkCount(
      BeatAddr: UInt,
      Size: UInt,
      Partial: Bool
  ): UInt = {
    val Lane = BeatAddr(UpLaneBits - 1, 0)
    val SizeBytes = (1.U << Size).pad(UpLaneBits + 1)
    val Offset = Lane.pad(UpLaneBits + 1) & (SizeBytes - 1.U)
    val Span = Mux(Partial, SizeBytes - Offset, SizeBytes)
    val FirstChunk = Lane >> DownLaneBits
    val LastChunk =
      ((Lane.pad(UpLaneBits + 1) + Span - 1.U) >> DownLaneBits)
    (LastChunk - FirstChunk + 1.U)(1, 0)
  }

  /** 从宽上游数据中取出下游宽度的字节块。
    *
    * Chisel 的动态右移不会自动扩大结果位宽，因此先显式 pad 到上游
    * 总线宽度，再 pad 到下游总线宽度，保证两种配置下切片都合法。
    */
  private def ExtractData(Data: UInt, ByteOffset: UInt): UInt = {
    val Padded = Data.pad(math.max(UpstreamDataWidth, DownstreamDataWidth))
    val Shifted = (Padded >> (ByteOffset << 3)).pad(DownstreamDataWidth)
    Shifted(DownstreamDataWidth - 1, 0)
  }

  /** 从上游 WSTRB 中取出下游字节块对应的掩码。 */
  private def ExtractStrb(Strb: UInt, ByteOffset: UInt): UInt = {
    val Padded = Strb.pad(math.max(UpBytes, DownBytes))
    val Shifted = (Padded >> ByteOffset).pad(DownBytes)
    Shifted(DownBytes - 1, 0)
  }

  /** 32->64 时上游 32 位字落在下游 64 位总线的哪个字节半区。
    *
    * 总线上的字节 lane 由 `地址 % 总线字节数` 决定，因此整个上游字要
    * 搬到下游字内与地址 bit[2] 对应的半区。若直接按 `地址 % 8` 移位，
    * 地址 `0x...2` 的窄访问会从 lane 2 漂到 lane 4，写到错误地址。
    */
  private def UpsizeLane(BeatAddr: UInt): UInt =
    if (Upsize) {
      Cat(BeatAddr(DownLaneBits - 1, UpLaneBits), 0.U(UpLaneBits.W))
    } else { 0.U }

  private def UpDataToDown(Data: UInt, Lane: UInt): UInt = {
    val Shift = Lane << 3
    val Padded = Data.pad(math.max(UpstreamDataWidth, DownstreamDataWidth))
    (Padded << Shift)(DownstreamDataWidth - 1, 0)
  }

  private def UpStrbToDown(Strb: UInt, Lane: UInt): UInt = {
    val Padded = Strb.pad(math.max(UpBytes, DownBytes))
    val Shifted = Padded << Lane
    Shifted(DownBytes - 1, 0)
  }

  private def DownDataToUp(Data: UInt, Lane: UInt): UInt = {
    val Shift = Lane << 3
    // 注意 pad 不能只扩到上游宽度：32->64 时下游 RDATA 比上游宽，
    // 先把高半区截掉再右移会读到错误数据。这里扩到两侧最大宽度。
    val Padded = Data.pad(math.max(UpstreamDataWidth, DownstreamDataWidth))
    (Padded >> Shift)(UpstreamDataWidth - 1, 0)
  }

  // 默认把所有 VALID 拉低，下面按状态覆盖，避免产生锁存器。
  io.in.AW.AWREADY := false.B
  io.in.W.WREADY := false.B
  io.in.B.BVALID := false.B
  io.in.B.BID := 0.U
  io.in.B.BRESP := OKAY
  io.in.AR.ARREADY := false.B
  io.in.R.RVALID := false.B
  io.in.R.RID := 0.U
  io.in.R.RDATA := 0.U
  io.in.R.RRESP := OKAY
  io.in.R.RLAST := false.B

  io.out.AW.AWVALID := false.B
  io.out.AW.AWID := 0.U
  io.out.AW.AWADDR := 0.U
  io.out.AW.AWLEN := 0.U
  io.out.AW.AWSIZE := 2.U
  io.out.AW.AWBURST := 0.U
  io.out.AW.AWPROT := 0.U
  io.out.AW.AWCACHE := 0.U
  io.out.AW.AWQOS := 0.U
  io.out.W.WVALID := false.B
  io.out.W.WDATA := 0.U
  io.out.W.WSTRB := 0.U
  io.out.W.WLAST := false.B
  io.out.B.BREADY := false.B
  io.out.AR.ARVALID := false.B
  io.out.AR.ARID := 0.U
  io.out.AR.ARADDR := 0.U
  io.out.AR.ARLEN := 0.U
  io.out.AR.ARSIZE := 2.U
  io.out.AR.ARBURST := 0.U
  io.out.AR.ARPROT := 0.U
  io.out.AR.ARCACHE := 0.U
  io.out.AR.ARQOS := 0.U
  io.out.R.RREADY := false.B

  // ==================================================================
  // 写通道
  // ==================================================================
  val wIdle :: wUpsize :: wErrDrain :: wResp :: wDnBeat :: wDnSend :: wDnWaitB :: Nil =
    Enum(7)
  val wState = RegInit(wIdle)
  val wId = RegInit(0.U(IdWidth.W))
  val wAddr = RegInit(0.U(UpstreamAddressWidth.W))
  val wLen = RegInit(0.U(8.W))
  val wSize = RegInit(2.U(3.W))
  val wBurst = RegInit(0.U(2.W))
  val wProt = RegInit(0.U(3.W))
  val wCache = RegInit(0.U(4.W))
  val wQos = RegInit(0.U(4.W))
  val wError = RegInit(false.B)
  val wBeat = RegInit(0.U(8.W))
  val wChunk = RegInit(0.U(2.W))
  val wRespCode = RegInit(OKAY)
  val wAwSent = RegInit(false.B)
  val wWDone = RegInit(false.B)
  val wBeatValid = RegInit(false.B)
  val wBeatData = RegInit(0.U(UpstreamDataWidth.W))
  val wBeatStrb = RegInit(0.U(UpBytes.W))
  val wBeatLast = RegInit(false.B)
  val wChunkAwSent = RegInit(false.B)
  val wChunkWSent = RegInit(false.B)

  // 响应完成语义的观测寄存器：下游 AW 已发出、B 未返回的数量。
  // 桥串行处理事务，正常情况下该计数最多为 1。
  val wDnAwFire = io.out.AW.AWVALID && io.out.AW.AWREADY
  val wDnBFire = io.out.B.BVALID && io.out.B.BREADY
  val wDnOutstanding = RegInit(0.U(2.W))
  val wUpWLastSeen = RegInit(false.B)
  val wUpBSeen = RegInit(false.B)
  val wTxnIssuedAw = RegInit(false.B)
  val wDnWLastSeen = RegInit(false.B)

  val incomingWError = OutOfRange(
    io.in.AW.AWADDR,
    io.in.AW.AWLEN,
    io.in.AW.AWSIZE,
    io.in.AW.AWBURST
  )

  switch(wState) {
    is(wIdle) {
      io.in.AW.AWREADY := true.B
      when(io.in.AW.AWVALID) {
        assert(
          io.in.AW.AWSIZE <= UpLaneBits.U,
          "AXI5WidthBridge: AWSIZE exceeds upstream data bus width"
        )
        assert(
          io.in.AW.AWBURST =/= 3.U,
          "AXI5WidthBridge: reserved AWBURST encoding"
        )
        wId := io.in.AW.AWID
        wAddr := io.in.AW.AWADDR
        wLen := io.in.AW.AWLEN
        wSize := io.in.AW.AWSIZE
        wBurst := io.in.AW.AWBURST
        wProt := io.in.AW.AWPROT
        wCache := io.in.AW.AWCACHE
        wQos := io.in.AW.AWQOS
        wError := incomingWError
        wBeat := 0.U
        wChunk := 0.U
        wRespCode := OKAY
        wAwSent := false.B
        wWDone := false.B
        wBeatValid := false.B
        wChunkAwSent := false.B
        wChunkWSent := false.B
        wState := Mux(
          incomingWError,
          wErrDrain,
          if (Downsize) { wDnBeat }
          else { wUpsize }
        )
      }
    }

    is(wErrDrain) {
      // 地址越界时不下发任何 AW，但必须吃完整笔 W burst 再回 DECERR，
      // 否则上游会停在 W 通道上等一个永远不会到来的 B。
      io.in.W.WREADY := true.B
      when(io.in.W.WVALID && io.in.W.WLAST) {
        wState := wResp
      }
    }

    is(wUpsize) {
      // 32->64：一个上游 beat 对应一个下游 beat，AW/AR 的 burst 形状
      // 保持不变。数据按当前 beat 地址搬到 64 位总线的对应半区。
      io.out.AW.AWVALID := !wAwSent
      io.out.AW.AWID := wId
      io.out.AW.AWADDR := DownAddress(wAddr)
      io.out.AW.AWLEN := wLen
      io.out.AW.AWSIZE := wSize
      io.out.AW.AWBURST := wBurst
      io.out.AW.AWPROT := wProt
      io.out.AW.AWCACHE := wCache
      io.out.AW.AWQOS := wQos
      when(io.out.AW.AWVALID && io.out.AW.AWREADY) {
        wAwSent := true.B
      }

      val BeatAddr = BeatAddress(wAddr, wBeat, wSize, wBurst, wLen)
      val Lane = UpsizeLane(BeatAddr)
      val SizeBytes = 1.U << wSize
      val Partial = wBeat === 0.U || wBurst === 0.U
      val BeatBytes =
        Mux(
          Partial,
          SizeBytes - (BeatAddr & (SizeBytes - 1.U)),
          SizeBytes
        )
      when(io.in.W.WVALID || wBeatValid) {
        assert(
          Lane + BeatBytes <= DownBytes.U,
          "AXI5WidthBridge: upstream beat does not fit a downstream wide beat"
        )
      }
      when(!wBeatValid && !wWDone) {
        io.in.W.WREADY := true.B
        when(io.in.W.WVALID) {
          wBeatData := io.in.W.WDATA
          wBeatStrb := io.in.W.WSTRB
          wBeatLast := io.in.W.WLAST
          wBeatValid := true.B
        }
      }.otherwise {
        io.out.W.WVALID := true.B
        io.out.W.WDATA := UpDataToDown(wBeatData, Lane)
        io.out.W.WSTRB := UpStrbToDown(wBeatStrb, Lane)
        io.out.W.WLAST := wBeatLast
        when(io.out.W.WREADY) {
          wBeatValid := false.B
          when(wBeatLast) {
            wWDone := true.B
          }.otherwise {
            wBeat := wBeat + 1.U
          }
        }
      }

      when(wWDone && wAwSent) {
        wState := wResp
      }
    }

    is(wDnBeat) {
      // 64->32：先把一个上游宽 beat 收到本地缓冲，再逐 4 字节块发出。
      // 这里不能直接透传，因为一个上游 beat 可能对应两笔下游 AW/B。
      io.in.W.WREADY := !wBeatValid
      when(!wBeatValid && io.in.W.WVALID) {
        wBeatData := io.in.W.WDATA
        wBeatStrb := io.in.W.WSTRB
        wBeatLast := io.in.W.WLAST
        wBeatValid := true.B
        wChunk := 0.U
        wChunkAwSent := false.B
        wChunkWSent := false.B
        wState := wDnSend
      }
    }

    is(wDnSend) {
      val BeatAddr = BeatAddress(wAddr, wBeat, wSize, wBurst, wLen)
      val ByteBase = BeatByteBase(BeatAddr)
      val ChunkAddr = ChunkAddress(BeatAddr, wChunk)
      val ByteOffset = (ChunkAddr - ByteBase)(UpLaneBits - 1, 0)

      io.out.AW.AWVALID := !wChunkAwSent
      io.out.AW.AWID := wId
      io.out.AW.AWADDR := DownAddress(ChunkAddr)
      io.out.AW.AWLEN := 0.U
      io.out.AW.AWSIZE := DownLaneBits.U(3.W)
      io.out.AW.AWBURST := 1.U
      io.out.AW.AWPROT := wProt
      io.out.AW.AWCACHE := wCache
      io.out.AW.AWQOS := wQos

      io.out.W.WVALID := !wChunkWSent
      io.out.W.WDATA := ExtractData(wBeatData, ByteOffset)
      io.out.W.WSTRB := ExtractStrb(wBeatStrb, ByteOffset)
      // 每个 chunk 都是独立的 AWLEN=0 单拍事务，WLAST 必须逐笔拉高；
      // 上游 burst 的最后一拍只用于决定何时聚合出上游 B。
      io.out.W.WLAST := true.B

      val AwFire = io.out.AW.AWVALID && io.out.AW.AWREADY
      val WFire = io.out.W.WVALID && io.out.W.WREADY
      when(AwFire) { wChunkAwSent := true.B }
      when(WFire) { wChunkWSent := true.B }
      when((wChunkAwSent || AwFire) && (wChunkWSent || WFire)) {
        wChunkAwSent := false.B
        wChunkWSent := false.B
        wState := wDnWaitB
      }
    }

    is(wDnWaitB) {
      val BeatAddr = BeatAddress(wAddr, wBeat, wSize, wBurst, wLen)
      val Partial = wBeat === 0.U || wBurst === 0.U
      val LastChunk = BeatChunkCount(BeatAddr, wSize, Partial) - 1.U
      io.out.B.BREADY := true.B
      when(io.out.B.BVALID) {
        assert(
          io.out.B.BID === wId,
          "AXI5WidthBridge: downstream write response ID mismatch"
        )
        when(io.out.B.BRESP =/= OKAY) {
          wRespCode := io.out.B.BRESP
        }
        when(wChunk === LastChunk) {
          when(wBeatLast) {
            wState := wResp
          }.otherwise {
            wBeat := wBeat + 1.U
            wBeatValid := false.B
            wChunk := 0.U
            wState := wDnBeat
          }
        }.otherwise {
          wChunk := wChunk + 1.U
          wState := wDnSend
        }
      }
    }

    is(wResp) {
      if (Downsize) {
        // 64->32：wDnWaitB 已经逐块收齐所有下游 B，本地聚合成一个
        // 上游 B。越界事务没有下发 AW/W，也在这里返回 DECERR。
        io.in.B.BVALID := true.B
        io.in.B.BID := wId
        io.in.B.BRESP := Mux(wError, DECERR, wRespCode)
        when(io.in.B.BREADY) {
          wState := wIdle
        }
      } else if (AddressNarrows) {
        // 地址变窄但数据同宽（RV64 CPU + 32 位 SoC）：AW/W 走 wUpsize，
        // 下游 B 仍在途，必须等它返回后再交给上游，不能提前伪造 B。
        // 越界事务没有下发 AW/W，必须本地生成 DECERR。
        when(wError) {
          io.in.B.BVALID := true.B
          io.in.B.BID := wId
          io.in.B.BRESP := DECERR
          when(io.in.B.BREADY) {
            wState := wIdle
          }
        }.otherwise {
          io.in.B.BVALID := io.out.B.BVALID
          io.in.B.BID := io.out.B.BID
          io.in.B.BRESP := io.out.B.BRESP
          io.out.B.BREADY := io.in.B.BREADY
          when(io.out.B.BVALID) {
            assert(
              io.out.B.BID === wId,
              "AXI5WidthBridge: downstream write response ID mismatch"
            )
          }
          when(io.out.B.BVALID && io.in.B.BREADY) {
            wState := wIdle
          }
        }
      } else {
        when(wError) {
          // 地址越界但数据同宽/变宽：没有下发任何下游 AW/W，本地
          // 返回 DECERR，不能等待一个不存在的下游 B。
          io.in.B.BVALID := true.B
          io.in.B.BID := wId
          io.in.B.BRESP := DECERR
          when(io.in.B.BREADY) {
            wState := wIdle
          }
        }.otherwise {
          // 数据同宽（包括 AddressNarrows）或 32->64：下游 AW/W 已经
          // 下发，必须等下游 B 返回后再透传给上游。地址窄化本身不
          // 产生本地响应。
          io.in.B.BVALID := io.out.B.BVALID
          io.in.B.BID := io.out.B.BID
          io.in.B.BRESP := io.out.B.BRESP
          io.out.B.BREADY := io.in.B.BREADY
          when(io.out.B.BVALID) {
            assert(
              io.out.B.BID === wId,
              "AXI5WidthBridge: downstream write response ID mismatch"
            )
          }
          when(io.out.B.BVALID && io.in.B.BREADY) {
            wState := wIdle
          }
        }
      }
    }
  }

  // ------------------------------------------------------------------
  // 写通道协议断言
  // ------------------------------------------------------------------
  when(wState === wIdle && io.in.AW.AWVALID && io.in.AW.AWREADY) {
    wUpWLastSeen := false.B
    wUpBSeen := false.B
    wTxnIssuedAw := false.B
    wDnWLastSeen := false.B
  }
  when(wDnAwFire) { wTxnIssuedAw := true.B }
  when(io.in.W.WVALID && io.in.W.WREADY && io.in.W.WLAST) {
    wUpWLastSeen := true.B
  }
  when(io.out.W.WVALID && io.out.W.WREADY && io.out.W.WLAST) {
    wDnWLastSeen := true.B
  }
  when(io.in.B.BVALID && io.in.B.BREADY) {
    wUpBSeen := true.B
  }
  when(wDnAwFire =/= wDnBFire) {
    wDnOutstanding := Mux(
      wDnAwFire,
      wDnOutstanding + 1.U,
      wDnOutstanding - 1.U
    )
  }

  when(wDnOutstanding > 1.U) {
    assert(
      false.B,
      "AXI5WidthBridge: more than one downstream write outstanding"
    )
  }
  when(wState === wIdle) {
    assert(
      wDnOutstanding === 0.U,
      "AXI5WidthBridge: idle with downstream write outstanding"
    )
  }
  when(io.out.B.BVALID) {
    assert(
      io.out.B.BID === wId,
      "AXI5WidthBridge: downstream write response ID mismatch"
    )
    assert(
      wDnWLastSeen || (io.out.W.WVALID && io.out.W.WREADY && io.out.W.WLAST),
      "AXI5WidthBridge: downstream B before downstream WLAST"
    )
  }
  when(io.in.B.BVALID) {
    assert(
      wUpWLastSeen,
      "AXI5WidthBridge: upstream B before last upstream W beat"
    )
    assert(!wUpBSeen, "AXI5WidthBridge: duplicate upstream B")
    assert(
      io.in.B.BID === wId,
      "AXI5WidthBridge: upstream write response ID mismatch"
    )
    assert(
      wDnOutstanding === 0.U || io.out.B.BVALID,
      "AXI5WidthBridge: upstream B while downstream write is outstanding"
    )
    when(wError) {
      assert(
        !wTxnIssuedAw,
        "AXI5WidthBridge: local DECERR B after issuing downstream AW"
      )
    }
  }
  when(io.in.W.WVALID && io.in.W.WREADY && !wError) {
    assert(
      wBeat <= wLen,
      "AXI5WidthBridge: upstream W beat exceeds AWLEN"
    )
    assert(
      io.in.W.WLAST === (wBeat === wLen),
      "AXI5WidthBridge: upstream WLAST does not match AWLEN"
    )
  }

  // ==================================================================
  // 读通道
  // ==================================================================
  val rIdle :: rUpsize :: rErrData :: rDnSend :: rDnWait :: rDnOut :: Nil =
    Enum(6)
  val rState = RegInit(rIdle)
  val rId = RegInit(0.U(IdWidth.W))
  val rAddr = RegInit(0.U(UpstreamAddressWidth.W))
  val rLen = RegInit(0.U(8.W))
  val rSize = RegInit(2.U(3.W))
  val rBurst = RegInit(0.U(2.W))
  val rProt = RegInit(0.U(3.W))
  val rCache = RegInit(0.U(4.W))
  val rQos = RegInit(0.U(4.W))
  val rError = RegInit(false.B)
  val rBeat = RegInit(0.U(8.W))
  val rChunk = RegInit(0.U(2.W))
  val rRespCode = RegInit(OKAY)
  val rData = RegInit(0.U(UpstreamDataWidth.W))
  val rArSent = RegInit(false.B)

  // 读方向的完成语义观测：下游 AR 已发出、RLAST 未返回的数量。
  val rDnArFire = io.out.AR.ARVALID && io.out.AR.ARREADY
  val rDnRLastFire = io.out.R.RVALID && io.out.R.RREADY && io.out.R.RLAST
  val rDnOutstanding = RegInit(0.U(2.W))
  val rUpRLastSeen = RegInit(false.B)
  val rTxnIssuedAr = RegInit(false.B)

  val incomingRError = OutOfRange(
    io.in.AR.ARADDR,
    io.in.AR.ARLEN,
    io.in.AR.ARSIZE,
    io.in.AR.ARBURST
  )

  switch(rState) {
    is(rIdle) {
      io.in.AR.ARREADY := true.B
      when(io.in.AR.ARVALID) {
        assert(
          io.in.AR.ARSIZE <= UpLaneBits.U,
          "AXI5WidthBridge: ARSIZE exceeds upstream data bus width"
        )
        assert(
          io.in.AR.ARBURST =/= 3.U,
          "AXI5WidthBridge: reserved ARBURST encoding"
        )
        rId := io.in.AR.ARID
        rAddr := io.in.AR.ARADDR
        rLen := io.in.AR.ARLEN
        rSize := io.in.AR.ARSIZE
        rBurst := io.in.AR.ARBURST
        rProt := io.in.AR.ARPROT
        rCache := io.in.AR.ARCACHE
        rQos := io.in.AR.ARQOS
        rError := incomingRError
        rBeat := 0.U
        rChunk := 0.U
        rRespCode := OKAY
        rData := 0.U
        rArSent := false.B
        rState := Mux(
          incomingRError,
          rErrData,
          if (Downsize) { rDnSend }
          else { rUpsize }
        )
      }
    }

    is(rUpsize) {
      // 32->64：AR 形状不变，R 数据按 beat 地址从宽总线对应半区取回。
      io.out.AR.ARVALID := !rArSent
      io.out.AR.ARID := rId
      io.out.AR.ARADDR := DownAddress(rAddr)
      io.out.AR.ARLEN := rLen
      io.out.AR.ARSIZE := rSize
      io.out.AR.ARBURST := rBurst
      io.out.AR.ARPROT := rProt
      io.out.AR.ARCACHE := rCache
      io.out.AR.ARQOS := rQos
      when(io.out.AR.ARVALID && io.out.AR.ARREADY) {
        rArSent := true.B
      }

      val BeatAddr = BeatAddress(rAddr, rBeat, rSize, rBurst, rLen)
      val Lane = UpsizeLane(BeatAddr)
      io.out.R.RREADY := io.in.R.RREADY
      io.in.R.RVALID := io.out.R.RVALID
      io.in.R.RID := io.out.R.RID
      io.in.R.RDATA := DownDataToUp(io.out.R.RDATA, Lane)
      io.in.R.RRESP := io.out.R.RRESP
      io.in.R.RLAST := io.out.R.RLAST
      when(io.out.R.RVALID) {
        assert(
          io.out.R.RID === rId,
          "AXI5WidthBridge: downstream read response ID mismatch"
        )
        assert(
          io.out.R.RLAST === (rBeat === rLen),
          "AXI5WidthBridge: downstream RLAST does not match ARLEN"
        )
        // 32 位上游数据放进 64 位总线的一半；非对齐首拍只占该 size
        // 自然窗口的剩余部分，后续拍必须是完整且对齐的 size 窗口。
        val SizeBytes = 1.U << rSize
        val Partial = rBeat === 0.U || rBurst === 0.U
        val BeatBytes =
          Mux(
            Partial,
            SizeBytes - (BeatAddr & (SizeBytes - 1.U)),
            SizeBytes
          )
        assert(
          Lane + BeatBytes <= DownBytes.U,
          "AXI5WidthBridge: upstream read beat does not fit a downstream wide beat"
        )
      }
      when(io.out.R.RVALID && io.in.R.RREADY) {
        when(rBeat === rLen) {
          rState := rIdle
        }.otherwise {
          rBeat := rBeat + 1.U
        }
      }
    }

    is(rErrData) {
      // 地址越界读：不产生下游 AR，按 ARLEN 返回等长 DECERR burst。
      io.in.R.RVALID := true.B
      io.in.R.RID := rId
      io.in.R.RDATA := 0.U
      io.in.R.RRESP := DECERR
      io.in.R.RLAST := rBeat === rLen
      when(io.in.R.RREADY) {
        when(rBeat === rLen) {
          rState := rIdle
        }.otherwise {
          rBeat := rBeat + 1.U
        }
      }
    }

    is(rDnSend) {
      // 64->32：为当前上游 beat 的一个 4 字节块发一笔单拍 AR。
      val BeatAddr = BeatAddress(rAddr, rBeat, rSize, rBurst, rLen)
      val ChunkAddr = ChunkAddress(BeatAddr, rChunk)
      io.out.AR.ARVALID := true.B
      io.out.AR.ARID := rId
      io.out.AR.ARADDR := DownAddress(ChunkAddr)
      io.out.AR.ARLEN := 0.U
      io.out.AR.ARSIZE := DownLaneBits.U(3.W)
      io.out.AR.ARBURST := 1.U
      io.out.AR.ARPROT := rProt
      io.out.AR.ARCACHE := rCache
      io.out.AR.ARQOS := rQos
      when(io.out.AR.ARREADY) {
        rState := rDnWait
      }
    }

    is(rDnWait) {
      val BeatAddr = BeatAddress(rAddr, rBeat, rSize, rBurst, rLen)
      val ByteBase = BeatByteBase(BeatAddr)
      val ChunkAddr = ChunkAddress(BeatAddr, rChunk)
      val ByteOffset = (ChunkAddr - ByteBase)(UpLaneBits - 1, 0)
      val Partial = rBeat === 0.U || rBurst === 0.U
      val LastChunk = BeatChunkCount(BeatAddr, rSize, Partial) - 1.U
      io.out.R.RREADY := true.B
      when(io.out.R.RVALID) {
        assert(
          io.out.R.RID === rId,
          "AXI5WidthBridge: downstream read response ID mismatch"
        )
        assert(
          io.out.R.RLAST,
          "AXI5WidthBridge: single-beat downstream read missing RLAST"
        )
        val Shift = ByteOffset << 3
        rData := rData |
          ((io.out.R.RDATA.pad(UpstreamDataWidth) << Shift)(
            UpstreamDataWidth - 1,
            0
          ))
        when(io.out.R.RRESP =/= OKAY) {
          rRespCode := io.out.R.RRESP
        }
        when(rChunk === LastChunk) {
          rState := rDnOut
        }.otherwise {
          rChunk := rChunk + 1.U
          rState := rDnSend
        }
      }
    }

    is(rDnOut) {
      io.in.R.RVALID := true.B
      io.in.R.RID := rId
      io.in.R.RDATA := rData
      io.in.R.RRESP := rRespCode
      io.in.R.RLAST := rBeat === rLen
      when(io.in.R.RREADY) {
        when(rBeat === rLen) {
          rState := rIdle
        }.otherwise {
          rBeat := rBeat + 1.U
          rChunk := 0.U
          rData := 0.U
          rRespCode := OKAY
          rState := rDnSend
        }
      }
    }
  }

  // ------------------------------------------------------------------
  // 读通道协议断言
  // ------------------------------------------------------------------
  when(rState === rIdle && io.in.AR.ARVALID && io.in.AR.ARREADY) {
    rUpRLastSeen := false.B
    rTxnIssuedAr := false.B
  }
  when(rDnArFire) { rTxnIssuedAr := true.B }
  when(io.in.R.RVALID && io.in.R.RREADY && io.in.R.RLAST) {
    rUpRLastSeen := true.B
  }
  when(rDnArFire =/= rDnRLastFire) {
    rDnOutstanding := Mux(
      rDnArFire,
      rDnOutstanding + 1.U,
      rDnOutstanding - 1.U
    )
  }

  when(rDnOutstanding > 1.U) {
    assert(
      false.B,
      "AXI5WidthBridge: more than one downstream read outstanding"
    )
  }
  when(rState === rIdle) {
    assert(
      rDnOutstanding === 0.U,
      "AXI5WidthBridge: idle with downstream read outstanding"
    )
  }
  when(io.out.AR.ARVALID) {
    assert(
      io.out.AR.ARID === rId,
      "AXI5WidthBridge: downstream read request ID mismatch"
    )
  }
  when(io.out.R.RVALID) {
    assert(
      rDnOutstanding =/= 0.U || rDnArFire,
      "AXI5WidthBridge: downstream R without outstanding AR"
    )
    assert(
      io.out.R.RID === rId,
      "AXI5WidthBridge: downstream read response ID mismatch"
    )
  }
  when(io.in.R.RVALID) {
    assert(rState =/= rIdle, "AXI5WidthBridge: upstream R while idle")
    assert(
      io.in.R.RID === rId,
      "AXI5WidthBridge: upstream read response ID mismatch"
    )
    assert(!rUpRLastSeen, "AXI5WidthBridge: duplicate upstream RLAST")
    assert(
      io.in.R.RLAST === (rBeat === rLen),
      "AXI5WidthBridge: upstream RLAST does not match ARLEN"
    )
    when(io.in.R.RLAST) {
      assert(
        rDnOutstanding === 0.U ||
          (io.out.R.RVALID && io.out.R.RLAST) ||
          rState === rErrData,
        "AXI5WidthBridge: upstream final R while downstream read is outstanding"
      )
    }
    when(rState === rErrData) {
      assert(
        !rTxnIssuedAr,
        "AXI5WidthBridge: local DECERR R after issuing downstream AR"
      )
    }
  }
}
