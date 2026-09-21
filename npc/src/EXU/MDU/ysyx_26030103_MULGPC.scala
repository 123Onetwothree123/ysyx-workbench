package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_MULGPCConfig

// 参数化K:R通用并行计数器；具体输出编码在elaboration期由权重配置求解。
class ysyx_26030103_MULGPC(val Config: ysyx_26030103_MULGPCConfig) extends Module {
  private val EncodingWidth = Config.OutputCountWithCout
  private val CountWidth = log2Ceil(Config.InputCountWithCin + 1).max(1)

  final val IO = _root_.chisel3.IO(new Bundle {
    val In = Input(Vec(Config.Inputs, Bool()))
    val Cin = Input(Bool())
    val Out = Output(Vec(Config.Outputs, Bool()))
    val Cout = Output(Bool())
  })

  val BaseCount = PopCount(IO.In)
  val Count = Wire(UInt(CountWidth.W))
  Count := (if (Config.HasCin) (BaseCount +& IO.Cin.asUInt)(CountWidth - 1, 0) else BaseCount)
  val EncodingTable = VecInit(Config.EncodingTable.map(_.U(EncodingWidth.W)))
  val Selected = EncodingTable(Count)
  for (Index <- 0 until Config.Outputs) {
    IO.Out(Index) := Selected(Index)
  }
  IO.Cout := (if (Config.HasCout) Selected(Config.Outputs) else false.B)
}
