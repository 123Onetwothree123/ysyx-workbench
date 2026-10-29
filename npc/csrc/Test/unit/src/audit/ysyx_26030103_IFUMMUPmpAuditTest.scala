package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.mmu._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** IFUMMU 取指 PMP 审计。
  *
  * 问题一：Bare/M 模式下 IFUMMU 完全绕过 PMP，idle 只处理需要翻译的
  * 请求，`translationValid` 也无条件直通。PMP 拒绝的取指会直接进入
  * ICache，且没有任何 fault 事件。
  *
  * 问题二：PIPT 模式下 done 状态拉高 `translationValid` 时忽略
  * `pmpFault`，故障取指仍会在该拍被 ICache 受理。
  */
class ysyx_26030103_IFUMMUPmpAuditTest
    extends AnyFlatSpec with Matchers {

  private def bool(v: Bool): Boolean = v.peek().litValue == 1
  private def long(v: UInt): Long = v.peek().litValue.toLong

  private def reset(dut: ysyx_26030103_IFUMMU): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.fetchValid.poke(false.B)
    dut.io.fetchVaddr.poke(0.U)
    dut.io.cacheReady.poke(false.B)
    dut.io.pmpFault.poke(false.B)
    dut.io.privilege.poke(1.U)
    dut.io.satp.poke(0x80000000L.U)
    dut.io.sum.poke(false.B)
    dut.io.mxr.poke(false.B)
    dut.io.flush.poke(false.B)
    dut.io.req.ready.poke(true.B)
    dut.io.resp.valid.poke(false.B)
    dut.io.resp.bits.id.poke(0.U)
    dut.io.resp.bits.paddr.poke(0.U)
    dut.io.resp.bits.fault.poke(false.B)
    dut.io.resp.bits.cause.poke(0.U)
    dut.io.resp.bits.tval.poke(0.U)
  }

  "IFUMMU" should "在 Bare/M 模式对 PMP 拒绝的取指产生 cause=1 故障" in {
    simulate(new ysyx_26030103_IFUMMU) { dut =>
      reset(dut)
      val pc = 0x80000100L
      dut.io.fetchValid.poke(true.B)
      dut.io.fetchVaddr.poke(pc.U)
      dut.io.cacheReady.poke(true.B)
      dut.io.privilege.poke(3.U) // M 模式，翻译 bypass
      dut.io.satp.poke(0.U)
      dut.io.pmpFault.poke(true.B)

      // PMP 拒绝时不得把该取指当成有效翻译交给 ICache。
      withClue("Bare/M 模式 pmpFault 时 translationValid 必须为 0: ") {
        bool(dut.io.translationValid) should be(false)
      }
      withClue("Bare/M 模式 pmpFault 时 fetchReady 必须为 0: ") {
        bool(dut.io.fetchReady) should be(false)
      }

      dut.clock.step()
      withClue("Bare/M 模式 PMP 故障必须产生 IFU fault: ") {
        bool(dut.io.fault) should be(true)
      }
      withClue("取指 PMP 故障 cause 必须是 1: ") {
        long(dut.io.faultCause) should be(1L)
      }
      withClue("faultVaddr 必须是故障取指的虚拟地址: ") {
        long(dut.io.faultVaddr) should be(pc)
      }

      // faultReg 是单拍事件；保持 fault 状态直到 flush。
      dut.clock.step()
      bool(dut.io.fault) should be(false)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
    }
  }

  it should "在 PIPT done 状态 pmpFault 时立即撤销 translationValid" in {
    simulate(new ysyx_26030103_IFUMMU) { dut =>
      reset(dut)
      val pc = 0x80000100L
      dut.io.fetchValid.poke(true.B)
      dut.io.fetchVaddr.poke(pc.U)
      dut.io.cacheReady.poke(false.B)
      dut.io.privilege.poke(1.U) // S 模式 + Sv32：需要真实翻译
      dut.io.satp.poke(0x80000000L.U)

      // idle 发出翻译请求。
      bool(dut.io.req.valid) should be(true)
      dut.clock.step()
      bool(dut.io.req.valid) should be(false)

      // 返回成功翻译，进入 done。
      dut.io.resp.valid.poke(true.B)
      dut.io.resp.bits.paddr.poke(pc.U)
      dut.io.resp.bits.tval.poke(pc.U)
      dut.clock.step()
      dut.io.resp.valid.poke(false.B)

      withClue("done 状态无 PMP 故障时应给出有效翻译: ") {
        bool(dut.io.translationValid) should be(true)
      }

      // PIPT done 状态下 PMP 拒绝：同拍就必须撤销 valid，不能先让
      // ICache 受理一次故障取指。
      dut.io.pmpFault.poke(true.B)
      dut.io.cacheReady.poke(true.B)
      withClue("PIPT done 状态 pmpFault 时 translationValid 必须为 0: ") {
        bool(dut.io.translationValid) should be(false)
      }
      withClue("PIPT done 状态 pmpFault 时 fetchReady 必须为 0: ") {
        bool(dut.io.fetchReady) should be(false)
      }

      dut.clock.step()
      bool(dut.io.fault) should be(true)
      long(dut.io.faultCause) should be(1L)
      long(dut.io.faultVaddr) should be(pc)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
    }
  }
}
