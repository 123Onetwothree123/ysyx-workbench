package ysyx_26030103.common
import chisel3._
class ysyx_26030103_GPR(
    val XLEN: Int = 32,
    val EnableDynamicDebug: Boolean = true
) extends Module {
  require(XLEN == 32 || XLEN == 64, s"GPR XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val wdata = Input(UInt(XLEN.W))
    val WriteSELECT = Input(UInt(5.W))
    val WriteEN = Input(Bool())
    val Read1SELECT = Input(UInt(5.W))
    val Read2SELECT = Input(UInt(5.W))
    val ReadDATA1 = Output(UInt(XLEN.W))
    val ReadDATA2 = Output(UInt(XLEN.W))
    val DebugA0 = Output(UInt(XLEN.W))
    val DebugRaddr = Input(UInt(5.W))
    val DebugRdata = Output(UInt(XLEN.W))
  })
  val ysyx_26030103_RegisterFile = Module(
    new ysyx_26030103_RegisterFile(
      ADDR_WIDTH = 5,
      DATA_WIDTH = XLEN,
      EnableDynamicDebug = EnableDynamicDebug
    )
  )
  val RegisterFileWen = Mux(io.WriteSELECT === 0.U, false.B, io.WriteEN)
  ysyx_26030103_RegisterFile.io.wdata := io.wdata
  ysyx_26030103_RegisterFile.io.waddr := io.WriteSELECT
  ysyx_26030103_RegisterFile.io.wen := RegisterFileWen
  ysyx_26030103_RegisterFile.io.raddr1 := io.Read1SELECT
  ysyx_26030103_RegisterFile.io.raddr2 := io.Read2SELECT
  io.ReadDATA1 := Mux(
    io.Read1SELECT === 0.U,
    0.U(XLEN.W),
    ysyx_26030103_RegisterFile.io.rdata1
  )
  io.ReadDATA2 := Mux(
    io.Read2SELECT === 0.U,
    0.U(XLEN.W),
    ysyx_26030103_RegisterFile.io.rdata2
  )
  io.DebugA0 := ysyx_26030103_RegisterFile.io.debug_a0
  ysyx_26030103_RegisterFile.io.debug_raddr := io.DebugRaddr
  io.DebugRdata := ysyx_26030103_RegisterFile.io.debug_rdata
}
