package RV32I
import chisel3._
import chisel3.util._
class WBU extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new EXUMessage))
    val RegisterFileWriteSELECT = Output(UInt(5.W))
    val RegisterFileWriteEN = Output(Bool())
    val RegisterFileWriteDATA = Output(UInt(32.W))
  })
  io.in.ready := true.B
  io.RegisterFileWriteSELECT := io.in.bits.Rd
  io.RegisterFileWriteEN := io.in.fire && io.in.bits.RegWrite
  io.RegisterFileWriteDATA := 0.U(32.W)
  switch(io.in.bits.WBSel) {
    is("b00".U) {
      io.RegisterFileWriteDATA := io.in.bits.ALUResult
    }
    is("b01".U) {
      io.RegisterFileWriteDATA := io.in.bits.LoadData
    }
    is("b10".U) {
      io.RegisterFileWriteDATA := io.in.bits.snpc
    }
    is("b11".U) {
      io.RegisterFileWriteDATA := io.in.bits.CSRReadData
    }
  }
}
