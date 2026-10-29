package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.mem.ysyx_26030103_LSU
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** store 的 BRESP 错误必须精确提交为 cause=7，并且排在它后面的 store
  * 不能在此之前或之后产生任何总线副作用。写缓冲只允许队首发 AW/W，
  * 因此第二笔 store 在首个 B 之前必须完全不可见。
  */
class ysyx_26030103_LSUStoreFaultAuditTest
    extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): UInt = value

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

  private def pokeStore(
      dut: ysyx_26030103_LSU,
      address: Long,
      data: BigInt,
      pc: Long
  ): Unit = {
    val addrWidth = dut.io.in.bits.ALUResult.getWidth
    dut.io.in.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.in.bits)))
    dut.io.in.bits.Retire.poke(true.B)
    dut.io.in.bits.RegisterWrite.poke(false.B)
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(true.B)
    dut.io.in.bits.ALUResult.poke(address.U(addrWidth.W))
    dut.io.in.bits.VAddr.poke(address.U(addrWidth.W))
    dut.io.in.bits.WidthSelect.poke(2.U)
    dut.io.in.bits.StoreData.poke(data.U(64.W))
    dut.io.in.bits.pc.poke(pc.U(addrWidth.W))
    dut.io.in.valid.poke(true.B)
  }

  private def run(useDCache: Boolean): Unit = {
    simulate(new ysyx_26030103_LSU(
      XLEN = 32,
      DCacheEnable = useDCache,
      WBufDepth = 4,
      DCacheBlockSizeLog2 = 4,
      DCacheIndexBits = 2
    )) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)

      val addr1 = 0x80000100L
      val addr2 = 0x80000104L
      pokeStore(dut, addr1, BigInt(0x11111111L), 0x80000000L)
      var cycles = 0
      while (!bool(dut.io.in.ready) && cycles < 20) {
        clock.step(); cycles += 1
      }
      bool(dut.io.in.ready) should be(true)
      clock.step()
      dut.io.in.valid.poke(false.B)

      // 第二条 store 只能进入写缓冲/等待槽，不能发 AW/W。
      pokeStore(dut, addr2, BigInt(0x22222222L), 0x80000004L)
      cycles = 0
      while (!bool(dut.io.in.ready) && cycles < 20) {
        clock.step(); cycles += 1
      }
      bool(dut.io.in.ready) should be(true)
      clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.in.bits)))

      dut.io.DataBus.AW.AWREADY.poke(true.B)
      dut.io.DataBus.W.WREADY.poke(true.B)
      val awAddrs = mutable.ArrayBuffer.empty[Long]
      val wData = mutable.ArrayBuffer.empty[BigInt]
      var sawFirstAw = false
      var sawSecondAw = false
      cycles = 0
      while (!(sawFirstAw && wData.nonEmpty) && cycles < 30) {
        if (bool(dut.io.DataBus.AW.AWVALID)) {
          val a = dut.io.DataBus.AW.AWADDR.peek().litValue.toLong
          awAddrs += a
          if (a == addr1) sawFirstAw = true
          if (a == addr2) sawSecondAw = true
        }
        if (bool(dut.io.DataBus.W.WVALID)) {
          wData += dut.io.DataBus.W.WDATA.peek().litValue
        }
        clock.step(); cycles += 1
      }
      withClue("第一条 store 未发出 AW/W: ") {
        (sawFirstAw && wData.nonEmpty) should be(true)
      }
      withClue(
        s"第一条 store 的 B 之前出现了第二条 store 的总线请求: " +
          s"awAddrs=${awAddrs.map(a => f"0x$a%x").mkString(",")}: ") {
        sawSecondAw should be(false)
      }

      // 第一条 store 收到 SLVERR；此时第二条已经进入写缓冲，绝不能
      // 在故障提交后继续发到总线。
      dut.io.DataBus.AW.AWREADY.poke(false.B)
      dut.io.DataBus.W.WREADY.poke(false.B)
      dut.io.DataBus.B.BVALID.poke(true.B)
      dut.io.DataBus.B.BID.poke(0.U)
      dut.io.DataBus.B.BRESP.poke(2.U)
      clock.step()
      dut.io.DataBus.B.BVALID.poke(false.B)

      var trapped = false
      var retireSeen = false
      var secondBusAfterFault = false
      for (_ <- 0 until 40) {
        if (bool(dut.io.MemTrapCommit)) {
          trapped = true
          dut.io.MemTrapCause.peek().litValue should be(7)
        }
        if (bool(dut.io.out.valid)) {
          retireSeen = true
          bool(dut.io.out.bits.Retire) should be(false)
        }
        if (trapped && (bool(dut.io.DataBus.AW.AWVALID) ||
            bool(dut.io.DataBus.W.WVALID))) {
          secondBusAfterFault = true
        }
        clock.step()
      }
      withClue(s"store BRESP=SLVERR 未提交 cause=7（trapped=$trapped）: ") {
        trapped should be(true)
      }
      withClue("故障 store 不应产生 Retire=1 的输出: ") {
        retireSeen should be(true)
      }
      withClue("故障提交后年轻 store 仍在总线上产生副作用: ") {
        secondBusAfterFault should be(false)
      }
    }
  }

  "LSU store fault" should "无 DCache 时 BRESP 错误精确提交 cause=7" in {
    run(useDCache = false)
  }

  it should "有 DCache 时 BRESP 错误精确提交 cause=7 且不污染后续 store" in {
    run(useDCache = true)
  }
}
