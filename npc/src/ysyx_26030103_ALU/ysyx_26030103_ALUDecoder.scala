package ysyx_26030103.ysyx_26030103_ALU
import chisel3._
import chisel3.util._
import ysyx_26030103.ysyx_26030103_General.ysyx_26030103_opcode._
class ysyx_26030103_ALUDecoder extends Module {
  import ysyx_26030103_ALUFunction._
  val io = IO(new Bundle {
    val opcode = Input(UInt(7.W))
    val funct3 = Input(UInt(3.W))
    val funct7 = Input(UInt(7.W))
    val ALUCtrl = Output(UInt(CtrlWidth.W))
    val Illegal = Output(Bool())
  })
  private def MatchesAny(value: UInt, validValues: UInt*): Bool =
    validValues.map(value === _).reduceOption(_ || _).getOrElse(false.B)
  private def SetIllegal(): Unit = {
    io.ALUCtrl := NOP
    io.Illegal := true.B
  }
  io.ALUCtrl := NOP
  io.Illegal := false.B
  switch(io.opcode) {
    is(OPCODE_Immediate_Lxxx) {
      io.ALUCtrl := ADD
      when(
        !MatchesAny(
          io.funct3,
          0.U(3.W), // LB
          1.U(3.W), // LH
          2.U(3.W), // LW
          4.U(3.W), // LBU
          5.U(3.W)  // LHU
        )
      ) {
        SetIllegal()
      }
    }
    is(OPCODE_Store) {
      io.ALUCtrl := ADD
      when(!MatchesAny(io.funct3, 0.U(3.W), 1.U(3.W), 2.U(3.W))) {
        SetIllegal()
      }
    }
    is(OPCODE_Immediate_Bxxx) {
      io.ALUCtrl := ADD
      when(io.funct3 =/= 0.U(3.W)) {
        SetIllegal()
      }
    }
    is(OPCODE_UpperImmediate_auipc, OPCODE_Jump) {
      io.ALUCtrl := ADD
    }
    is(OPCODE_Branch) {
      io.ALUCtrl := SUB
      when(
        !MatchesAny(
          io.funct3,
          0.U(3.W), // BEQ
          1.U(3.W), // BNE
          4.U(3.W), // BLT
          5.U(3.W), // BGE
          6.U(3.W), // BLTU
          7.U(3.W)  // BGEU
        )
      ) {
        SetIllegal()
      }
    }
    is(OPCODE_Immediate) {
      switch(io.funct3) {
        is(0.U(3.W)) { // ADDI
          io.ALUCtrl := ADD
        }
        is(1.U(3.W)) { // SLLI RV32I要funct7=0000000
          when(io.funct7 === 0.U(7.W)) {
            io.ALUCtrl := SLL
          }.otherwise {
            SetIllegal()
          }
        }
        is(2.U(3.W)) { // SLTI
          io.ALUCtrl := SLT
        }
        is(3.U(3.W)) { // SLTIU
          io.ALUCtrl := SLTU
        }
        is(4.U(3.W)) { // XORI
          io.ALUCtrl := XOR
        }
        is(5.U(3.W)) { // SRLI/SRAI
          when(io.funct7 === 0.U(7.W)) {
            io.ALUCtrl := SRL
          }.elsewhen(io.funct7 === "b0100000".U(7.W)) {
            io.ALUCtrl := SRA
          }.otherwise {
            SetIllegal()
          }
        }
        is(6.U(3.W)) { // ORI
          io.ALUCtrl := OR
        }
        is(7.U(3.W)) { // ANDI
          io.ALUCtrl := AND
        }
      }
    }
    is(OPCODE_Register) {
      switch(io.funct3) {
        is(0.U(3.W)) { // ADD/SUB
          when(io.funct7 === 0.U(7.W)) {
            io.ALUCtrl := ADD
          }.elsewhen(io.funct7 === "b0100000".U(7.W)) {
            io.ALUCtrl := SUB
          }.otherwise {
            SetIllegal()
          }
        }
        is(1.U(3.W)) { // SLL
          when(io.funct7 === 0.U(7.W)) {
            io.ALUCtrl := SLL
          }.otherwise {
            SetIllegal()
          }
        }
        is(2.U(3.W)) { // SLT
          when(io.funct7 === 0.U(7.W)) {
            io.ALUCtrl := SLT
          }.otherwise {
            SetIllegal()
          }
        }
        is(3.U(3.W)) { // SLTU
          when(io.funct7 === 0.U(7.W)) {
            io.ALUCtrl := SLTU
          }.otherwise {
            SetIllegal()
          }
        }
        is(4.U(3.W)) { // XOR
          when(io.funct7 === 0.U(7.W)) {
            io.ALUCtrl := XOR
          }.otherwise {
            SetIllegal()
          }
        }
        is(5.U(3.W)) { // SRL/SRA
          when(io.funct7 === 0.U(7.W)) {
            io.ALUCtrl := SRL
          }.elsewhen(io.funct7 === "b0100000".U(7.W)) {
            io.ALUCtrl := SRA
          }.otherwise {
            SetIllegal()
          }
        }
        is(6.U(3.W)) { // OR
          when(io.funct7 === 0.U(7.W)) {
            io.ALUCtrl := OR
          }.otherwise {
            SetIllegal()
          }
        }
        is(7.U(3.W)) { // AND
          when(io.funct7 === 0.U(7.W)) {
            io.ALUCtrl := AND
          }.otherwise {
            SetIllegal()
          }
        }
      }
    }
    is(OPCODE_UpperImmediate_lui) {
      io.ALUCtrl := ADD
    }
  }
}
