package ysyx_26030103.mem

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** LSU 非对齐/跨页分段地址 helper 的纯组合回归。 */
class ysyx_26030103_LSUSegmentTest
    extends AnyFlatSpec with Matchers with ChiselSim {

  private class SegmentHarness extends Module {
    val io = IO(new Bundle {
      val baseVa = Input(UInt(32.W))
      val index = Input(UInt(2.W))
      val basePa = Input(UInt(32.W))
      val page1Pa = Input(UInt(32.W))
      val page1Valid = Input(Bool())
      val crossPage = Input(Bool())
      val segmentVa = Output(UInt(32.W))
      val usesPage1 = Output(Bool())
      val page1Paddr = Output(UInt(32.W))
      val segmentPaddr = Output(UInt(32.W))
    })

    io.segmentVa := ysyx_26030103_LSUSegment.SegmentAddress(
      io.baseVa, io.index, 3, 32)
    io.usesPage1 := ysyx_26030103_LSUSegment.SegmentUsesPage1(
      io.segmentVa, io.baseVa, 32)
    io.page1Paddr := ysyx_26030103_LSUSegment.Page1Paddr(
      io.basePa, io.page1Pa, io.page1Valid, io.segmentVa, 32)
    io.segmentPaddr := ysyx_26030103_LSUSegment.SegmentPaddr(
      io.basePa, io.page1Pa, io.page1Valid, io.segmentVa, io.baseVa,
      io.crossPage, 32)
  }

  "LSUSegment" should "按粒度块递增段地址并识别第二页" in {
    simulate(new SegmentHarness) { dut =>
      dut.io.baseVa.poke("h80000ffc".U)
      dut.io.index.poke(1.U)
      dut.io.basePa.poke("h80000ffc".U)
      dut.io.page1Pa.poke("h80123456".U)
      dut.io.page1Valid.poke(false.B)
      dut.io.crossPage.poke(true.B)

      dut.io.segmentVa.peek().litValue shouldBe BigInt("80001000", 16)
      dut.io.usesPage1.peek().litValue.toInt shouldBe 1
      // 无第二页翻译时按连续物理页外推。
      dut.io.page1Paddr.peek().litValue shouldBe BigInt("80001000", 16)
      dut.io.segmentPaddr.peek().litValue shouldBe BigInt("80001000", 16)
    }
  }

  it should "有第二页翻译时使用翻译后的物理页" in {
    simulate(new SegmentHarness) { dut =>
      dut.io.baseVa.poke("h80000ffc".U)
      dut.io.index.poke(1.U)
      dut.io.basePa.poke("h80000ffc".U)
      dut.io.page1Pa.poke("h80123456".U)
      dut.io.page1Valid.poke(true.B)
      dut.io.crossPage.poke(true.B)

      dut.io.page1Paddr.peek().litValue shouldBe BigInt("80123000", 16)
      dut.io.segmentPaddr.peek().litValue shouldBe BigInt("80123000", 16)
    }
  }

  it should "不跨页时保持第一页物理偏移" in {
    simulate(new SegmentHarness) { dut =>
      dut.io.baseVa.poke("h80000003".U)
      dut.io.index.poke(0.U)
      dut.io.basePa.poke("h81234567".U)
      dut.io.page1Pa.poke("h00000000".U)
      dut.io.page1Valid.poke(false.B)
      dut.io.crossPage.poke(false.B)

      dut.io.segmentVa.peek().litValue shouldBe BigInt("80000000", 16)
      dut.io.usesPage1.peek().litValue.toInt shouldBe 0
      dut.io.segmentPaddr.peek().litValue shouldBe BigInt("81234000", 16)
    }
  }

  it should "跨页标志为假时即使段地址落到下一页也不选页 1" in {
    simulate(new SegmentHarness) { dut =>
      dut.io.baseVa.poke("h80000ffc".U)
      dut.io.index.poke(1.U)
      dut.io.basePa.poke("h80000ffc".U)
      dut.io.page1Pa.poke("h80123456".U)
      dut.io.page1Valid.poke(true.B)
      dut.io.crossPage.poke(false.B)

      dut.io.segmentPaddr.peek().litValue shouldBe BigInt("80000000", 16)
    }
  }
}
