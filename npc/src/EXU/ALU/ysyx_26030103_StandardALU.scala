package ysyx_26030103.exu
import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._
import _root_.ysyx_26030103.config._
class ysyx_26030103_StandardALU(
    override val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_ALU(config) {
  private val XLEN = config.XLEN
  private val shiftAmountWidth = if (XLEN == 64) {
    6
  } else {
    5
  }
  // 然后就是补上操作数隔离，只有现在的控制码真正需要的子块才可以去看到有效的操作数
  // 没有选中的子块输入直接强制锁定在0，内部节点不再随操作数翻转
  private val adderEnable = IsAddLike(io.ALUCtrl) // 要加法器
  private val subtractorEnable = IsSubLike(io.ALUCtrl) // 要减法器
  private val shifterEnable = IsShiftLike(io.ALUCtrl) // 要移位器
  private val bitLogicEnable = IsLogicLike(io.ALUCtrl) // 这是位逻辑
  private def isolate(value: UInt, enable: Bool): UInt =
    Mux(enable, value, 0.U(value.getWidth.W)) // 没用的子块就把数值改成0
  val adderUnit = Module(new ysyx_26030103_ALUAdder(config))
  val subtractorUnit = Module(new ysyx_26030103_ALUSubtractor(config))
  val shifterUnit = Module(new ysyx_26030103_ALUShifter(XLEN))
  val bitLogicUnit = Module(new ysyx_26030103_ALUBitLogic(XLEN))
  val resultSelectUnit = Module(new ysyx_26030103_ALUResultSelect(XLEN))
  adderUnit.io.A := isolate(io.A, adderEnable)
  adderUnit.io.B := isolate(io.B, adderEnable)
  adderUnit.io.Cin := false.B // 普通加法没有进位输入
  subtractorUnit.io.A := isolate(io.A, subtractorEnable)
  subtractorUnit.io.B := isolate(io.B, subtractorEnable)
  shifterUnit.io.A := isolate(io.A, shifterEnable)
  // 这是之前AI残留的：普通移位取低 6 位（RV64）或低 5 位（RV32）；W 型在 Shifter 内只取低 5 位。
  shifterUnit.io.shamt := isolate(io.B(shiftAmountWidth - 1, 0), shifterEnable)
  bitLogicUnit.io.A := isolate(io.A, bitLogicEnable)
  bitLogicUnit.io.B := isolate(io.B, bitLogicEnable)
  resultSelectUnit.io.ALUCtrl := io.ALUCtrl
  resultSelectUnit.io.adderSum := adderUnit.io.sum
  resultSelectUnit.io.subResult := subtractorUnit.io.sub
  resultSelectUnit.io.slt := subtractorUnit.io.slt
  resultSelectUnit.io.sltu := subtractorUnit.io.sltu
  resultSelectUnit.io.sll := shifterUnit.io.sll
  resultSelectUnit.io.srl := shifterUnit.io.srl
  resultSelectUnit.io.sra := shifterUnit.io.sra
  resultSelectUnit.io.xorResult := bitLogicUnit.io.xorResult
  resultSelectUnit.io.orResult := bitLogicUnit.io.orResult
  resultSelectUnit.io.andResult := bitLogicUnit.io.andResult
  resultSelectUnit.io.addw := adderUnit.io.sumWord
  resultSelectUnit.io.subw := subtractorUnit.io.subWord
  resultSelectUnit.io.sllw := shifterUnit.io.sllw
  resultSelectUnit.io.srlw := shifterUnit.io.srlw
  resultSelectUnit.io.sraw := shifterUnit.io.sraw
  io.result := resultSelectUnit.io.result
}
