package RV32I
import chisel3._
import chisel3.util._
class Memory extends Module{
    val io=IO(new Bundle {
        val valid=Input(Bool())
        val wen=Input(Bool())
        val raddr=Input(UInt(32.W))
        val waddr=Input(UInt(32.W))
        val wdata=Input(UInt(32.W))
        val wmask=Input(UInt(4.W))
        val rdata=Output(UInt(32.W))
    })
}