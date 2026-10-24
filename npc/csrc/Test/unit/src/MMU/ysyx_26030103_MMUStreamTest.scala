package ysyx_26030103.mmu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 取指 MMU 包装层的重定向/旧 paddr 防护测试。 */
class ysyx_26030103_MMUStreamTest extends AnyFlatSpec with Matchers {
  private def bool(v: Bool): Boolean = v.peek().litValue == 1
  private def long(v: UInt): Long = v.peek().litValue.toLong

  "IFUMMU" should "重定向后不再把旧物理地址送给 ICache" in {
    simulate(new ysyx_26030103_IFUMMU) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.fetchValid.poke(false.B)
      dut.io.fetchVaddr.poke(0.U)
      dut.io.cacheReady.poke(false.B)
      dut.io.privilege.poke(1.U)
      dut.io.satp.poke(0x80000000L.U(32.W))
      dut.io.sum.poke(false.B)
      dut.io.mxr.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.req.ready.poke(true.B)
      dut.io.resp.valid.poke(false.B)
      dut.io.resp.bits.paddr.poke(0.U)
      dut.io.resp.bits.fault.poke(false.B)
      dut.io.resp.bits.cause.poke(0.U)
      dut.io.resp.bits.tval.poke(0.U)

      dut.io.fetchValid.poke(true.B)
      dut.io.fetchVaddr.poke(0x80000100L.U)
      dut.clock.step()
      // 请求已经被 MMU 接收，回一个物理地址。
      dut.io.resp.valid.poke(true.B)
      dut.io.resp.bits.paddr.poke(0x80000100L.U)
      dut.clock.step()
      dut.io.resp.valid.poke(false.B)
      dut.clock.step()
      bool(dut.io.translationValid) should be(true)
      long(dut.io.fetchPaddr) should be(0x80000100L)

      // 模拟 EXU 重定向：虚拟 PC 改变但 flush 还没到。
      dut.io.fetchVaddr.poke(0x80000200L.U)
      bool(dut.io.translationValid) should be(false)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
    }
  }
}
