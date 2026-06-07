package RV32I.GPR
import chisel3._
class RegisterFile(val ADDR_WIDTH: Int = 1, val DATA_WIDTH: Int = 1)
    extends Module {
  val io = IO(new Bundle {
    val wdata = Input(UInt(DATA_WIDTH.W))
    val waddr = Input(UInt(ADDR_WIDTH.W))
    val wen = Input(Bool())
    val raddr1 = Input(UInt(ADDR_WIDTH.W))
    val rdata1 = Output(UInt(DATA_WIDTH.W))
    val raddr2 = Input(UInt(ADDR_WIDTH.W))
    val rdata2 = Output(UInt(DATA_WIDTH.W))
    val debug_a0 = Output(UInt(DATA_WIDTH.W))
  })
  val RegisterFile = Reg(Vec(1 << ADDR_WIDTH, UInt(DATA_WIDTH.W)))
  when(io.wen) {
    RegisterFile(io.waddr) := io.wdata
  }
  io.rdata1 := RegisterFile(io.raddr1)
  io.rdata2 := RegisterFile(io.raddr2)
  io.debug_a0 := RegisterFile(10.U)
}
