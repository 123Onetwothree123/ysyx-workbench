package ysyx_26030103.exu
import chisel3._

// ALU 减法器：SUB 以及 SLT/SLTU 的比较都从这里派生，省掉独立的比较器。
// 多扩展一位做减法，最高位记录无符号意义下"是否不够减"：
//   sltu = !ext(XLEN)
//   slt  = A(XLEN-1) ^ B(XLEN-1) ^ sltu
class ysyx_26030103_ALUSubtractor(val XLEN: Int = 32) extends Module {
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
  private val ext = (io.A +& (~io.B)) + 1.U
  io.sub := ext(XLEN - 1, 0)
  io.sltu := !ext(XLEN)
  io.slt := io.A(XLEN - 1).asBool ^ io.B(XLEN - 1).asBool ^ io.sltu
  private val extWord = (io.A(31, 0) +& (~io.B(31, 0))) + 1.U
  io.subWord := extWord(31, 0)
}
