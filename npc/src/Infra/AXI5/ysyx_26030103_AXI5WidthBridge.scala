package ysyx_26030103.infra

import chisel3._
import chisel3.util._

class ysyx_26030103_AXI5WidthBridge(
    private[infra] val UpstreamAddressWidth: Int = 64,
    private[infra] val DownstreamAddressWidth: Int = 32,
    private[infra] val UpstreamDataWidth: Int = 32,
    private[infra] val DownstreamDataWidth: Int = 64,
    private[infra] val IDWidth: Int = 4,
    // 指定 ID 的读请求可使用独立的第二读槽，避免 CPU 已挂起的读（如
    // 停在上游的取指）把 DMA 读串行堵在共享桥内。None 保持单 outstanding。
    private[infra] val BypassReadID: Option[Int] = None,
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
  private[infra] val upBytes = UpstreamDataWidth / 8
  private[infra] val downBytes = DownstreamDataWidth / 8
  private[infra] val upLaneBits = log2Ceil(upBytes)
  private[infra] val downLaneBits = log2Ceil(downBytes)
  private[infra] val downsize = UpstreamDataWidth > DownstreamDataWidth
  private[infra] val upsize = UpstreamDataWidth < DownstreamDataWidth
  private[infra] val addressNarrows = DownstreamAddressWidth < UpstreamAddressWidth
  private[infra] val OKAY = 0.U(2.W)
  private[infra] val DECERR = 3.U(2.W)

  val io = IO(new Bundle {
    val in =
      Flipped(new ysyx_26030103_AXI5IO(
        UpstreamAddressWidth,
        UpstreamDataWidth,
        IDWidth
      ))
    val out =
      new ysyx_26030103_AXI5IO(
        DownstreamAddressWidth,
        DownstreamDataWidth,
        IDWidth
      )
    // 精简内部 AXI bundle 没有 LOCK 字段。外部边界必须把上游 AWLOCK/
    // ARLOCK 在地址握手拍锁存，并与桥内部正在处理的事务绑定；下游只能
    // 看到本桥实际转发的那笔内部事务。锁存值保持到对应 B/最后一个 R
    // 握手，避免仲裁器在稍后的内部 AW/AR 握手时采样到已经撤销的外部
    // LOCK（AXI 只要求 LOCK 在 VALID 期间稳定）。
    val InAWLock = if (TrackExclusive) Some(Input(Bool())) else None
    val InARLock = if (TrackExclusive) Some(Input(Bool())) else None
    val OutAWLock = if (TrackExclusive) Some(Output(Bool())) else None
    val OutARLock = if (TrackExclusive) Some(Output(Bool())) else None
  })
  private[infra] val InAWLock = io.InAWLock.getOrElse(false.B)
  private[infra] val InARLock = io.InARLock.getOrElse(false.B)

  private[infra] def downAddress(address: UInt): UInt = {
    if (DownstreamAddressWidth >= UpstreamAddressWidth) {
      address.pad(DownstreamAddressWidth)
    } else {
      address(DownstreamAddressWidth - 1, 0)
    }
  }

  // 按 INCR/WRAP/FIXED 计算末字节；`length + 1` 扩到 9 位，避免 AWLEN=255
  // 在 8 位加法里回绕后把跨 4GiB 边界的 burst 算错。
  private[infra] def outOfRange(
      address: UInt,
      length: UInt,
      size: UInt,
      burst: UInt
  ): Bool = {
    if (!addressNarrows) {
      false.B
    } else {
      val start = Cat(0.U(1.W), address)
      val lastByte =
        ysyx_26030103_AXI5Burst.lastByte(address, length, size, burst)
      start(UpstreamAddressWidth, DownstreamAddressWidth).orR ||
      lastByte(UpstreamAddressWidth, DownstreamAddressWidth).orR
    }
  }

  // 非对齐 INCR 首拍从原始地址开始，后续拍从 size 对齐边界递增。
  private[infra] def beatAddressOf(
      base: UInt,
      beat: UInt,
      size: UInt,
      burst: UInt,
      length: UInt
  ): UInt = {
    val width = math.max(UpstreamAddressWidth, DownstreamAddressWidth) + 1
    val baseExt = base.pad(width)
    val sizeMask = ((1.U << size) - 1.U).pad(width)
    val alignedBase = (baseExt & (~sizeMask).asUInt)(width - 1, 0)
    val step = (beat.pad(width) << size)(width - 1, 0)
    val inc = (alignedBase + step)(width - 1, 0)
    val incAddress = Mux(beat === 0.U, baseExt, inc)
    val wrapMask =
      (ysyx_26030103_AXI5Burst.totalBytes(
        length,
        ysyx_26030103_AXI5Burst.beatBytes(size, width)
      ) - 1.U).pad(width)
    val wrapped =
      ((baseExt & (~wrapMask).asUInt) | (inc & wrapMask))(width - 1, 0)
    Mux(
      burst === 2.U,
      wrapped,
      Mux(burst === 1.U, incAddress, baseExt)
    )
  }

  // 上游数据总线上一个 beat 的字节起始地址（按总线宽度对齐）。
  private[infra] def beatByteBase(address: UInt): UInt =
    ((address >> upLaneBits) << upLaneBits)

  // 64->32 拆分时，第 chunk 个 4 字节块的下游地址。
  private[infra] def chunkAddressOf(beatAddress: UInt, chunk: UInt): UInt = {
    val base = (beatAddress >> downLaneBits) << downLaneBits
    base + (chunk << downLaneBits)
  }

  // 宽 beat 或跨 4 字节边界的非对齐 beat 需要拆两拍；有效字节从地址 % size 算。
  private[infra] def beatChunkCount(
      beatAddress: UInt,
      size: UInt,
      partial: Bool
  ): UInt = {
    val lane = beatAddress(upLaneBits - 1, 0)
    val sizeBytes = (1.U << size).pad(upLaneBits + 1)
    val offset = lane.pad(upLaneBits + 1) & (sizeBytes - 1.U)
    val span = Mux(partial, sizeBytes - offset, sizeBytes)
    val firstChunk = lane >> downLaneBits
    val lastChunk =
      ((lane.pad(upLaneBits + 1) + span - 1.U) >> downLaneBits)
    (lastChunk - firstChunk + 1.U)(1, 0)
  }

  private[infra] def extractData(data: UInt, byteOffset: UInt): UInt = {
    val padded = data.pad(math.max(UpstreamDataWidth, DownstreamDataWidth))
    val shifted = (padded >> (byteOffset << 3)).pad(DownstreamDataWidth)
    shifted(DownstreamDataWidth - 1, 0)
  }

  // 从上游 WSTRB 中取出下游字节块对应的掩码。
  private[infra] def extractStrobe(strb: UInt, byteOffset: UInt): UInt = {
    val padded = strb.pad(math.max(upBytes, downBytes))
    val shifted = (padded >> byteOffset).pad(downBytes)
    shifted(downBytes - 1, 0)
  }

  // 整个上游字搬到地址 bit[2] 对应的半区；直接按地址 % 8 移位会让 lane 2 漂到 lane 4。
  private[infra] def upsizeLane(beatAddress: UInt): UInt =
    if (upsize) {
      Cat(beatAddress(downLaneBits - 1, upLaneBits), 0.U(upLaneBits.W))
    } else { 0.U }

  private[infra] def upDataToDown(data: UInt, lane: UInt): UInt = {
    val shift = lane << 3
    val padded = data.pad(math.max(UpstreamDataWidth, DownstreamDataWidth))
    (padded << shift)(DownstreamDataWidth - 1, 0)
  }

  private[infra] def upStrobeToDown(strb: UInt, lane: UInt): UInt = {
    val padded = strb.pad(math.max(upBytes, downBytes))
    val shifted = padded << lane
    shifted(downBytes - 1, 0)
  }

  private[infra] def downDataToUp(data: UInt, lane: UInt): UInt = {
    val shift = lane << 3
    // 注意 pad 不能只扩到上游宽度：32->64 时下游 RDATA 比上游宽，
    // 先把高半区截掉再右移会读到错误数据。这里扩到两侧最大宽度。
    val padded = data.pad(math.max(UpstreamDataWidth, DownstreamDataWidth))
    (padded >> shift)(UpstreamDataWidth - 1, 0)
  }

  // 默认把所有 VALID 拉低，下面按状态覆盖，避免产生锁存器。
  io.in.AW.AWREADY := false.B
  io.in.W.WREADY := false.B
  io.in.B.BVALID := false.B
  io.in.B.BID := 0.U
  io.in.B.BRESP := OKAY

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

  connectWrite()

  connectRead()
}
