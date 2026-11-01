package ysyx_26030103.idu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import chisel3.util.BitPat
import chisel3.util.experimental.decode._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_ALUFunction._
import _root_.ysyx_26030103.common.ysyx_26030103_MDUOp
import _root_.ysyx_26030103.common.ysyx_26030103_opcode._
import _root_.ysyx_26030103.config._

class ysyx_26030103_IDUMinimizerTest extends AnyFlatSpec with Matchers {
  private val mixedDefault = TruthTable(
    Seq(
      BitPat("b00") -> BitPat("b10"),
      BitPat("b01") -> BitPat("b01")
    ),
    BitPat("b10")
  )

  private val oneDefault = TruthTable(
    Seq(
      BitPat("b00") -> BitPat("b0")
    ),
    BitPat("b1")
  )

  private def matchRow(t: TruthTable, input: Int): Option[BitPat] =
    t.table.collectFirst { case (in, out)
        if (BigInt(input) & in.mask) == (in.value & in.mask) => out
    }

  private def evalOriginal(t: TruthTable, input: Int): Int = {
    val default = t.default.value & t.default.mask
    matchRow(t, input) match {
      case Some(out) => ((default & ~out.mask) | (out.value & out.mask)).toInt
      case None      => default.toInt
    }
  }

  private def evalMinimized(t: TruthTable, input: Int): Int = {
    var value = 0
    for (bit <- 0 until t.outputWidth) {
      val onSet = t.table.exists { case (in, out) =>
        ((BigInt(input) & in.mask) == (in.value & in.mask)) &&
          out.mask.testBit(bit) && out.value.testBit(bit)
      }
      val defaultOne =
        t.default.mask.testBit(bit) && t.default.value.testBit(bit)
      val actual = if (defaultOne) !onSet else onSet
      if (actual) value |= 1 << bit
    }
    value
  }

  private def check(table: TruthTable, minimizer: Minimizer): Unit = {
    val minimized = minimizer.minimize(table)
    for (input <- 0 until (1 << table.inputWidth)) {
      withClue(s"${minimizer.getClass.getSimpleName} input=$input: ") {
        evalMinimized(minimized, input) should be(evalOriginal(table, input))
      }
    }
  }

  private def hasAbc: Boolean =
    try {
      new ProcessBuilder("abc", "-c", "quit").start().waitFor() == 0
    } catch {
      case _: Exception => false
    }

  private def checkAll(table: TruthTable): Unit = {
    check(table, ysyx_26030103_IdentityMinimizer)
    check(table, QMCMinimizer)
    if (hasAbc) {
      check(table, ysyx_26030103_AbcBddMinimizer)
      check(table, ysyx_26030103_AbcSatMinimizer)
    }
  }

  "IDU minimizers" should "保持混合默认值的真值表语义" in {
    checkAll(mixedDefault)
  }

  it should "保持默认 1 的真值表语义" in {
    checkAll(oneDefault)
  }

  "ALUDecoder" should "支持 QMC/None/BDD/SAT 四种后端" in {
    val backends = Seq(
      ysyx_26030103_IDUDecodeMinimizer.QMC,
      ysyx_26030103_IDUDecodeMinimizer.None,
      ysyx_26030103_IDUDecodeMinimizer.Bdd,
      ysyx_26030103_IDUDecodeMinimizer.Sat
    )
    for (backend <- backends) {
      withClue(s"backend=${backend.Name}: ") {
        val cfg = ysyx_26030103_NPCConfig(
          UseM = true,
          IDUDecodeMinimizer = backend
        )
        simulate(new ysyx_26030103_ALUDecoder(cfg)) { dut =>
          dut.io.opcode.poke(OPCODE_Register)
          dut.io.funct3.poke(0.U)
          dut.io.funct7.poke(0.U)
          dut.io.ALUCtrl.peek().litValue should be(ADD.litValue)
          dut.io.Illegal.peek().litValue should be(0)

          dut.io.funct7.poke(1.U)
          dut.io.IsMDU.peek().litValue should be(1)
          dut.io.MDUOp.peek().litValue should be(
            ysyx_26030103_MDUOp.MUL.litValue)
          dut.io.ALUCtrl.peek().litValue should be(NOP.litValue)

          dut.io.funct7.poke("b1111111".U)
          dut.io.Illegal.peek().litValue should be(1)
        }
      }
    }
  }
}
