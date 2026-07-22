package ysyx_26030103
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.ysyx_26030103_Message._
class ysyx_26030103_WBU extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_EXUMessage))
    // 给ysyx_26030103_GPR的
    val WriteSELECT = Output(UInt(5.W))
    val WriteEN = Output(Bool())
    val wdata = Output(UInt(32.W))
  })
  io.in.ready := true.B
  io.WriteSELECT := io.in.bits.Rd
  io.WriteEN := io.in.fire && io.in.bits.RegisterWrite
  io.wdata := 0.U(32.W)
  switch(io.in.bits.WBSelect) {
    is("b00".U) {
      io.wdata := io.in.bits.ALUResult
    }
    is("b01".U) {
      io.wdata := io.in.bits.LoadData
    }
    is("b10".U) {
      io.wdata := io.in.bits.snpc
    }
    is("b11".U) {
      io.wdata := io.in.bits.CSRReadData
    }
  }
  val wb_probe = Module(new AXIDebugProbe)
  wb_probe.io.trigger := io.in.fire && io.in.bits.RegisterWrite
  wb_probe.io.tag := 6.U
  wb_probe.io.addr := io.in.bits.Rd
  wb_probe.io.resp := io.in.bits.snpc
}
