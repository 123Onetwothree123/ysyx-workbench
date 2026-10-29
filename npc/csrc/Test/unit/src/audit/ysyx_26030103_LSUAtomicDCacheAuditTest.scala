package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.mem.ysyx_26030103_LSU
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 原子事务与 DCache refill 共用 AR/R 的审计测试。
  *
  * 场景：DCache 的 64 字节行回填已经接受 AR 并返回关键词 beat（LSU 的
  * load 因此提前完成），但 refill 仍在后台继续。此时发射一条 AMO，并在
  * DCacheBusActive 期间把 io.DataBus.AR.ARREADY 拉高。
  *
  * 正确实现必须等 DCache refill 完全排空后才让原子事务发自己的 AR；
  * 错误实现会在 ARVALID 被 DCache 覆盖时直接观察外部 ARREADY 并误认为
  * 自己的 AR 已握手，随后把 refill 的 R beat 当成原子读数据并提前发 AW/W。
  */
class ysyx_26030103_LSUAtomicDCacheAuditTest
    extends AnyFlatSpec with Matchers {
  private val AMOADD = BigInt(0x00)
  private val LineBase = 0x80000000L
  private val AtomicAddr = 0x80000040L

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long = value.peek().litValue.toLong

  private def resetDut(
      dut: ysyx_26030103_LSU,
      clock: Clock,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.in.bits)))
    dut.io.out.ready.poke(true.B)
    dut.io.DCacheFlush.poke(false.B)
    dut.io.BusQuiet.poke(true.B)
    dut.io.ReservationClear.poke(false.B)
    dut.io.DCacheDmaSnoopValid.poke(false.B)
    dut.io.DCacheDmaSnoopStart.poke(0.U)
    dut.io.DCacheDmaSnoopEnd.poke(0.U)
    dut.io.ViptPAddr.poke(0.U)
    dut.io.ViptPAddrValid.poke(false.B)
    dut.io.ViptPAddrVaddr.poke(0.U)
    dut.io.ViptKill.poke(false.B)
    dut.io.PmpFault.poke(false.B)
    dut.io.DataBus.AW.AWREADY.poke(false.B)
    dut.io.DataBus.W.WREADY.poke(false.B)
    dut.io.DataBus.B.BVALID.poke(false.B)
    dut.io.DataBus.B.BID.poke(0.U)
    dut.io.DataBus.B.BRESP.poke(0.U)
    dut.io.DataBus.AR.ARREADY.poke(false.B)
    dut.io.DataBus.R.RVALID.poke(false.B)
    dut.io.DataBus.R.RID.poke(0.U)
    dut.io.DataBus.R.RDATA.poke(0.U)
    dut.io.DataBus.R.RRESP.poke(0.U)
    dut.io.DataBus.R.RLAST.poke(false.B)
  }

  private def pokeLoad(
      dut: ysyx_26030103_LSU,
      address: Long
  ): Unit = {
    val addrWidth = dut.io.in.bits.ALUResult.getWidth
    dut.io.in.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.in.bits)))
    dut.io.in.bits.Retire.poke(true.B)
    dut.io.in.bits.RegisterWrite.poke(true.B)
    dut.io.in.bits.WBSelect.poke(1.U(2.W))
    dut.io.in.bits.Rd.poke(5.U)
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(false.B)
    dut.io.in.bits.IsAtomic.poke(false.B)
    dut.io.in.bits.ALUResult.poke(address.U(addrWidth.W))
    dut.io.in.bits.VAddr.poke(address.U(addrWidth.W))
    dut.io.in.bits.WidthSelect.poke(2.U(2.W))
    dut.io.in.bits.pc.poke(0x80000000L.U(addrWidth.W))
  }

  private def pokeAtomic(
      dut: ysyx_26030103_LSU,
      op: BigInt,
      address: Long,
      rhs: BigInt
  ): Unit = {
    val addrWidth = dut.io.in.bits.ALUResult.getWidth
    dut.io.in.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.in.bits)))
    dut.io.in.bits.Retire.poke(true.B)
    dut.io.in.bits.RegisterWrite.poke(true.B)
    dut.io.in.bits.WBSelect.poke(1.U(2.W))
    dut.io.in.bits.Rd.poke(6.U)
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(true.B)
    dut.io.in.bits.IsAtomic.poke(true.B)
    dut.io.in.bits.AtomicOp.poke(op.U(5.W))
    dut.io.in.bits.ALUResult.poke(address.U(addrWidth.W))
    dut.io.in.bits.VAddr.poke(address.U(addrWidth.W))
    dut.io.in.bits.WidthSelect.poke(2.U(2.W))
    dut.io.in.bits.StoreData.poke(rhs.U(dut.io.in.bits.StoreData.getWidth.W))
    dut.io.in.bits.pc.poke(0x80000000L.U(addrWidth.W))
  }

  private def acceptInput(
      dut: ysyx_26030103_LSU,
      clock: Clock,
      limit: Int = 50
  ): Unit = {
    var cycles = 0
    while (!(bool(dut.io.in.valid) && bool(dut.io.in.ready)) &&
        cycles < limit) {
      clock.step(); cycles += 1
    }
    withClue(s"LSU 未在 $limit 拍内受理指令") { cycles should be < limit }
    clock.step()
    dut.io.in.valid.poke(false.B)
  }

  "LSU with DCache" should
    "等 refill 排空后才为 AMO 发自己的 AR，不能误用 refill 的 ARREADY" in {
    simulate(new ysyx_26030103_LSU(
      XLEN = 64,
      DCacheEnable = true,
      DCacheVipt = false,
      ViptParallel = false,
      DCacheBlockSizeLog2 = 6, // 64B line = 16 beats，保证观察窗口内 refill 不结束
      DCacheIndexBits = 4
    )) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)

      // 1) 发起一笔 32 位 load。关键词 beat0 返回后 LSU 可以提前完成，
      //    但 64B 行的其余 15 个 beat 仍在后台回填。
      pokeLoad(dut, LineBase)
      dut.io.in.valid.poke(true.B)
      acceptInput(dut, clock)

      var cycles = 0
      while (!bool(dut.io.DataBus.AR.ARVALID) && cycles < 50) {
        clock.step(); cycles += 1
      }
      bool(dut.io.DataBus.AR.ARVALID) should be(true)
      long(dut.io.DataBus.AR.ARADDR) should be(LineBase)
      long(dut.io.DataBus.AR.ARLEN) should be(15L)
      dut.io.DataBus.AR.ARREADY.poke(true.B)
      clock.step()
      dut.io.DataBus.AR.ARREADY.poke(false.B)

      // 关键词 beat0：load 在此后完成，refill 继续。
      dut.io.DataBus.R.RVALID.poke(true.B)
      dut.io.DataBus.R.RDATA.poke("h11112222".U)
      dut.io.DataBus.R.RRESP.poke(0.U)
      dut.io.DataBus.R.RLAST.poke(false.B)
      clock.step()
      dut.io.DataBus.R.RVALID.poke(false.B)

      cycles = 0
      var loadDone = false
      while (!loadDone && cycles < 50) {
        if (bool(dut.io.out.valid)) { loadDone = true }
        clock.step(); cycles += 1
      }
      withClue("load 未完成") { loadDone should be(true) }
      cycles = 0
      while (!bool(dut.io.in.ready) && cycles < 20) {
        clock.step(); cycles += 1
      }
      bool(dut.io.in.ready) should be(true)

      // 2) DCache refill 在途时发射 AMO。原子必须等 refill 排空。
      pokeAtomic(dut, AMOADD, AtomicAddr, BigInt(5))
      dut.io.in.valid.poke(true.B)
      acceptInput(dut, clock)

      // 3) 在 DCacheBusActive 期间持续给外部 ARREADY=1，同时送一个带
      //    RLAST 的 refill beat。错误实现会把它误当成原子读的最后一拍。
      dut.io.DataBus.AR.ARREADY.poke(true.B)
      dut.io.DataBus.R.RVALID.poke(true.B)
      dut.io.DataBus.R.RRESP.poke(0.U)
      dut.io.DataBus.R.RDATA.poke("h33334444".U)
      dut.io.DataBus.R.RLAST.poke(true.B)

      var earlyWrite = false
      for (_ <- 0 until 10) {
        if (bool(dut.io.DataBus.AW.AWVALID) ||
            bool(dut.io.DataBus.W.WVALID)) {
          earlyWrite = true
        }
        clock.step()
      }
      // 停止送 beat，但保持 ARREADY。refill 应该仍未完成（64B 行只消耗了
      // 不到一半），错误实现已经在写阶段。
      dut.io.DataBus.R.RVALID.poke(false.B)
      dut.io.DataBus.R.RLAST.poke(false.B)
      for (_ <- 0 until 40) {
        if (bool(dut.io.DataBus.AW.AWVALID) ||
            bool(dut.io.DataBus.W.WVALID)) {
          earlyWrite = true
        }
        clock.step()
      }
      withClue(
        "AMO 在没有自己 AR 握手的情况下开始 AW/W（把 DCache refill 的 " +
          "R beat 误当成原子读响应）: ") {
        earlyWrite should be(false)
      }
      bool(dut.io.AtomicBusy) should be(true)

      // 4) 补齐剩余 refill beat。DCache 排空后原子才发自己的 AR。
      dut.io.DataBus.R.RVALID.poke(true.B)
      dut.io.DataBus.R.RDATA.poke("h33334444".U)
      dut.io.DataBus.R.RRESP.poke(0.U)
      dut.io.DataBus.R.RLAST.poke(true.B)
      var writeSeen = false
      cycles = 0
      while (!writeSeen && cycles < 400) {
        if (bool(dut.io.DataBus.AW.AWVALID) ||
            bool(dut.io.DataBus.W.WVALID)) {
          writeSeen = true
        } else {
          clock.step(); cycles += 1
        }
      }
      withClue(s"refill 排空后 AMO 未恢复（等待 $cycles 拍）") {
        writeSeen should be(true)
      }
      dut.io.DataBus.R.RVALID.poke(false.B)
      dut.io.DataBus.R.RLAST.poke(false.B)

      // 5) 完成 AMO 写并检查返回旧值。原子自己的读应取到 0x33334444。
      dut.io.DataBus.AW.AWREADY.poke(true.B)
      dut.io.DataBus.W.WREADY.poke(true.B)
      cycles = 0
      var awDone = false
      var wDone = false
      while ((!awDone || !wDone) && cycles < 40) {
        if (bool(dut.io.DataBus.AW.AWVALID)) {
          long(dut.io.DataBus.AW.AWADDR) should be(AtomicAddr)
          awDone = true
        }
        if (bool(dut.io.DataBus.W.WVALID)) { wDone = true }
        clock.step(); cycles += 1
      }
      withClue("AMO AW/W 未握手") { (awDone && wDone) should be(true) }
      dut.io.DataBus.AW.AWREADY.poke(false.B)
      dut.io.DataBus.W.WREADY.poke(false.B)
      dut.io.DataBus.B.BVALID.poke(true.B)
      dut.io.DataBus.B.BID.poke(0.U)
      dut.io.DataBus.B.BRESP.poke(0.U)
      clock.step()
      dut.io.DataBus.B.BVALID.poke(false.B)

      cycles = 0
      var outSeen = false
      while (!outSeen && cycles < 40) {
        if (bool(dut.io.out.valid)) { outSeen = true }
        clock.step(); cycles += 1
      }
      withClue("AMO 未退休") { outSeen should be(true) }
      long(dut.io.out.bits.LoadData) should be(0x33334444L)
    }
  }
}
