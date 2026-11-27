package ysyx_26030103.exu
import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._
import _root_.ysyx_26030103.config._
class ysyx_26030103_CompactALU(
    override val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_ALU(config) with ysyx_26030103_ALUCommonBackend {
  private val isSub = IsSubLike(io.ALUCtrl)
  private val arithmeticEnable = AdderEnable || SubtractorEnable
  val arithmeticUnit = Module(new ysyx_26030103_ALUAdder(config))
  private val arithmeticA = Isolate(io.A, arithmeticEnable)
  private val arithmeticB = Isolate(io.B, arithmeticEnable)
  arithmeticUnit.io.A := arithmeticA
  arithmeticUnit.io.B := Mux(isSub, ~arithmeticB, arithmeticB)
  arithmeticUnit.io.Cin := isSub // 减法的时候当补码用
  ConnectShiftAndLogic()
  // 仅有 SLT/SLTU 会使用这两个标志；此时 IsSub 必为真，算术单元输出
  // 对应 A-B 的借位。ADD/逻辑/移位路径不会消费它们。
  val sltuFlag = !arithmeticUnit.io.carryOut
  val sltFlag = arithmeticA(XLEN - 1).asBool ^
    arithmeticB(XLEN - 1).asBool ^ sltuFlag
  // 同一算术结果分别接到 add/sub 端口：IsSub 保证对应端口上的值正确，
  // 未被 ALUCtrl 选中的端口不会出现在最终 result 中。
  ConnectResultSelect(
    adderSum = arithmeticUnit.io.sum,
    subResult = arithmeticUnit.io.sum,
    slt = sltFlag,
    sltu = sltuFlag,
    addw = arithmeticUnit.io.sum(31, 0),
    subw = arithmeticUnit.io.sum(31, 0)
  )
}
