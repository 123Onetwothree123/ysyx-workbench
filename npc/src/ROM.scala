package RV32I
import chisel3._
import chisel3.util._
class ROM extends Module{
    val io=IO(new Bundle {
        val Address=Input(UInt(32.W))
        val ReadDATA=Output(UInt(32.W))
    })
}