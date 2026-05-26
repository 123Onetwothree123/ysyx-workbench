package RV32I
import chisel3._
import chisel3.util._
class PC extends Module {
  val io = IO(new Bundle {
    val NextPC = Input(UInt(32.W)) // 下一条PC值
    val PCEnable = Input(Bool()) // PC写使能
    val PC = Output(UInt(32.W)) // 当前PC值
  })
  val RESET_ADDR = 0x80000000L
  val PCReg = RegInit(RESET_ADDR.U(32.W))
  when(io.PCEnable) {
    PCReg := io.NextPC
  }
  io.PC := PCReg
}
