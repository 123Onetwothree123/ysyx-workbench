package RV32I.AXI5Lite
import chisel3._
import chisel3.util._
class AXI5LiteB extends Bundle {
  val BRESP = Input(UInt(2.W)) // 标准写的是0，2，3
  val BVALID = Input(Bool()) // 有效
  val BREADY = Output(Bool()) // 准备
}
