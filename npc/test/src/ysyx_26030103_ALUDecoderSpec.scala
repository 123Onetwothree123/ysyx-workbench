package ysyx_26030103.test

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_NPCConfig
import _root_.ysyx_26030103.idu.ysyx_26030103_ALUDecoder

class ysyx_26030103_ALUDecoderSpec
    extends AnyFlatSpec
    with Matchers
    with ChiselSim {

  private val MExtOff = ysyx_26030103_NPCConfig(UseM = false)
  private val MExtOn = ysyx_26030103_NPCConfig(UseM = true)
  private val OpcodeReg = "b0110011".U(7.W)
  private val Funct7M = "b0000001".U(7.W)

  "ALUDecoder(UseM=true)" should "decode all 8 M ops as MDU" in {
    simulate(new ysyx_26030103_ALUDecoder(MExtOn)) { dut =>
      for (f3 <- 0 until 8) {
        dut.io.opcode.poke(OpcodeReg)
        dut.io.funct3.poke(f3.U(3.W))
        dut.io.funct7.poke(Funct7M)
        dut.clock.step()
        dut.io.IsMDU.expect(true.B)
        dut.io.MDUOp.expect(f3.U(3.W))
        dut.io.Illegal.expect(false.B)
      }
    }
  }

  it should "keep base register ops on the ALU path" in {
    simulate(new ysyx_26030103_ALUDecoder(MExtOn)) { dut =>
      dut.io.opcode.poke(OpcodeReg)
      dut.io.funct3.poke(0.U(3.W))
      dut.io.funct7.poke(0.U(7.W))
      dut.clock.step()
      dut.io.IsMDU.expect(false.B)
      dut.io.Illegal.expect(false.B)
    }
  }

  "ALUDecoder(UseM=false)" should "reject M encodings as illegal" in {
    simulate(new ysyx_26030103_ALUDecoder(MExtOff)) { dut =>
      dut.io.opcode.poke(OpcodeReg)
      dut.io.funct3.poke(0.U(3.W))
      dut.io.funct7.poke(Funct7M)
      dut.clock.step()
      dut.io.IsMDU.expect(false.B)
      dut.io.Illegal.expect(true.B)
    }
  }
}
