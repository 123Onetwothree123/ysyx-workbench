package RV32I
import chisel3._
import chisel3.util._
import RV32I.Message._
class EXU extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new IDUMessage))
    val out = Decoupled(new EXUMessage)
    val MemoryReadDATA = Input(UInt(32.W))
    
  })
}
