package ysyx_26030103.exu
import chisel3._
import _root_.ysyx_26030103.config._
class ysyx_26030103_StandardALU(
    override val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_ALU(config) with ysyx_26030103_ALUCommonBackend {
  val adderUnit = Module(new ysyx_26030103_ALUAdder(config))
  val subtractorUnit = Module(new ysyx_26030103_ALUSubtractor(config))
  adderUnit.io.A := Isolate(io.A, AdderEnable)
  adderUnit.io.B := Isolate(io.B, AdderEnable)
  adderUnit.io.Cin := false.B // 普通加法没有进位输入
  subtractorUnit.io.A := Isolate(io.A, SubtractorEnable)
  subtractorUnit.io.B := Isolate(io.B, SubtractorEnable)
  ConnectShiftAndLogic()
  ConnectResultSelect(
    adderSum = adderUnit.io.sum,
    subResult = subtractorUnit.io.sub,
    slt = subtractorUnit.io.slt,
    sltu = subtractorUnit.io.sltu,
    addw = adderUnit.io.sumWord,
    subw = subtractorUnit.io.subWord
  )
}
