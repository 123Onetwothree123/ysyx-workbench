package ysyx_26030103.mem

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** LSU AXI 拍 helper 的纯组合回归。
  *
  * 只验证 helper 的 LEN/SIZE/LAST/WDATA/WSTRB 规则，不替代整核仿真；
  * 单拍路径必须恒为 LAST，两拍路径只在 beat=1 拉高 LAST。
  */
class ysyx_26030103_LSUHelpersTest
    extends AnyFlatSpec with Matchers with ChiselSim {

  private class BeatHarness(busWide: Boolean) extends Module {
    val io = IO(new Bundle {
      val kind = Input(UInt(2.W)) // 0=read, 1=atomic, 2=store
      val wsel = Input(UInt(2.W))
      val wide = Input(Bool())
      val misaligned = Input(Bool())
      val segmentWide = Input(Bool())
      val beat = Input(UInt(1.W))
      val address = Input(UInt(32.W))
      val low = Input(UInt(32.W))
      val high = Input(UInt(32.W))
      val lowStrobe = Input(UInt(4.W))
      val len = Output(UInt(8.W))
      val size = Output(UInt(3.W))
      val multi = Output(Bool())
      val last = Output(Bool())
      val data = Output(UInt(64.W))
      val strobe = Output(UInt(8.W))
    })

    private val readPlan =
      ysyx_26030103_LSUAXIBeat.Read(io.wsel, io.wide, busWide)
    private val atomicPlan =
      ysyx_26030103_LSUAXIBeat.Atomic(io.wide, busWide)
    private val storePlan = ysyx_26030103_LSUAXIBeat.Store(
      ysyx_26030103_LSUAXIBeat.WidthSize(io.wsel),
      io.wide,
      busWide,
      io.misaligned,
      io.segmentWide
    )

    io.len := MuxLookup(io.kind, readPlan.len)(
      Seq(0.U -> readPlan.len, 1.U -> atomicPlan.len, 2.U -> storePlan.len))
    io.size := MuxLookup(io.kind, readPlan.size)(
      Seq(0.U -> readPlan.size, 1.U -> atomicPlan.size, 2.U -> storePlan.size))
    io.multi := MuxLookup(io.kind, readPlan.multiBeat)(
      Seq(0.U -> readPlan.multiBeat, 1.U -> atomicPlan.multiBeat,
        2.U -> storePlan.multiBeat))
    io.last := MuxLookup(io.kind, readPlan.lastBeat(io.beat))(
      Seq(0.U -> readPlan.lastBeat(io.beat),
        1.U -> atomicPlan.lastBeat(io.beat),
        2.U -> storePlan.lastBeat(io.beat)))
    io.data := ysyx_26030103_LSUAXIBeat.WriteData(
      io.low, io.high, io.address, io.wide, busWide, io.beat)
    io.strobe := ysyx_26030103_LSUAXIBeat.WriteStrobe(
      io.lowStrobe, io.address, io.wide, busWide, io.beat)
  }

  private def idle(dut: BeatHarness): Unit = {
    dut.io.wsel.poke(2.U)
    dut.io.wide.poke(false.B)
    dut.io.misaligned.poke(false.B)
    dut.io.segmentWide.poke(false.B)
    dut.io.beat.poke(0.U)
    dut.io.address.poke(0x80000000L.U)
    dut.io.low.poke(0x11223344L.U)
    dut.io.high.poke(0x55667788L.U)
    dut.io.lowStrobe.poke("hf".U)
  }

  "LSUAXIBeat" should "把 32 位总线上的 8 字节读拆成两拍" in {
    simulate(new BeatHarness(busWide = false)) { dut =>
      idle(dut)
      dut.io.kind.poke(0.U)
      dut.io.wsel.poke(3.U)
      dut.io.wide.poke(true.B)
      dut.io.len.peek().litValue.toInt shouldBe 1
      dut.io.size.peek().litValue.toInt shouldBe 2
      dut.io.multi.peek().litValue.toInt shouldBe 1
      dut.io.last.peek().litValue.toInt shouldBe 0
      dut.io.beat.poke(1.U)
      dut.io.last.peek().litValue.toInt shouldBe 1
    }
  }

  it should "让单拍访问的 LAST 恒为真，即使拍计数器有残留" in {
    simulate(new BeatHarness(busWide = false)) { dut =>
      idle(dut)
      dut.io.kind.poke(2.U) // store
      dut.io.wsel.poke(3.U)
      dut.io.wide.poke(true.B)
      dut.io.misaligned.poke(true.B) // 分段写：每段单拍
      dut.io.beat.poke(1.U)
      dut.io.len.peek().litValue.toInt shouldBe 0
      dut.io.size.peek().litValue.toInt shouldBe 2
      dut.io.multi.peek().litValue.toInt shouldBe 0
      dut.io.last.peek().litValue.toInt shouldBe 1
    }
  }

  it should "保持原子 .W 单拍、.D 在 64 位总线上单拍" in {
    simulate(new BeatHarness(busWide = true)) { dut =>
      idle(dut)
      dut.io.kind.poke(1.U)
      dut.io.wide.poke(true.B)
      dut.io.len.peek().litValue.toInt shouldBe 0
      dut.io.size.peek().litValue.toInt shouldBe 3
      dut.io.multi.peek().litValue.toInt shouldBe 0
      dut.io.last.peek().litValue.toInt shouldBe 1
    }
  }

  it should "按 beat 放置 32/64 位总线的 WDAT/WSTRB" in {
    simulate(new BeatHarness(busWide = false)) { dut =>
      idle(dut)
      dut.io.wide.poke(true.B)
      dut.io.beat.poke(0.U)
      dut.io.data.peek().litValue shouldBe BigInt("11223344", 16)
      dut.io.strobe.peek().litValue shouldBe BigInt("f", 16)
      dut.io.beat.poke(1.U)
      dut.io.data.peek().litValue shouldBe BigInt("55667788", 16)
      dut.io.strobe.peek().litValue shouldBe BigInt("f", 16)
    }
    simulate(new BeatHarness(busWide = true)) { dut =>
      idle(dut)
      dut.io.wide.poke(true.B)
      dut.io.beat.poke(0.U)
      dut.io.data.peek().litValue shouldBe BigInt("5566778811223344", 16)
      dut.io.strobe.peek().litValue shouldBe BigInt("ff", 16)
    }
  }

  it should "64 位总线上的 32 位访问按 address[2] 选 lane" in {
    simulate(new BeatHarness(busWide = true)) { dut =>
      idle(dut)
      dut.io.kind.poke(2.U)
      dut.io.wide.poke(false.B)
      dut.io.wsel.poke(2.U)
      dut.io.address.poke(0x80000004L.U)
      dut.io.data.peek().litValue shouldBe BigInt("1122334400000000", 16)
      dut.io.strobe.peek().litValue shouldBe BigInt("f0", 16)
    }
  }
}
