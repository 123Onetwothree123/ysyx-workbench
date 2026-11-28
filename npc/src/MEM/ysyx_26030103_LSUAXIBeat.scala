package ysyx_26030103.mem

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_AXI5Lane.{
  LaneStrobe, LaneWord
}

/** 一笔 AXI 访问的拍形状：长度、每拍宽度和总拍数。
  *
  * `len`/`size` 直接对应 AxLEN/AxSIZE，`lastBeat` 供 WLAST 与 R 拍判定。
  * 这里只描述协议字段，不拥有拍计数器；普通/原子/分段路径仍各自保留
  * 自己的 beat 寄存器，避免把不同的错误恢复路径强行合并。
  */
private[mem] final case class ysyx_26030103_AXIBeatPlan(
    len: UInt,
    size: UInt,
    twoBeat: Bool
) {
  // 当前 helper 只产生一拍或两拍访问；单拍时首/末拍恒真，
  // 不依赖拍计数器一定为 0，避免把复位/残留值带进 LAST 判定。
  def firstBeat(beat: UInt): Bool = !twoBeat || beat === 0.U
  def lastBeat(beat: UInt): Bool = !twoBeat || beat === 1.U
  def multiBeat: Bool = twoBeat
  /** 拍数仅在调用方显式需要时生成，默认路径直接用 twoBeat。 */
  def beats: UInt = Mux(twoBeat, 2.U(2.W), 1.U(2.W))
}

/** LSU 普通读/写与 LR/SC/AMO 读/写共用的 AXI 拍协议 helper。
  *
  * 32 位总线承载 64 位访问时，对齐访问拆成两拍 INCR burst；非对齐
  * store 的每个粒度段是独立单拍。两套状态机只消费这里的 LEN/SIZE/
  * LAST 与 WDATA/WSTRB 放置规则，AXI 信号和 RLAST/WLAST 语义不变。
  */
private[mem] object ysyx_26030103_LSUAXIBeat {
  private def burstLen(twoBeat: Bool): UInt =
    Mux(twoBeat, 1.U(8.W), 0.U(8.W))

  /** 宽度编码 -> AXI AxSIZE；8 字节访问在 32 位总线上仍按 2 编码。 */
  def WidthSize(width: UInt): UInt = MuxLookup(width, 2.U(3.W))(
    Seq("b00".U -> 0.U(3.W), "b01".U -> 1.U(3.W), "b10".U -> 2.U(3.W),
      "b11".U -> 2.U(3.W))
  )

  /** 普通 load：.D 在 32 位总线上拆两拍。 */
  def Read(
      width: UInt,
      wide: Bool,
      busWide: Boolean
  ): ysyx_26030103_AXIBeatPlan = {
    val twoBeat = wide && !busWide.B
    val size = Mux(wide && busWide.B, 3.U(3.W), WidthSize(width))
    ysyx_26030103_AXIBeatPlan(burstLen(twoBeat), size, twoBeat)
  }

  /** LR/AMO 读：.W 恒单拍，.D 在 32 位总线上两拍。 */
  def Atomic(
      wide: Bool,
      busWide: Boolean
  ): ysyx_26030103_AXIBeatPlan = {
    val twoBeat = wide && !busWide.B
    val size = Mux(wide && busWide.B, 3.U(3.W), 2.U(3.W))
    ysyx_26030103_AXIBeatPlan(burstLen(twoBeat), size, twoBeat)
  }

  /** 普通 store：非对齐分段单拍；对齐 .D 在 32 位总线上两拍。
    *
    * `widthSize` 是单 word 的 AXI AxSIZE 编码（wbHeadSize），不是原始
    * WidthSelect；这样非宽访问直接复用写缓冲已存的编码。
    */
  def Store(
      widthSize: UInt,
      wide: Bool,
      busWide: Boolean,
      misaligned: Bool,
      segmentWide: Bool
  ): ysyx_26030103_AXIBeatPlan = {
    val twoBeat = wide && !busWide.B && !misaligned
    val size = Mux(
      misaligned,
      Mux(segmentWide, 3.U(3.W), 2.U(3.W)),
      Mux(wide && busWide.B, 3.U(3.W), widthSize)
    )
    ysyx_26030103_AXIBeatPlan(burstLen(twoBeat), size, twoBeat)
  }

  /** 对齐写的 WDATA：低/高 32 位 word 按总线宽度和 beat 放置。 */
  def WriteData(
      low: UInt,
      high: UInt,
      address: UInt,
      wide: Bool,
      busWide: Boolean,
      beat: UInt
  ): UInt =
    Mux(
      wide && busWide.B,
      Cat(high, low),
      Mux(
        wide && !busWide.B && beat === 1.U,
        high,
        LaneWord(low, address, busWide)
      )
    )

  /** 对齐写的 WSTRB：第二拍是高 4 字节，其余按地址 lane 放置。 */
  def WriteStrobe(
      lowStrobe: UInt,
      address: UInt,
      wide: Bool,
      busWide: Boolean,
      beat: UInt
  ): UInt =
    Mux(
      wide && busWide.B,
      "hff".U,
      Mux(
        wide && !busWide.B && beat === 1.U,
        "hf".U,
        LaneStrobe(lowStrobe, address, busWide)
      )
    )
}
