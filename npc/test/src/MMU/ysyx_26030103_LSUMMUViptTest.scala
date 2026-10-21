package ysyx_26030103.mmu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_EXUMessage

/** LSUMMU VIPT 并行路径测试。
  *
  * 重点验证：
  *   1. load 在 PA 之前就交给 LSU（out.bits 仍是 VA），MMU 同时翻译； 2. 翻译故障产生 memKill 单拍脉冲，并有且仅有一次精确 faultCommit； 3. 翻译成功时 PA 通过
  *      memPaddr 端口保持到 LSU 排空。
  */
class ysyx_26030103_LSUMMUViptTest extends AnyFlatSpec with Matchers {
  private val AddrW = 32
  private val Va    = 0x00001050L
  private val Pa    = 0x80002000L

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long    = value.peek().litValue.toLong

  private def waitUntil(clock: Clock, cond: => Boolean, message: String): Unit = {
    var cycles = 0
    while (!cond && cycles < 200) {
      clock.step()
      cycles += 1
    }
    withClue(s"$message（等待 $cycles 拍）") { cond should be(true) }
  }

  private def pokeLoad(
    dut: ysyx_26030103_LSUMMU,
    pc:  Long
  ): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(false.B)
    dut.io.in.bits.ALUResult.poke(Va.U(AddrW.W))
    dut.io.in.bits.VAddr.poke(Va.U(AddrW.W))
    dut.io.in.bits.WidthSelect.poke(2.U(2.W))
    dut.io.in.bits.pc.poke(pc.U(AddrW.W))
  }

  private def resetDut(
    dut:   ysyx_26030103_LSUMMU,
    clock: Clock,
    reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.out.ready.poke(true.B)
    dut.io.privilege.poke(1.U(2.W))
    dut.io.satp.poke("h80000000".U(32.W)) // MODE=1，强制走页表翻译
    dut.io.sum.poke(false.B)
    dut.io.mxr.poke(false.B)
    dut.io.flush.poke(false.B)
    dut.io.pmpFault.poke(false.B)
    dut.io.memActive.poke(false.B)
    dut.io.req.ready.poke(false.B)
    dut.io.resp.valid.poke(false.B)
    dut.io.resp.bits.poke(0.U.asTypeOf(new ysyx_26030103_MMUResponse))
  }

  private def startLoad(
    dut:   ysyx_26030103_LSUMMU,
    clock: Clock,
    pc:    Long
  ): Unit = {
    pokeLoad(dut, pc)
    dut.io.in.valid.poke(true.B)
    waitUntil(clock, bool(dut.io.in.ready), "LSUMMU 未受理 load")
    clock.step()
    dut.io.in.valid.poke(false.B)
  }

  private def forwardAndRequest(
    dut:   ysyx_26030103_LSUMMU,
    clock: Clock
  ): Unit = {
    // PA 尚未到达时，load 已经以 VA 出现在 LSU 输入上。
    waitUntil(clock, bool(dut.io.out.valid), "load 未在翻译前转发给 LSU")
    long(dut.io.out.bits.ALUResult) should be(Va)
    long(dut.io.out.bits.VAddr) should be(Va)
    bool(dut.io.memPaddrValid) should be(false)
    clock.step() // io.out.ready 恒为 true，LSU 接收
    waitUntil(clock, bool(dut.io.req.valid), "MMU 翻译请求未发出")
    long(dut.io.req.bits.vaddr) should be(Va)
    dut.io.req.ready.poke(true.B)
    clock.step()
    dut.io.req.ready.poke(false.B)
  }

  private def pokeResponse(
    dut:   ysyx_26030103_LSUMMU,
    fault: Boolean,
    paddr: Long
  ): Unit = {
    dut.io.resp.bits.poke(0.U.asTypeOf(new ysyx_26030103_MMUResponse))
    dut.io.resp.bits.id.poke(1.U)
    dut.io.resp.bits.paddr.poke(paddr.U(32.W))
    dut.io.resp.bits.fault.poke(fault.B)
    dut.io.resp.bits.cause.poke((if (fault) 13 else 0).U(32.W))
    dut.io.resp.bits.tval.poke(Va.U(32.W))
  }

  private def respond(
    dut:   ysyx_26030103_LSUMMU,
    clock: Clock,
    fault: Boolean,
    paddr: Long = 0L
  ): Unit = {
    pokeResponse(dut, fault, paddr)
    dut.io.resp.valid.poke(true.B)
    clock.step()
    dut.io.resp.valid.poke(false.B)
  }

  "LSUMMU VIPT parallel" should "翻译故障杀死已转发 load 并提交精确 page fault" in {
    simulate(new ysyx_26030103_LSUMMU(ParallelVipt = true)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      startLoad(dut, clock, pc = 0x1000L)
      forwardAndRequest(dut, clock)
      // 模拟 load 已被 LSU 接收：memActive=true。
      dut.io.memActive.poke(true.B)
      pokeResponse(dut, fault = true, paddr = 0L)
      dut.io.resp.valid.poke(true.B)
      // 故障响应被采样的同拍必须给出 memKill，撤销 LSU/DCache 等待请求。
      bool(dut.io.memKill) should be(true)
      clock.step()
      dut.io.resp.valid.poke(false.B)
      // 下一拍提交一次精确 page fault。
      bool(dut.io.faultCommit) should be(true)
      long(dut.io.faultCause) should be(13)
      long(dut.io.faultTval) should be(Va)
      long(dut.io.faultPC) should be(0x1000L)
      bool(dut.io.out.valid) should be(false)
      bool(dut.io.memPaddrValid) should be(false)
      clock.step()
      // faultCommit 保持到 EXU 冲刷（与 PIPT 行为一致），避免重复注入。
      bool(dut.io.faultCommit) should be(true)
      bool(dut.io.busy) should be(true)
      // EXU 冲刷后回到空闲。
      dut.io.flush.poke(true.B)
      clock.step()
      dut.io.flush.poke(false.B)
      clock.step()
      bool(dut.io.faultCommit) should be(false)
      bool(dut.io.busy) should be(false)
    }
  }

  it should "翻译成功时保持 PA 到 LSU 排空，且不复用旧 PA" in {
    simulate(new ysyx_26030103_LSUMMU(ParallelVipt = true)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      startLoad(dut, clock, pc = 0x2000L)
      forwardAndRequest(dut, clock)
      dut.io.memActive.poke(true.B)
      respond(dut, clock, fault = false, paddr = Pa)
      clock.step()
      bool(dut.io.memPaddrValid) should be(true)
      long(dut.io.memPaddr) should be(Pa)
      long(dut.io.memPaddrVaddr) should be(Va)
      bool(dut.io.faultCommit) should be(false)
      // LSU 仍持有该 load 时不能开始下一笔翻译（避免覆盖 PA）。
      bool(dut.io.in.ready) should be(false)
      // LSU 排空后才释放。
      dut.io.memActive.poke(false.B)
      clock.step()
      bool(dut.io.busy) should be(false)
      bool(dut.io.memPaddrValid) should be(false)
    }
  }
}
