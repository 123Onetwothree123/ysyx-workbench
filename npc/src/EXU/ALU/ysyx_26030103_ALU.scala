package ysyx_26030103.exu
import _root_.ysyx_26030103.common.ysyx_26030103_XLENRequire
import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._
import _root_.ysyx_26030103.config._

// 所有 ALU 后端共用的组合接口：单拍组合执行，不需要 Decoupled 握手；
// `result` 必须只由当前 A/B/ALUCtrl 决定，后端不得依赖隐藏状态。
class ysyx_26030103_ALUInterface(val XLEN: Int = 32) extends Bundle {
  ysyx_26030103_XLENRequire(XLEN, "ALUInterface")
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
  ysyx_26030103_XLENRequire(XLEN, "ALU")
  require(config.ALUImpl != ysyx_26030103_ALUImpl.External,
    "ALU_IMPL=external 只是预留接口，当前没有外部 ALU 实现")
  val io = IO(new ysyx_26030103_ALUInterface(XLEN))
}

trait ysyx_26030103_ALUCommonBackend { this: ysyx_26030103_ALU =>
  protected val XLEN: Int = config.XLEN
  protected val ShiftAmountWidth: Int = if (XLEN == 64) {
    6
  } else {
    5
  }
  protected val AdderEnable: Bool = IsAddLike(io.ALUCtrl)
  protected val SubtractorEnable: Bool = IsSubLike(io.ALUCtrl)
  protected val ShifterEnable: Bool = IsShiftLike(io.ALUCtrl)
  protected val BitLogicEnable: Bool = IsLogicLike(io.ALUCtrl)
  protected def Isolate(value: UInt, enable: Bool): UInt =
    Mux(enable, value, 0.U(value.getWidth.W))
  val shifterUnit = Module(new ysyx_26030103_ALUShifter(XLEN))
  val bitLogicUnit = Module(new ysyx_26030103_ALUBitLogic(XLEN))
  val resultSelectUnit = Module(new ysyx_26030103_ALUResultSelect(XLEN))
  protected def ConnectShiftAndLogic(): Unit = {
    shifterUnit.io.A := Isolate(io.A, ShifterEnable)
    shifterUnit.io.shamt := Isolate(io.B(ShiftAmountWidth - 1, 0), ShifterEnable)
    bitLogicUnit.io.A := Isolate(io.A, BitLogicEnable)
    bitLogicUnit.io.B := Isolate(io.B, BitLogicEnable)
  }
  protected def ConnectResultSelect(
      adderSum: UInt,
      subResult: UInt,
      slt: Bool,
      sltu: Bool,
      addw: UInt,
      subw: UInt
  ): Unit = {
    resultSelectUnit.io.ALUCtrl := io.ALUCtrl
    resultSelectUnit.io.adderSum := adderSum
    resultSelectUnit.io.subResult := subResult
    resultSelectUnit.io.slt := slt
    resultSelectUnit.io.sltu := sltu
    resultSelectUnit.io.sll := shifterUnit.io.sll
    resultSelectUnit.io.srl := shifterUnit.io.srl
    resultSelectUnit.io.sra := shifterUnit.io.sra
    resultSelectUnit.io.xorResult := bitLogicUnit.io.xorResult
    resultSelectUnit.io.orResult := bitLogicUnit.io.orResult
    resultSelectUnit.io.andResult := bitLogicUnit.io.andResult
    resultSelectUnit.io.addw := addw
    resultSelectUnit.io.subw := subw
    resultSelectUnit.io.sllw := shifterUnit.io.sllw
    resultSelectUnit.io.srlw := shifterUnit.io.srlw
    resultSelectUnit.io.sraw := shifterUnit.io.sraw
    io.result := resultSelectUnit.io.result
  }
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
