package ysyx_26030103.ysyx_26030103_ALU
import chisel3._
import chisel3.util._
import ysyx_26030103.ysyx_26030103_General.ysyx_26030103_opcode._
class ysyx_26030103_ALUOpDecoder extends Module {
  val io = IO(new Bundle {
    val opcode = Input(UInt(7.W))
    val ALUOp = Output(UInt(2.W))
  })
  // 和Verilog版本一样
  val ALUOP_ADDR = 0.U(2.W) // 地址计算
  val ALUOP_BRANCH = 1.U(2.W) // 分支比较
  val ALUOP_ARITH = 2.U(2.W) // 算数的
  val ALUOP_MISC = 3.U(2.W) // other
  io.ALUOp := ALUOP_MISC // 先给个默认的，之前就出现过没有给初始值结果跑编译都失败的情况
  switch(io.opcode) {
    is(
      OPCODE_Immediate_Lxxx,
      OPCODE_Immediate_Bxxx,
      OPCODE_Store,
      OPCODE_UpperImmediate_auipc,
      OPCODE_Jump
    ) {
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
