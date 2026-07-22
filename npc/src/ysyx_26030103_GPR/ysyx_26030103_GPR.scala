package ysyx_26030103.ysyx_26030103_GPR
import chisel3._
import _root_.ysyx_26030103.AXIDebugProbe
class ysyx_26030103_GPR extends Module {
  val io = IO(new Bundle {
    val wdata = Input(UInt(32.W))
    val WriteSELECT = Input(UInt(5.W))
    val WriteEN = Input(Bool())
    val Read1SELECT = Input(UInt(5.W))
    val Read2SELECT = Input(UInt(5.W))
    val ReadDATA1 = Output(UInt(32.W))
    val ReadDATA2 = Output(UInt(32.W))
    val DebugA0 = Output(UInt(32.W))
    val DebugRaddr = Input(UInt(5.W))
    val DebugRdata = Output(UInt(32.W))
  })
  val ysyx_26030103_RegisterFile = Module(
    new ysyx_26030103_RegisterFile(ADDR_WIDTH = 5, DATA_WIDTH = 32)
  )
  val RegisterFileWen = Mux(io.WriteSELECT === 0.U, false.B, io.WriteEN)
  ysyx_26030103_RegisterFile.io.wdata := io.wdata
  ysyx_26030103_RegisterFile.io.waddr := io.WriteSELECT
  ysyx_26030103_RegisterFile.io.wen := RegisterFileWen
  ysyx_26030103_RegisterFile.io.raddr1 := io.Read1SELECT
  ysyx_26030103_RegisterFile.io.raddr2 := io.Read2SELECT
  val fwd_valid = RegInit(false.B)
  val fwd_addr = RegInit(0.U(5.W))
  val fwd_data = RegInit(0.U(32.W))
  when(RegisterFileWen) {
    fwd_valid := true.B
    fwd_addr := io.WriteSELECT
    fwd_data := io.wdata
  }.otherwise {
    fwd_valid := false.B
  }
  io.ReadDATA1 := Mux(
    io.Read1SELECT === 0.U,
    0.U(32.W),
    Mux(fwd_valid && io.Read1SELECT === fwd_addr, fwd_data,
      ysyx_26030103_RegisterFile.io.rdata1)
  )
  io.ReadDATA2 := Mux(
    io.Read2SELECT === 0.U,
    0.U(32.W),
    Mux(fwd_valid && io.Read2SELECT === fwd_addr, fwd_data,
      ysyx_26030103_RegisterFile.io.rdata2)
  )
  io.DebugA0 := ysyx_26030103_RegisterFile.io.debug_a0
  ysyx_26030103_RegisterFile.io.debug_raddr := io.DebugRaddr
  io.DebugRdata := ysyx_26030103_RegisterFile.io.debug_rdata
  val gpr_probe = Module(new AXIDebugProbe)
  gpr_probe.io.trigger := RegisterFileWen && io.WriteSELECT === 15.U
  gpr_probe.io.tag := 5.U
  gpr_probe.io.addr := io.WriteSELECT
  gpr_probe.io.resp := io.wdata
}
