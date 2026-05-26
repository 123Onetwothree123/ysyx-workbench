package RV32I
import chisel3._
import chisel3.util._
class RV32IIO extends Bundle {
  val InstructionAddress = Output(UInt(32.W))
  val InstructionReadDATA = Input(UInt(32.W))
  val MemWE = Output(Bool())
  val MemAddr = Output(UInt(32.W))
  val MemWriteDATA = Output(UInt(32.W))
  val MemWriteMask = Output(UInt(4.W))
  val MemoryReadDATA = Input(UInt(32.W))
}