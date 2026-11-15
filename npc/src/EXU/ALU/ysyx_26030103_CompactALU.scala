package ysyx_26030103.exu
import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._
import _root_.ysyx_26030103.config._
class ysyx_26030103_CompactALU(
    override val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_ALU(config) {
  private val XLEN = config.XLEN
  private val shiftAmountWidth = if (XLEN == 64) {
    6
  } else {
    5
  }
  // 这个设计思路应该是因为后面需要计算a-b的借位来判断大小，最后结果不是差值，是0或者1
  private val isSub = io.ALUCtrl === SUB || io.ALUCtrl === SLT ||
    io.ALUCtrl === SLTU || io.ALUCtrl === SUBW
  // 新补充的操作数隔离，共享算术单元只在加减或者比较的时候看到有效操作数
  // 移位器和位逻辑也只在对应控制码被激活的情况下才会翻转
  private val arithmeticEnable =
    IsAddLike(io.ALUCtrl) || IsSubLike(io.ALUCtrl)
  private val shifterEnable = IsShiftLike(io.ALUCtrl)
  private val bitLogicEnable = IsLogicLike(io.ALUCtrl)
  private def isolate(value: UInt, enable: Bool): UInt =
    Mux(enable, value, 0.U(value.getWidth.W))
  val arithmeticUnit = Module(new ysyx_26030103_ALUAdder(config))
  val shifterUnit = Module(new ysyx_26030103_ALUShifter(XLEN))
  val bitLogicUnit = Module(new ysyx_26030103_ALUBitLogic(XLEN))
  val resultSelectUnit = Module(new ysyx_26030103_ALUResultSelect(XLEN))
  private val arithmeticA = isolate(io.A, arithmeticEnable)
  private val arithmeticB = isolate(io.B, arithmeticEnable)
  arithmeticUnit.io.A := arithmeticA
  arithmeticUnit.io.B := Mux(isSub, ~arithmeticB, arithmeticB)
  arithmeticUnit.io.Cin := isSub // 减法的时候当补码用
  shifterUnit.io.A := isolate(io.A, shifterEnable)
  shifterUnit.io.shamt := isolate(io.B(shiftAmountWidth - 1, 0), shifterEnable)
  bitLogicUnit.io.A := isolate(io.A, bitLogicEnable)
  bitLogicUnit.io.B := isolate(io.B, bitLogicEnable)
  //AI残留的
  // 仅有 SLT/SLTU 会使用这两个标志；此时 IsSub 必为真，算术单元输出
  // 对应 A-B 的借位。ADD/逻辑/移位路径不会消费它们。
  val sltuFlag = !arithmeticUnit.io.carryOut
  val sltFlag = arithmeticA(XLEN - 1).asBool ^
    arithmeticB(XLEN - 1).asBool ^ sltuFlag
  resultSelectUnit.io.ALUCtrl := io.ALUCtrl
  // 同一算术结果分别接到 add/sub 端口：IsSub 保证对应端口上的值正确，
  // 未被 ALUCtrl 选中的端口不会出现在最终 result 中。
  resultSelectUnit.io.adderSum := arithmeticUnit.io.sum
  resultSelectUnit.io.subResult := arithmeticUnit.io.sum
  resultSelectUnit.io.slt := sltFlag
  resultSelectUnit.io.sltu := sltuFlag
  resultSelectUnit.io.sll := shifterUnit.io.sll
  resultSelectUnit.io.srl := shifterUnit.io.srl
  resultSelectUnit.io.sra := shifterUnit.io.sra
  resultSelectUnit.io.xorResult := bitLogicUnit.io.xorResult
  resultSelectUnit.io.orResult := bitLogicUnit.io.orResult
  resultSelectUnit.io.andResult := bitLogicUnit.io.andResult
  resultSelectUnit.io.addw := arithmeticUnit.io.sum(31, 0)
  resultSelectUnit.io.subw := arithmeticUnit.io.sum(31, 0)
  resultSelectUnit.io.sllw := shifterUnit.io.sllw
  resultSelectUnit.io.srlw := shifterUnit.io.srlw
  resultSelectUnit.io.sraw := shifterUnit.io.sraw
  io.result := resultSelectUnit.io.result
}
