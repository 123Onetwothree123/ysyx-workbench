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
  val wen_reg = RegNext(io.in.fire && io.in.bits.RegisterWrite, false.B)
  val wdata_reg = RegNext(0.U(32.W))
  val waddr_reg = RegNext(0.U(5.W))
  when(io.in.fire) {
    waddr_reg := io.in.bits.Rd
    switch(io.in.bits.WBSelect) {
      is("b00".U) { wdata_reg := io.in.bits.ALUResult }
      is("b01".U) { wdata_reg := io.in.bits.LoadData }
      is("b10".U) { wdata_reg := io.in.bits.snpc }
      is("b11".U) { wdata_reg := io.in.bits.CSRReadData }
    }
  }
  io.WriteEN := wen_reg
  io.wdata := wdata_reg
  io.WriteSELECT := waddr_reg
}
