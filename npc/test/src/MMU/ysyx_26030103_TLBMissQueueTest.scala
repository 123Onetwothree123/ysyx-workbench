package ysyx_26030103.mmu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** TLB miss queue 的深度、FIFO 顺序与 flush 行为测试。 */
class ysyx_26030103_TLBMissQueueTest extends AnyFlatSpec with Matchers {
  private def asBool(value: Bool): Boolean = value.peek().litValue == 1
  private def asLong(value: UInt): Long = value.peek().litValue.toLong

  "TLBMissQueue" should "按配置深度缓存并按 FIFO 顺序出队" in {
    simulate(new ysyx_26030103_TLBMissQueue(entries = 2, width = 4)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.enq.valid.poke(false.B)
      dut.io.enq.bits.poke(0.U)
      dut.io.deq.ready.poke(false.B)

      // 写满两项；第三项必须被 ready=0 挡住。
      dut.io.enq.valid.poke(true.B)
      dut.io.enq.bits.poke(3.U)
      dut.clock.step()
      dut.io.enq.bits.poke(5.U)
      dut.clock.step()
      asLong(dut.io.occupancy) should be(2L)
      asBool(dut.io.enq.ready) should be(false)
      dut.io.enq.valid.poke(false.B)

      // 队首是 3，读出后才轮到 5。
      asBool(dut.io.deq.valid) should be(true)
      asLong(dut.io.deq.bits) should be(3L)
      dut.io.deq.ready.poke(true.B)
      dut.clock.step()
      asLong(dut.io.deq.bits) should be(5L)
      asLong(dut.io.occupancy) should be(1L)
      dut.clock.step()
      asBool(dut.io.deq.valid) should be(false)
      asLong(dut.io.occupancy) should be(0L)
    }
  }

  it should "flush 当拍清空且优先于同拍入队" in {
    simulate(new ysyx_26030103_TLBMissQueue(entries = 4, width = 4)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.deq.ready.poke(false.B)

      dut.io.enq.valid.poke(true.B)
      dut.io.enq.bits.poke(1.U)
      dut.clock.step()
      dut.io.enq.bits.poke(2.U)
      dut.clock.step()
      asLong(dut.io.occupancy) should be(2L)

      // flush 与 enq 同拍：flush 优先，队列保持空。
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.enq.valid.poke(false.B)
      asLong(dut.io.occupancy) should be(0L)
      asBool(dut.io.deq.valid) should be(false)
    }
  }
}
