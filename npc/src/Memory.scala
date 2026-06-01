package RV32I
import chisel3._
import chisel3.util._
class Memory extends Module {
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val wen = Input(Bool())
    val raddr = Input(UInt(32.W))
    val waddr = Input(UInt(32.W))
    val wdata = Input(UInt(32.W))
    val wmask = Input(UInt(4.W))
    val rdata = Output(UInt(32.W))
  })
  //临时的
  io.rdata := 0.U
  // val memory = Module(new RegisterFile(ADDR_WIDTH = 8, DATA_WIDTH = 32))
  // memory.io.raddr1 := io.raddr(9, 2)
  // memory.io.raddr2 := io.waddr(9, 2)
  // val old = memory.io.rdata2 // 原来内存的word，主要是因为写的waddr连的是raddr2
  // val merged = Cat( // 先标记一下，先标记一下，这个单词是机翻出来的，merge的过去时，merge是合并的意思
  //   Mux(io.wmask(3), io.wdata(31, 24), old(31, 24)),
  //   Mux(io.wmask(2), io.wdata(23, 16), old(23, 16)),
  //   Mux(io.wmask(1), io.wdata(15, 8), old(15, 8)),
  //   Mux(io.wmask(0), io.wdata(7, 0), old(7, 0))
  // )
  // memory.io.wen := io.valid && io.wen
  // memory.io.waddr:=io.waddr(9,2)
  // memory.io.wdata:=merged
  // io.rdata:=memory.io.rdata1
}