package RV32I
import chisel3._
class RegisterFile(val ADDR_WIDTH: Int = 1, val DATA_WIDTH: Int = 1)
    extends Module {
  val io = IO(new Bundle {
    val wdata = Input(UInt(DATA_WIDTH.W))
    val waddr = Input(UInt(ADDR_WIDTH.W))
    val wen = Input(Bool())
  })
  val rf = Reg(Vec(1 << ADDR_WIDTH, UInt(DATA_WIDTH.W)))
  when(io.wen) {
    rf(io.waddr) := io.wdata
  }
}
