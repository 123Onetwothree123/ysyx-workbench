package RV32I
import chisel3._
import chisel3.util._
class RV32I extends Module {
  val io = IO(new RV32IIO)
  val RESET_ADDR = 0x80000000L
  io := DontCare
}
