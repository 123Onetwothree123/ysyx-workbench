package ysyx_26030103.exu
import chisel3._
import _root_.ysyx_26030103.config._

// ALU 减法器：SUB 以及 SLT/SLTU 的比较都从这里派生，省掉独立的比较器。
// 复用 ALUAdder 的可配置结构，做 A + ~B + 1；carryOut=1 表示无借位：
//   sltu = !carryOut
//   slt  = A(XLEN-1) ^ B(XLEN-1) ^ sltu
class ysyx_26030103_ALUSubtractor(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends Module {
  private val XLEN = config.XLEN
  require(XLEN == 32 || XLEN == 64, s"ALUSubtractor XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val A = Input(UInt(XLEN.W))
    val B = Input(UInt(XLEN.W))
    val sub = Output(UInt(XLEN.W))
    val slt = Output(Bool())
    val sltu = Output(Bool())
    // RV64 SUBW：低 32 位差，最终由 ALU 做符号扩展。
    val subWord = Output(UInt(32.W))
  })
  private val adder = Module(new ysyx_26030103_ALUAdder(config))
  adder.io.A := io.A
  adder.io.B := ~io.B
  adder.io.Cin := true.B
  io.sub := adder.io.sum
  io.sltu := !adder.io.carryOut
  io.slt := io.A(XLEN - 1).asBool ^ io.B(XLEN - 1).asBool ^ io.sltu
  // 64 位减法的低 32 位与独立 32 位减法的低 32 位相同。
  io.subWord := adder.io.sum(31, 0)
}
