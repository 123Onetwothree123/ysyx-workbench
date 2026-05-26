package RV32I
import chisel3._
import chisel3.util._
import RV32I.opcode._
class ALUControlDecoder extends Module {
  val io = IO(new Bundle {
    val ALUOp = Input(UInt(2.W))
    val opcode = Input(UInt(7.W))
    val funct3 = Input(UInt(3.W))
    val funct7 = Input(UInt(7.W))
    val ALUCtrl = Output(UInt(4.W))
    val Illegal = Output(Bool()) // 当前未实现或不支持的指令编码标记
  })
  val ALUOP_ADDR = 0.U(2.W)
  val ALUOP_BRANCH = 1.U(2.W)
  val ALUOP_ARITH = 2.U(2.W)
  val ALUOP_MISC = 3.U(2.W)
  val ALUCTRL_ADD = 0.U(4.W)
  val ALUCTRL_SUB = 1.U(4.W)
  val ALUCTRL_SLL = 2.U(4.W)
  val ALUCTRL_SLT = 3.U(4.W)
  val ALUCTRL_SLTU = 4.U(4.W)
  val ALUCTRL_XOR = 5.U(4.W)
  val ALUCTRL_SRL = 6.U(4.W)
  val ALUCTRL_SRA = 7.U(4.W)
  val ALUCTRL_OR = 8.U(4.W)
  val ALUCTRL_AND = 9.U(4.W)
  val ALUCTRL_NOP = 15.U(4.W)
  val is_immediate = (io.opcode === OPCODE_Immediate)
  val is_register = (io.opcode === OPCODE_Register)
  io.ALUCtrl := ALUCTRL_NOP
  io.Illegal := false.B
  switch(io.ALUOp) {
    is(ALUOP_ADDR) {
      switch(io.opcode) {
        is(OPCODE_Immediate_Lxxx) {
          io.ALUCtrl := ALUCTRL_ADD
          when(
            !((io.funct3 === "b000".U(3.W)) || (io.funct3 === "b001"
              .U(3.W)) || (io.funct3 === "b010".U(3.W)) || (io.funct3 === "b100"
              .U(3.W)) || (io.funct3 === "b101".U(3.W)))
          ) {
            io.ALUCtrl := ALUCTRL_NOP
            io.Illegal := true.B
          }
        }
        is(OPCODE_Store) {
          io.ALUCtrl := ALUCTRL_ADD
          when(
            !((io.funct3 === "b000".U(3.W)) || (io.funct3 === "b001"
              .U(3.W)) || (io.funct3 === "b010".U(3.W)))
          ) {
            io.ALUCtrl := ALUCTRL_NOP
            io.Illegal := true.B
          }
        }
        is(OPCODE_Immediate_Bxxx) {
          io.ALUCtrl := ALUCTRL_ADD
          when(!(io.funct3 === "b000".U(3.W))) {
            io.ALUCtrl := ALUCTRL_NOP
            io.Illegal := true.B
          }
        }
        is(OPCODE_UpperImmediate_auipc, OPCODE_Jump) {
          io.ALUCtrl := ALUCTRL_ADD
        }
      }
    }
    is(ALUOP_ARITH) {
      when(is_immediate) {
        switch(io.funct3) {
          is("b000".U(3.W)) {
            io.ALUCtrl := ALUCTRL_ADD
          }
          is("b001".U(3.W)) {
            when(io.funct7 === "b0000000".U(7.W)) {
              io.ALUCtrl := ALUCTRL_SLL
            }.otherwise {
              io.ALUCtrl := ALUCTRL_NOP
              io.Illegal := true.B
            }
          }
          is("b010".U(3.W)) {
            io.ALUCtrl := ALUCTRL_SLT
          }
          is("b011".U(3.W)) {
            io.ALUCtrl := ALUCTRL_SLTU
          }
          is("b100".U(3.W)) {
            io.ALUCtrl := ALUCTRL_XOR
          }
          is("b101".U(3.W)) {
            when(io.funct7 === "b0000000".U(7.W)) {
              io.ALUCtrl := ALUCTRL_SRL
            }.elsewhen(io.funct7 === "b0100000".U(7.W)) {
              io.ALUCtrl := ALUCTRL_SRA
            }.otherwise {
              io.ALUCtrl := ALUCTRL_NOP
              io.Illegal := true.B
            }
          }
          is("b110".U(3.W)) {
            io.ALUCtrl := ALUCTRL_OR
          }
          is("b111".U(3.W)) {
            io.ALUCtrl := ALUCTRL_AND
          }
        }
      }.elsewhen(is_register) {
        switch(io.funct3) {
          is("b000".U(3.W)) {
            when(io.funct7 === "b0000000".U(7.W)) {
              io.ALUCtrl := ALUCTRL_ADD
            }.elsewhen(io.funct7 === "b0100000".U(7.W)) {
              io.ALUCtrl := ALUCTRL_SUB
            }.otherwise {
              io.ALUCtrl := ALUCTRL_NOP
              io.Illegal := true.B
            }
          }
          is("b001".U(3.W)) {
            when(io.funct7 === "b0000000".U(7.W)) {
              io.ALUCtrl := ALUCTRL_SLL
            }.otherwise {
              io.ALUCtrl := ALUCTRL_NOP
              io.Illegal := true.B
            }
          }
          is("b010".U(3.W)) {
            when(io.funct7 === "b0000000".U(7.W)) {
              io.ALUCtrl := ALUCTRL_SLT
            }.otherwise {
              io.ALUCtrl := ALUCTRL_NOP
              io.Illegal := true.B
            }
          }
          is("b011".U(3.W)) {
            when(io.funct7 === "b0000000".U(7.W)) {
              io.ALUCtrl := ALUCTRL_SLTU
            }.otherwise {
              io.ALUCtrl := ALUCTRL_NOP
              io.Illegal := true.B
            }
          }
          is("b100".U(3.W)) {
            when(io.funct7 === "b0000000".U(7.W)) {
              io.ALUCtrl := ALUCTRL_XOR
            }.otherwise {
              io.ALUCtrl := ALUCTRL_NOP
              io.Illegal := true.B
            }
          }
          is("b101".U(3.W)) {
            when(io.funct7 === "b0000000".U(7.W)) {
              io.ALUCtrl := ALUCTRL_SRL
            }.elsewhen(io.funct7 === "b0100000".U(7.W)) {
              io.ALUCtrl := ALUCTRL_SRA
            }.otherwise {
              io.ALUCtrl := ALUCTRL_NOP
              io.Illegal := true.B
            }
          }
          is("b110".U(3.W)) {
            when(io.funct7 === "b0000000".U(7.W)) {
              io.ALUCtrl := ALUCTRL_OR
            }.otherwise {
              io.ALUCtrl := ALUCTRL_NOP
              io.Illegal := true.B
            }
          }
          is("b111".U(3.W)) {
            when(io.funct7 === "b0000000".U(7.W)) {
              io.ALUCtrl := ALUCTRL_AND
            }.otherwise {
              io.ALUCtrl := ALUCTRL_NOP
              io.Illegal := true.B
            }
          }
        }
      }.otherwise {
        io.ALUCtrl := ALUCTRL_NOP
        io.Illegal := true.B
      }
    }
    is(ALUOP_MISC) {
      switch(io.opcode) {
        is(OPCODE_UpperImmediate_lui) {
          io.ALUCtrl := ALUCTRL_ADD
        }
      }
    }
    is(ALUOP_BRANCH) {
      io.ALUCtrl := ALUCTRL_SUB
      when(
        !((io.funct3 === "b000".U(3.W)) || (io.funct3 === "b001".U(3.W)) ||
          (io.funct3 === "b100".U(3.W)) || (io.funct3 === "b101".U(3.W)) ||
          (io.funct3 === "b110".U(3.W)) || (io.funct3 === "b111".U(3.W)))
      ) {
        io.ALUCtrl := ALUCTRL_NOP
        io.Illegal := true.B
      }
    }
  }
}
