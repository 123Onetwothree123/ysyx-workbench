package ysyx_26030103.exu
import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._
import _root_.ysyx_26030103.config._

// 所有 ALU 后端共用的组合接口：单拍组合执行，不需要 Decoupled 握手；
// `result` 必须只由当前 A/B/ALUCtrl 决定，后端不得依赖隐藏状态。
class ysyx_26030103_ALUInterface(val XLEN: Int = 32) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"ALUInterface XLEN 非法: $XLEN")
  val A = Input(UInt(XLEN.W))
  val B = Input(UInt(XLEN.W))
  val ALUCtrl = Input(UInt(CtrlWidth.W))
  val result = Output(UInt(XLEN.W))
}

// 后端实现继承本类并只实现统一接口；EXU 只依赖该抽象类型。
abstract class ysyx_26030103_ALU(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends Module {
  private val XLEN = config.XLEN
  require(XLEN == 32 || XLEN == 64, s"ALU XLEN 非法: $XLEN")
  require(config.ALUImpl != ysyx_26030103_ALUImpl.External,
    "ALU_IMPL=external 只是预留接口，当前没有外部 ALU 实现")
  val io = IO(new ysyx_26030103_ALUInterface(XLEN))
}

// ALU 工厂：按 Kconfig 选择后端。
object ysyx_26030103_ALU {
  def apply(config: ysyx_26030103_NPCConfig): ysyx_26030103_ALU = {
    config.ALUImpl match {
      case ysyx_26030103_ALUImpl.Standard =>
        new ysyx_26030103_StandardALU(config)
      case ysyx_26030103_ALUImpl.Compact =>
        new ysyx_26030103_CompactALU(config)
      case ysyx_26030103_ALUImpl.External =>
        throw new NotImplementedError(
          "ALU_IMPL=external 只是预留接口，当前没有外部 ALU 实现"
        )
    }
  }
}
