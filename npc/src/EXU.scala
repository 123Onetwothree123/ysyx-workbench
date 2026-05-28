package RV32I
import chisel3._
import chisel3.util._

class EXU extends Module {
  val io = IO(new Bundle {
    val ALUCtrl = Input(UInt(4.W))
    val SourceDATA_A = Input(UInt(32.W))
    val SourceDATA_B = Input(UInt(32.W))
    val ALUResult = Output(UInt(32.W))
  })
  // 简单起见就直接输入给alu，以后再考虑搞个控制环境吧
  val alu = Module(new ALU)
  alu.io.A := io.SourceDATA_A
  alu.io.B := io.SourceDATA_B
  alu.io.ALUCtrl := io.ALUCtrl
  io.ALUResult := alu.io.result
}
