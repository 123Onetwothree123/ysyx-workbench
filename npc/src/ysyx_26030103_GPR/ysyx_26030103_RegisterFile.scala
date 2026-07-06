package ysyx_26030103.ysyx_26030103_GPR
import chisel3._
class ysyx_26030103_RegisterFile(
    val ADDR_WIDTH: Int = 1,
    val DATA_WIDTH: Int = 1
) extends Module {
  val io = IO(new Bundle {
    val wdata = Input(UInt(DATA_WIDTH.W))
    val waddr = Input(UInt(ADDR_WIDTH.W))
    val wen = Input(Bool())
    val raddr1 = Input(UInt(ADDR_WIDTH.W))
    val rdata1 = Output(UInt(DATA_WIDTH.W))
    val raddr2 = Input(UInt(ADDR_WIDTH.W))
    val rdata2 = Output(UInt(DATA_WIDTH.W))
    val debug_a0 = Output(UInt(DATA_WIDTH.W))
    val debug_raddr = Input(UInt(ADDR_WIDTH.W))
    val debug_rdata = Output(UInt(DATA_WIDTH.W))
  })
  val ysyx_26030103_RegisterFile = Reg(Vec(1 << ADDR_WIDTH, UInt(DATA_WIDTH.W)))
  withClock(!clock) {
    when(io.wen) {
      ysyx_26030103_RegisterFile(io.waddr) := io.wdata
    }
  }
  io.rdata1 := ysyx_26030103_RegisterFile(io.raddr1)
  io.rdata2 := ysyx_26030103_RegisterFile(io.raddr2)
  io.debug_a0 := ysyx_26030103_RegisterFile(10.U)
  io.debug_rdata := Mux(
    io.debug_raddr === 0.U,
    0.U(DATA_WIDTH.W),
    ysyx_26030103_RegisterFile(io.debug_raddr)
  )
}
