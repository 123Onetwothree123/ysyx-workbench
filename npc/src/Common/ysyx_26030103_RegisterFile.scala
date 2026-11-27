package ysyx_26030103.common
import chisel3._
class ysyx_26030103_RegisterFile(
    val ADDR_WIDTH: Int = 1,
    val DATA_WIDTH: Int = 1,
    val ReadPorts: Int = 2,
    val ZeroReg0: Boolean = false,
    val DebugA0Fallback: Boolean = false,
    val InitZero: Boolean = false,
    val EnableDynamicDebug: Boolean = true
) extends Module {
  require(ADDR_WIDTH >= 1, "RegisterFile ADDR_WIDTH必须至少为1")
  require(DATA_WIDTH >= 1, "RegisterFile DATA_WIDTH必须至少为1")
  require(ReadPorts == 2 || ReadPorts == 3, s"RegisterFile ReadPorts非法: $ReadPorts")
  val io = IO(new Bundle {
    val wdata = Input(UInt(DATA_WIDTH.W))
    val waddr = Input(UInt(ADDR_WIDTH.W))
    val wen = Input(Bool())
    val raddr1 = Input(UInt(ADDR_WIDTH.W))
    val rdata1 = Output(UInt(DATA_WIDTH.W))
    val raddr2 = Input(UInt(ADDR_WIDTH.W))
    val rdata2 = Output(UInt(DATA_WIDTH.W))
    val raddr3 = Input(UInt(ADDR_WIDTH.W))
    val rdata3 = Output(UInt(DATA_WIDTH.W))
    val DebugRaddr = Input(UInt(ADDR_WIDTH.W))
    val DebugRdata = Output(UInt(DATA_WIDTH.W))
  })
  val registers =
    if (InitZero) {
      RegInit(VecInit(Seq.fill(1 << ADDR_WIDTH)(0.U(DATA_WIDTH.W))))
    } else {
      Reg(Vec(1 << ADDR_WIDTH, UInt(DATA_WIDTH.W)))
    }
  private def ReadReg(addr: UInt): UInt =
    if (ZeroReg0) {
      Mux(addr === 0.U, 0.U(DATA_WIDTH.W), registers(addr))
    } else {
      registers(addr)
    }
  val writeEnable = if (ZeroReg0) io.wen && io.waddr =/= 0.U else io.wen
  when(writeEnable) {
    registers(io.waddr) := io.wdata
  }
  io.rdata1 := ReadReg(io.raddr1)
  io.rdata2 := ReadReg(io.raddr2)
  if (ReadPorts == 3) {
    io.rdata3 := ReadReg(io.raddr3)
  } else {
    io.rdata3 := 0.U(DATA_WIDTH.W)
  }
  if (EnableDynamicDebug) {
    io.DebugRdata := ReadReg(io.DebugRaddr)
  } else if (DebugA0Fallback) {
    io.DebugRdata := (if ((1 << ADDR_WIDTH) > 10) {
      registers(10.U)
    } else {
      0.U(DATA_WIDTH.W)
    })
  } else {
    io.DebugRdata := 0.U(DATA_WIDTH.W)
  }
}
