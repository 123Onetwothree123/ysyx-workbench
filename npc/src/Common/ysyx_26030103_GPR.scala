package ysyx_26030103.common
import chisel3._
class ysyx_26030103_GPR(
    val XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    val EnableDynamicDebug: Boolean = true
) extends Module {
  ysyx_26030103_XLENRequire(XLEN, "GPR")
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
      ReadPorts = 2,
      ZeroReg0 = true,
      DebugA0Fallback = true,
      EnableDynamicDebug = EnableDynamicDebug
    )
  )
  registerFile.io.wdata := io.wdata
  registerFile.io.waddr := io.WriteSELECT
  registerFile.io.wen := io.WriteEN
  registerFile.io.raddr1 := io.Read1SELECT
  registerFile.io.raddr2 := io.Read2SELECT
  registerFile.io.raddr3 := 0.U
  io.ReadDATA1 := registerFile.io.rdata1
  io.ReadDATA2 := registerFile.io.rdata2
  registerFile.io.DebugRaddr := io.DebugRaddr
  io.DebugReadData := registerFile.io.DebugRdata
}
