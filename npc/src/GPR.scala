package RV32I
import chisel3._
class GPR extends Module {
  val io = IO(new Bundle {
    val wdata = Input(UInt(32.W))
    val WriteSELECT = Input(UInt(5.W))
    val WriteEN = Input(Bool())
    val Read1SELECT = Input(UInt(5.W))
    val Read2SELECT = Input(UInt(5.W))
    val ReadDATA1 = Output(UInt(32.W))
    val ReadDATA2 = Output(UInt(32.W))
  })
  val RegisterFile = Module(new RegisterFile(ADDR_WIDTH = 5, DATA_WIDTH = 32))
  val RegisterFileWen = Mux(io.WriteSELECT === 0.U, false.B, io.WriteEN)
  RegisterFile.io.wdata := io.wdata
  RegisterFile.io.waddr := io.WriteSELECT
  RegisterFile.io.wen := RegisterFileWen
  RegisterFile.io.raddr1 := io.Read1SELECT
  RegisterFile.io.raddr2 := io.Read2SELECT
  io.ReadDATA1 := Mux(io.Read1SELECT === 0.U, 0.U(32.W), RegisterFile.io.rdata1)
  io.ReadDATA2 := Mux(io.Read2SELECT === 0.U, 0.U(32.W), RegisterFile.io.rdata2)
}
