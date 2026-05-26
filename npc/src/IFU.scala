package RV32I
import chisel3._
import chisel3.util._
class IFU extends Module{
    val io=IO(new Bundle{
        val PC=Input(UInt(32.W))
        val InstructionReadDATA=Input(UInt(32.W))
        val InstructionAddress=Output(UInt(32.W))
        val InstructionOutput=Output(UInt(32.W))
        val SNPC=Output(UInt(32.W))
    })
    io.InstructionAddress := io.PC
    io.InstructionOutput := io.InstructionReadDATA
    io.SNPC := io.PC + 4.U(32.W)
}