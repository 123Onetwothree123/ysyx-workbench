package RV32I
import chisel3._
import chisel3.util._
import RV32I.opcode._
class ALUOpDecoder extends Module {
  val io = IO(new Bundle {
    val opcode = Input(UInt(7.W))
    val ALUOp = Output(UInt(2.W))
  })
  val ALUOP_ADDR = 0.U(2.W)
  val ALUOP_BRANCH = 1.U(2.W)
  val ALUOP_ARITH = 2.U(2.W)
  val ALUOP_MISC = 3.U(2.W)
  io.ALUOp := ALUOP_MISC
  switch(io.opcode) {
    is(OPCODE_Immediate_Lxxx, OPCODE_Immediate_Bxxx, OPCODE_Store, OPCODE_UpperImmediate_auipc, OPCODE_Jump) {
      io.ALUOp := ALUOP_ADDR
    }
    is(OPCODE_Branch) {
      io.ALUOp := ALUOP_BRANCH
    }
    is(OPCODE_Immediate, OPCODE_Register) {
      io.ALUOp := ALUOP_ARITH
    }
    is(OPCODE_UpperImmediate_lui) {
      io.ALUOp := ALUOP_MISC
    }
  }
}