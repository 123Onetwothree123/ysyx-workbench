package ysyx_26030103
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.ysyx_26030103_Message._
class ysyx_26030103_WBU extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_EXUMessage))
    val WriteSELECT = Output(UInt(5.W))
    val WriteEN = Output(Bool())
    val wdata = Output(UInt(32.W))
    val Commit = Output(Bool())
  })
  io.in.ready := true.B
  io.WriteSELECT := io.in.bits.Rd
  val fire = io.in.fire && io.in.bits.RegisterWrite
  io.WriteEN := fire
  // 用 set-clear 寄存器确保 commit 至少持续一整拍
  val commitSet = WireDefault(false.B)
  val commitReg = RegInit(false.B)
  when (commitSet) { commitReg := true.B }
  .elsewhen (commitReg) { commitReg := false.B }
  // 在 fire 的那一拍 set
  commitSet := fire
  io.Commit := commitReg
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
}
