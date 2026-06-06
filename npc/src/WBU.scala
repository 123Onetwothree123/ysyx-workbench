package RV32I
import chisel3._
import chisel3.util._
import _root_.RV32I.Message._
class WBU extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new EXUMessage))
    // 给GPR的
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
}
