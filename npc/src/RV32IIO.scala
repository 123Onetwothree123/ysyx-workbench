package RV32I
import chisel3._
import chisel3.util._
import _root_.RV32I.AXI5Lite._
object RV32IIO {
  val bit = 32
}
class RV32IIO extends Bundle {
  val InstructionsBus = new AXI5LiteMaster(RV32IIO.bit)
  val DataBus = new AXI5LiteMaster(RV32IIO.bit)
  // 中断信号，真的不想要用DPI-C监察来跑verilator停止仿真来实现ebreak的功能了
  val Interrupt = Input(Bool())
  val TrapValid = Output(Bool())
  val TrapCode = Output(UInt(32.W))
  val TrapPC = Output(UInt(32.W))
}
