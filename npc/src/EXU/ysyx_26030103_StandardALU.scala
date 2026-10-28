package ysyx_26030103.exu
import chisel3._
import _root_.ysyx_26030103.config._

/** 标准 ALU 后端：加法、减法、移位、位逻辑并行计算，结果浅 mux。
  *
  * 加法器与减法器各自实例化一个 ALUAdder；两者都受 ALUProfile.Adder
  * 选择的结构后端控制。SLT/SLTU 不单独做比较器，直接由减法器的借位
  * 派生。
  */
class ysyx_26030103_StandardALU(
    override val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_ALU(config) {
  private val XLEN = config.XLEN
  private val ShamtWidth = if (XLEN == 64) 6 else 5

  val AdderUnit = Module(new ysyx_26030103_ALUAdder(config))
  val SubtractorUnit = Module(new ysyx_26030103_ALUSubtractor(config))
  val ShifterUnit = Module(new ysyx_26030103_ALUShifter(XLEN))
  val BitLogicUnit = Module(new ysyx_26030103_ALUBitLogic(XLEN))
  val ResultSelectUnit = Module(new ysyx_26030103_ALUResultSelect(XLEN))

  AdderUnit.io.A := io.A
  AdderUnit.io.B := io.B
  AdderUnit.io.Cin := false.B
  SubtractorUnit.io.A := io.A
  SubtractorUnit.io.B := io.B
  ShifterUnit.io.A := io.A
  // 普通移位取低 6 位（RV64）或低 5 位（RV32）；W 型在 Shifter 内只取低 5 位。
  ShifterUnit.io.shamt := io.B(ShamtWidth - 1, 0)
  BitLogicUnit.io.A := io.A
  BitLogicUnit.io.B := io.B

  ResultSelectUnit.io.ALUCtrl := io.ALUCtrl
  ResultSelectUnit.io.adderSum := AdderUnit.io.sum
  ResultSelectUnit.io.subResult := SubtractorUnit.io.sub
  ResultSelectUnit.io.slt := SubtractorUnit.io.slt
  ResultSelectUnit.io.sltu := SubtractorUnit.io.sltu
  ResultSelectUnit.io.sll := ShifterUnit.io.sll
  ResultSelectUnit.io.srl := ShifterUnit.io.srl
  ResultSelectUnit.io.sra := ShifterUnit.io.sra
  ResultSelectUnit.io.xorResult := BitLogicUnit.io.xorResult
  ResultSelectUnit.io.orResult := BitLogicUnit.io.orResult
  ResultSelectUnit.io.andResult := BitLogicUnit.io.andResult
  ResultSelectUnit.io.addw := AdderUnit.io.sumWord
  ResultSelectUnit.io.subw := SubtractorUnit.io.subWord
  ResultSelectUnit.io.sllw := ShifterUnit.io.sllw
  ResultSelectUnit.io.srlw := ShifterUnit.io.srlw
  ResultSelectUnit.io.sraw := ShifterUnit.io.sraw

  io.result := ResultSelectUnit.io.result
}
