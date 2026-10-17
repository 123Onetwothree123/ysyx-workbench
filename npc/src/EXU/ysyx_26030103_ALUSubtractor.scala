package ysyx_26030103.exu
import chisel3._

// ALU 减法器：SUB 以及 SLT/SLTU 的比较都从这里派生，省掉独立的比较器。
// 多扩展一位做减法，最高位记录无符号意义下"是否不够减"：
//   sltu = !ext(32)
//   slt  = A(31) ^ B(31) ^ sltu   （符号位不同则看符号位，相同则等价于无符号比较）
class ysyx_26030103_ALUSubtractor extends Module {
  val io = IO(new Bundle {
    val A = Input(UInt(32.W))
    val B = Input(UInt(32.W))
    val sub = Output(UInt(32.W))
    val slt = Output(Bool())
    val sltu = Output(Bool())
  })
  private val ext = (io.A +& (~io.B)) + 1.U
  io.sub := ext(31, 0)
  io.sltu := !ext(32)
  io.slt := io.A(31).asBool ^ io.B(31).asBool ^ io.sltu
}
