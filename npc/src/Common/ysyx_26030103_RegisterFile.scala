package ysyx_26030103.common
import chisel3._
class ysyx_26030103_RegisterFile(
    val ADDR_WIDTH: Int = 1,
    val DATA_WIDTH: Int = 1
) extends Module {
  require(ADDR_WIDTH >= 1, "RegisterFile ADDR_WIDTH必须至少为1")
  require(DATA_WIDTH >= 1, "RegisterFile DATA_WIDTH必须至少为1")
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
  when(io.wen) {
    ysyx_26030103_RegisterFile(io.waddr) := io.wdata
  }
  io.rdata1 := ysyx_26030103_RegisterFile(io.raddr1)
  io.rdata2 := ysyx_26030103_RegisterFile(io.raddr2)
  io.debug_a0 := (if ((1 << ADDR_WIDTH) > 10) {
    ysyx_26030103_RegisterFile(10.U)
  } else {
    0.U(DATA_WIDTH.W)
  })
  io.debug_rdata := Mux(
    io.debug_raddr === 0.U,
    0.U(DATA_WIDTH.W),
    ysyx_26030103_RegisterFile(io.debug_raddr)
  )
}
