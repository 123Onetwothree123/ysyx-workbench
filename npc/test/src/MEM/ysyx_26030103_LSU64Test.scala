package ysyx_26030103.mem

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_EXUMessage

/** RV64 LD/SD 在 32 位 AXI 上的两拍窄传输定向测试。 */
class ysyx_26030103_LSU64Test extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long = value.peek().litValue.toLong

  private def resetDut(dut: ysyx_26030103_LSU, clock: Clock,
      reset: Reset): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage(64)))
    dut.io.out.ready.poke(true.B)
    dut.io.DCacheFlush.poke(false.B)
    dut.io.ViptPAddr.poke(0.U(32.W))
    dut.io.ViptPAddrValid.poke(false.B)
    dut.io.ViptPAddrVaddr.poke(0.U(32.W))
    dut.io.ViptKill.poke(false.B)
    dut.io.PmpFault.poke(false.B)
    dut.io.DataBus.AW.AWREADY.poke(false.B)
    dut.io.DataBus.W.WREADY.poke(false.B)
    dut.io.DataBus.B.BVALID.poke(false.B)
    dut.io.DataBus.B.BID.poke(0.U(4.W))
    dut.io.DataBus.B.BRESP.poke(0.U(2.W))
    dut.io.DataBus.AR.ARREADY.poke(false.B)
    dut.io.DataBus.R.RVALID.poke(false.B)
    dut.io.DataBus.R.RID.poke(0.U(4.W))
    dut.io.DataBus.R.RDATA.poke(0.U(32.W))
    dut.io.DataBus.R.RRESP.poke(0.U(2.W))
    dut.io.DataBus.R.RLAST.poke(false.B)
  }

  private def pokeMem(dut: ysyx_26030103_LSU, address: Long, write: Boolean,
      pc: Long): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage(64)))
    dut.io.in.bits.Retire.poke(true.B)
    dut.io.in.bits.RegisterWrite.poke((if (write) false.B else true.B))
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke((if (write) true.B else false.B))
    dut.io.in.bits.ALUResult.poke(address.U(64.W))
    dut.io.in.bits.VAddr.poke(address.U(64.W))
    dut.io.in.bits.WidthSelect.poke(3.U(2.W))
    dut.io.in.bits.LoadSigned.poke(true.B)
    dut.io.in.bits.pc.poke(pc.U(64.W))
  }

  "RV64 LSU" should "用两拍 AXI 读完成 LD 并组合为 64 位结果" in {
    simulate(new ysyx_26030103_LSU(XLEN = 64)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      pokeMem(dut, 0x80004000L, write = false, 0x80000000L)
      dut.io.DataBus.AR.ARREADY.poke(true.B)
      dut.io.in.valid.poke(true.B)

      var cycles = 0
      while (!bool(dut.io.DataBus.AR.ARVALID) && cycles < 12) {
        clock.step(); cycles += 1
      }
      bool(dut.io.DataBus.AR.ARVALID) should be(true)
      long(dut.io.DataBus.AR.ARADDR) should be(0x80004000L)
      long(dut.io.DataBus.AR.ARLEN) should be(1L)
      clock.step() // AR 握手，进入读响应状态

      dut.io.DataBus.R.RVALID.poke(true.B)
      dut.io.DataBus.R.RRESP.poke(0.U)
      dut.io.DataBus.R.RDATA.poke("h89abcdef".U(32.W))
      dut.io.DataBus.R.RLAST.poke(false.B)
      clock.step()
      dut.io.DataBus.R.RDATA.poke("h01234567".U(32.W))
      dut.io.DataBus.R.RLAST.poke(true.B)
      clock.step()
      dut.io.DataBus.R.RVALID.poke(false.B)
      dut.io.DataBus.R.RLAST.poke(false.B)

      var done = false
      var load = BigInt(0)
      cycles = 0
      while (!done && cycles < 12) {
        if (bool(dut.io.out.valid)) {
          done = true
          load = dut.io.out.bits.LoadData.peek().litValue
        }
        clock.step(); cycles += 1
      }
      done should be(true)
      load should be(BigInt("0123456789abcdef", 16))
    }
  }

  it should "用两拍 AXI 写完成 SD 并保持低字/高字顺序" in {
    simulate(new ysyx_26030103_LSU(XLEN = 64)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      pokeMem(dut, 0x80005000L, write = true, 0x80000010L)
      dut.io.in.bits.StoreData.poke(BigInt("0123456789abcdef", 16).U(64.W))
      dut.io.DataBus.AW.AWREADY.poke(true.B)
      dut.io.DataBus.W.WREADY.poke(true.B)
      dut.io.in.valid.poke(true.B)

      val beats = scala.collection.mutable.ArrayBuffer[BigInt]()
      var sawAw = false
      var cycles = 0
      while (beats.size < 2 && cycles < 20) {
        if (bool(dut.io.DataBus.AW.AWVALID)) {
          sawAw = true
          long(dut.io.DataBus.AW.AWLEN) should be(1L)
          long(dut.io.DataBus.AW.AWADDR) should be(0x80005000L)
        }
        if (bool(dut.io.DataBus.W.WVALID)) {
          beats += dut.io.DataBus.W.WDATA.peek().litValue
          if (beats.size == 1) bool(dut.io.DataBus.W.WLAST) should be(false)
          if (beats.size == 2) bool(dut.io.DataBus.W.WLAST) should be(true)
        }
        clock.step(); cycles += 1
      }
      sawAw should be(true)
      beats.size should be(2)
      beats(0) should be(BigInt("89abcdef", 16))
      beats(1) should be(BigInt("01234567", 16))

      // 两拍都完成后给 B 响应，store 才能退休。
      dut.io.DataBus.B.BVALID.poke(true.B)
      dut.io.DataBus.B.BID.poke(0.U)
      dut.io.DataBus.B.BRESP.poke(0.U)
      var retired = false
      cycles = 0
      while (!retired && cycles < 12) {
        if (bool(dut.io.out.valid)) {
          retired = true
          bool(dut.io.out.bits.Retire) should be(true)
        }
        clock.step(); cycles += 1
      }
      retired should be(true)
    }
  }
}
