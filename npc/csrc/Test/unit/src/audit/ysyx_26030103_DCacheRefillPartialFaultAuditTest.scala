package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.cache._
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 回填行中先出现错误 beat、随后关键词 beat 正常时，32 位 demand 仍须报错。
  *
  * 线路：32 位 DCache，16B line；load 地址在行内 word2，关键词是 beat2。
  * beat0 返回 SLVERR 后 DCache 已为尚未交付的 demand 产生 fault 响应；
  * 后续 beat2 的 RRESP=OKAY 不能把该 fault 覆盖成成功数据。否则一次
  * 部分失败的行回填会把错误数据/未定义行内容退休到寄存器。
  *
  * 既有 DCacheRefillFaultAuditTest 只覆盖 64 位 load（关键词分支不写
  * ResponseFault），没有覆盖 32 位关键词分支覆盖 fault 的路径。
  */
class ysyx_26030103_DCacheRefillPartialFaultAuditTest
    extends AnyFlatSpec with Matchers {
  private val LineBase = 0x80000000L
  private val LoadAddr = LineBase + 8

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long = value.peek().litValue.toLong

  private val region = ysyx_26030103_PMARegion(
    Base = 0x80000000L,
    Size = 0x10000000L,
    Cacheable = true,
    Executable = true
  )

  private def create(kind: String): ysyx_26030103_DCacheBase =
    if (kind == "reg") {
      new ysyx_26030103_DCacheReg(
        Enable = true,
        BlockSizeLog2 = 4,
        IndexBits = 4,
        AddressWidth = 32,
        DataWidth = 32,
        PMARegions = Seq(region)
      )
    } else {
      new ysyx_26030103_DCacheSRAM(
        Enable = true,
        BlockSizeLog2 = 4,
        IndexBits = 4,
        AddressWidth = 32,
        DataWidth = 32,
        Bypass = kind != "nobypass",
        PMARegions = Seq(region)
      )
    }

  private def resetDut(
      io: ysyx_26030103_DCacheInterface,
      clock: Clock,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    io.req.valid.poke(false.B)
    io.req.bits.addr.poke(0.U)
    io.req.bits.vaddr.poke(0.U)
    io.req.bits.WidthSelect.poke(2.U)
    io.req.bits.signed.poke(false.B)
    io.req_paddr_valid.poke(true.B)
    io.req_paddr_vaddr.poke(0.U)
    io.req_kill.poke(false.B)
    io.resp.ready.poke(false.B)
    io.StoreValid.poke(false.B)
    io.StoreResp.poke(0.U)
    io.StoreAddr.poke(0.U)
    io.StoreVAddr.poke(0.U)
    io.StoreData.poke(0.U)
    io.StoreStrb.poke(0.U)
    io.flush.poke(false.B)
    io.dma_snoop_valid.poke(false.B)
    io.dma_snoop_start.poke(0.U)
    io.dma_snoop_end.poke(0.U)
    io.AXI.AR.ARREADY.poke(false.B)
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RDATA.poke(0.U)
    io.AXI.R.RRESP.poke(0.U)
    io.AXI.R.RLAST.poke(false.B)
    io.AXI.R.RID.poke(0.U)
  }

  private def run(kind: String): Unit = {
    simulate(create(kind)) { dut =>
      val clock = dut.clock
      resetDut(dut.io, clock, dut.reset)

      dut.io.req.bits.addr.poke(LoadAddr.U)
      dut.io.req.bits.vaddr.poke(LoadAddr.U)
      dut.io.req.bits.WidthSelect.poke(2.U)
      dut.io.req.valid.poke(true.B)
      var cycles = 0
      while (!bool(dut.io.req.ready) && cycles < 20) {
        clock.step(); cycles += 1
      }
      withClue(s"$kind DCache 未受理 load: ") {
        bool(dut.io.req.ready) should be(true)
      }
      clock.step()
      dut.io.req.valid.poke(false.B)

      cycles = 0
      while (!bool(dut.io.AXI.AR.ARVALID) && cycles < 20) {
        clock.step(); cycles += 1
      }
      bool(dut.io.AXI.AR.ARVALID) should be(true)
      long(dut.io.AXI.AR.ARLEN) should be(3L)
      long(dut.io.AXI.AR.ARADDR) should be(LineBase)
      dut.io.AXI.AR.ARREADY.poke(true.B)
      clock.step()
      dut.io.AXI.AR.ARREADY.poke(false.B)

      // beat0 错误，beat1/2/3 正常；关键词 beat2 必须不能覆盖先前的 fault。
      for (beat <- 0 until 4) {
        dut.io.AXI.R.RVALID.poke(true.B)
        dut.io.AXI.R.RDATA.poke((0x60000000L + beat).U)
        dut.io.AXI.R.RRESP.poke(if (beat == 0) 2.U else 0.U)
        dut.io.AXI.R.RLAST.poke((beat == 3).B)
        clock.step()
        dut.io.AXI.R.RVALID.poke(false.B)
        dut.io.AXI.R.RLAST.poke(false.B)
      }
      dut.io.AXI.R.RRESP.poke(0.U)

      cycles = 0
      while (!bool(dut.io.resp.valid) && cycles < 50) {
        clock.step(); cycles += 1
      }
      withClue(s"$kind DCache 未产生响应: ") {
        bool(dut.io.resp.valid) should be(true)
      }
      withClue(
        s"$kind DCache 把早期错误 beat 的 fault 覆盖成成功数据: " +
          s"fault=${bool(dut.io.resp.bits.fault)} " +
          s"resp=${long(dut.io.resp.bits.FaultResp)} " +
          s"data=0x${dut.io.resp.bits.data.peek().litValue.toString(16)}: ") {
        bool(dut.io.resp.bits.fault) should be(true)
      }
      long(dut.io.resp.bits.FaultResp) should be(2L)
    }
  }

  "DCacheReg" should
    "32 位 load 在早期 beat 出错后不能被正常关键词 beat 覆盖 fault" in {
    run("reg")
  }

  "DCacheSRAM" should
    "32 位 load 在早期 beat 出错后不能被正常关键词 beat 覆盖 fault" in {
    run("sram")
  }

  "DCacheSRAM (NoBypass)" should
    "32 位 load 在早期 beat 出错后不能被正常关键词 beat 覆盖 fault" in {
    run("nobypass")
  }
}
