package ysyx_26030103.common
import chisel3._
class ysyx_26030103_GPR(
    val XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
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
    val DebugRaddr = Input(UInt(5.W))
    val DebugReadData = Output(UInt(XLEN.W))
  })
  val registerFile = Module(
    new ysyx_26030103_RegisterFile(
      ADDR_WIDTH = 5,
      DATA_WIDTH = XLEN,
      EnableDynamicDebug = EnableDynamicDebug
    )
  )
  val registerFileWriteEnable = Mux(io.WriteSELECT === 0.U, false.B, io.WriteEN)
  registerFile.io.wdata := io.wdata
  registerFile.io.waddr := io.WriteSELECT
  registerFile.io.wen := registerFileWriteEnable
  registerFile.io.raddr1 := io.Read1SELECT
  registerFile.io.raddr2 := io.Read2SELECT
  io.ReadDATA1 := Mux(
    io.Read1SELECT === 0.U,
    0.U(XLEN.W),
    registerFile.io.rdata1
  )
  io.ReadDATA2 := Mux(
    io.Read2SELECT === 0.U,
    0.U(XLEN.W),
    registerFile.io.rdata2
  )
  registerFile.io.debug_raddr := io.DebugRaddr
  io.DebugReadData := registerFile.io.debug_rdata
}
