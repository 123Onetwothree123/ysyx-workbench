package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_MULGPCConfig

// 参数化K:R通用并行计数器；具体输出编码在elaboration期由权重配置求解。
class ysyx_26030103_MULGPC(val Config: ysyx_26030103_MULGPCConfig) extends Module {
  private val encodingWidth = Config.OutputCountWithCout
  private val countWidth = log2Ceil(Config.InputCountWithCin + 1).max(1)

  final val IO = _root_.chisel3.IO(new Bundle {
    val In = Input(Vec(Config.Inputs, Bool()))
    val Cin = Input(Bool())
    val Out = Output(Vec(Config.Outputs, Bool()))
    val Cout = Output(Bool())
  })

  val baseCount = PopCount(IO.In)
  val count = Wire(UInt(countWidth.W))
  count := (if (Config.HasCin) (baseCount +& IO.Cin.asUInt)(countWidth - 1, 0) else baseCount)
  val encodingTable = VecInit(Config.EncodingTable.map(_.U(encodingWidth.W)))
  val selected = encodingTable(count)
  for (index <- 0 until Config.Outputs) {
    IO.Out(index) := selected(index)
  }
  IO.Cout := (if (Config.HasCout) selected(Config.Outputs) else false.B)
}
