package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._
import _root_.ysyx_26030103.config._

/** 紧凑 ALU 后端：复用一个可配置加法器完成加法和减法。
  *
  * 标准后端同时实例化 Adder 和 Subtractor，供普通 ADD/SUB 在同一拍
  * 并行计算；本后端根据 ALUCtrl 选择 B 是否取反、Cin 是否为 1，因此
  * 省掉一个宽算术单元。结果是组合路径略深，但指令语义不变。
  */
class ysyx_26030103_CompactALU(
    override val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_ALU(config) {
  private val XLEN = config.XLEN
  private val ShamtWidth = if (XLEN == 64) 6 else 5

  private val IsSub = io.ALUCtrl === SUB || io.ALUCtrl === SLT ||
    io.ALUCtrl === SLTU || io.ALUCtrl === SUBW

  val ArithmeticUnit = Module(new ysyx_26030103_ALUAdder(config))
  val ShifterUnit = Module(new ysyx_26030103_ALUShifter(XLEN))
  val BitLogicUnit = Module(new ysyx_26030103_ALUBitLogic(XLEN))
  val ResultSelectUnit = Module(new ysyx_26030103_ALUResultSelect(XLEN))

  ArithmeticUnit.io.A := io.A
  ArithmeticUnit.io.B := Mux(IsSub, ~io.B, io.B)
  ArithmeticUnit.io.Cin := IsSub
  ShifterUnit.io.A := io.A
  ShifterUnit.io.shamt := io.B(ShamtWidth - 1, 0)
  BitLogicUnit.io.A := io.A
  BitLogicUnit.io.B := io.B

  // 仅有 SLT/SLTU 会使用这两个标志；此时 IsSub 必为真，算术单元输出
  // 对应 A-B 的借位。ADD/逻辑/移位路径不会消费它们。
  val SltuFlag = !ArithmeticUnit.io.carryOut
  val SltFlag = io.A(XLEN - 1).asBool ^ io.B(XLEN - 1).asBool ^ SltuFlag

  ResultSelectUnit.io.ALUCtrl := io.ALUCtrl
  // 同一算术结果分别接到 add/sub 端口：IsSub 保证对应端口上的值正确，
  // 未被 ALUCtrl 选中的端口不会出现在最终 result 中。
  ResultSelectUnit.io.adderSum := ArithmeticUnit.io.sum
  ResultSelectUnit.io.subResult := ArithmeticUnit.io.sum
  ResultSelectUnit.io.slt := SltFlag
  ResultSelectUnit.io.sltu := SltuFlag
  ResultSelectUnit.io.sll := ShifterUnit.io.sll
  ResultSelectUnit.io.srl := ShifterUnit.io.srl
  ResultSelectUnit.io.sra := ShifterUnit.io.sra
  ResultSelectUnit.io.xorResult := BitLogicUnit.io.xorResult
  ResultSelectUnit.io.orResult := BitLogicUnit.io.orResult
  ResultSelectUnit.io.andResult := BitLogicUnit.io.andResult
  ResultSelectUnit.io.addw := ArithmeticUnit.io.sum(31, 0)
  ResultSelectUnit.io.subw := ArithmeticUnit.io.sum(31, 0)
  ResultSelectUnit.io.sllw := ShifterUnit.io.sllw
  ResultSelectUnit.io.srlw := ShifterUnit.io.srlw
  ResultSelectUnit.io.sraw := ShifterUnit.io.sraw

  io.result := ResultSelectUnit.io.result
}
