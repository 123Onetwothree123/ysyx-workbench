package ysyx_26030103.infra

import chisel3._
import chisel3.util._

/** AXI5 地址/数据宽度桥。
  * 支持 32/64 位宽转换、INCR/FIXED/WRAP burst、WSTRB/RLAST；
  * 下游地址更窄时对整笔 burst 做越界检查并本地返回 DECERR。
  * 同一方向只处理一笔事务，用反压保证握手稳定。
  */
class ysyx_26030103_AXI5WidthBridge(
    private[infra] val UpstreamAddressWidth: Int = 64,
    private[infra] val DownstreamAddressWidth: Int = 32,
    private[infra] val UpstreamDataWidth: Int = 32,
    private[infra] val DownstreamDataWidth: Int = 64,
    private[infra] val IdWidth: Int = 4,
    // 只有外部 CPU slave 边界需要 LOCK 旁带。内部/默认实例没有这两个
    // 端口，避免强迫所有 WidthBridge 使用方连接它们。
    private[infra] val TrackExclusive: Boolean = false
) extends Module
    with AXI5WidthBridgeWrite
    with AXI5WidthBridgeRead {
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
  private[infra] val UpBytes = UpstreamDataWidth / 8
  private[infra] val DownBytes = DownstreamDataWidth / 8
  private[infra] val UpLaneBits = log2Ceil(UpBytes)
  private[infra] val DownLaneBits = log2Ceil(DownBytes)
  private[infra] val Downsize = UpstreamDataWidth > DownstreamDataWidth
  private[infra] val Upsize = UpstreamDataWidth < DownstreamDataWidth
  private[infra] val AddressNarrows = DownstreamAddressWidth < UpstreamAddressWidth
  private[infra] val OKAY = 0.U(2.W)
  private[infra] val DECERR = 3.U(2.W)

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
    // 精简内部 AXI bundle 没有 LOCK 字段。外部边界必须把上游 AWLOCK/
    // ARLOCK 在地址握手拍锁存，并与桥内部正在处理的事务绑定；下游只能
    // 看到本桥实际转发的那笔内部事务。锁存值保持到对应 B/最后一个 R
    // 握手，避免仲裁器在稍后的内部 AW/AR 握手时采样到已经撤销的外部
    // LOCK（AXI 只要求 LOCK 在 VALID 期间稳定）。
    val inAWLock = if (TrackExclusive) Some(Input(Bool())) else None
    val inARLock = if (TrackExclusive) Some(Input(Bool())) else None
    val outAWLock = if (TrackExclusive) Some(Output(Bool())) else None
    val outARLock = if (TrackExclusive) Some(Output(Bool())) else None
  })
  private[infra] val InAWLock = io.inAWLock.getOrElse(false.B)
  private[infra] val InARLock = io.inARLock.getOrElse(false.B)

  /** 把上游地址映射到下游地址宽度。仅在 `!OutOfRange` 时使用；
    * 越界事务不会走到下游，因此这里的低位切片不构成静默截断。
    */
  private[infra] def DownAddress(Address: UInt): UInt = {
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
  private[infra] def OutOfRange(
      Address: UInt,
      Len: UInt,
      Size: UInt,
      Burst: UInt
  ): Bool = {
    if (!AddressNarrows) {
      false.B
    } else {
      val Start = Cat(0.U(1.W), Address)
      val LastByte =
        ysyx_26030103_AXI5Burst.lastByte(Address, Len, Size, Burst)
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
  private[infra] def BeatAddress(
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
    val WrapMask =
      (ysyx_26030103_AXI5Burst.totalBytes(
        Len,
        ysyx_26030103_AXI5Burst.beatBytes(Size, Width)
      ) - 1.U).pad(Width)
    val Wrapped =
      ((BaseExt & (~WrapMask).asUInt) | (Inc & WrapMask))(Width - 1, 0)
    Mux(
      Burst === 2.U,
      Wrapped,
      Mux(Burst === 1.U, IncAddress, BaseExt)
    )
  }

  /** 上游数据总线上一个 beat 的字节起始地址（按总线宽度对齐）。 */
  private[infra] def BeatByteBase(Address: UInt): UInt =
    ((Address >> UpLaneBits) << UpLaneBits)

  /** 64->32 拆分时，第 Chunk 个 4 字节块的下游地址。 */
  private[infra] def ChunkAddress(BeatAddr: UInt, Chunk: UInt): UInt = {
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
  private[infra] def BeatChunkCount(
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
  private[infra] def ExtractData(Data: UInt, ByteOffset: UInt): UInt = {
    val Padded = Data.pad(math.max(UpstreamDataWidth, DownstreamDataWidth))
    val Shifted = (Padded >> (ByteOffset << 3)).pad(DownstreamDataWidth)
    Shifted(DownstreamDataWidth - 1, 0)
  }

  /** 从上游 WSTRB 中取出下游字节块对应的掩码。 */
  private[infra] def ExtractStrb(Strb: UInt, ByteOffset: UInt): UInt = {
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
  private[infra] def UpsizeLane(BeatAddr: UInt): UInt =
    if (Upsize) {
      Cat(BeatAddr(DownLaneBits - 1, UpLaneBits), 0.U(UpLaneBits.W))
    } else { 0.U }

  private[infra] def UpDataToDown(Data: UInt, Lane: UInt): UInt = {
    val Shift = Lane << 3
    val Padded = Data.pad(math.max(UpstreamDataWidth, DownstreamDataWidth))
    (Padded << Shift)(DownstreamDataWidth - 1, 0)
  }

  private[infra] def UpStrbToDown(Strb: UInt, Lane: UInt): UInt = {
    val Padded = Strb.pad(math.max(UpBytes, DownBytes))
    val Shifted = Padded << Lane
    Shifted(DownBytes - 1, 0)
  }

  private[infra] def DownDataToUp(Data: UInt, Lane: UInt): UInt = {
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

  connectWrite()

  connectRead()
}
