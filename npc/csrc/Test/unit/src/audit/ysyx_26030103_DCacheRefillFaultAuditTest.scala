package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.cache._
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** DCache refill 错误响应审计：64 位与 32 位 demand 的 fault 路径。
  *
  * 64 位：load 地址落在行内 word2，关键词是 beat2、宽访问高字是 beat3。
  * beat0 返回 SLVERR 后，refill 结束必须为尚未交付的 demand 产生 fault
  * 响应，不能让 LSU 永久等待。
  *
  * 32 位：load 地址同样在 word2，关键词是 beat2。beat0 已产生 fault
  * 响应后，后续关键词 beat 的 RRESP=OKAY 不能把该 fault 覆盖成成功数据。
  *
  * 两条路径的断言各自独立，防止任一分支回归时被另一分支掩盖。
  */
class ysyx_26030103_DCacheRefillFaultAuditTest
    extends AnyFlatSpec with Matchers {
  private val LineBase = 0x80000000L
  private val LoadAddress = LineBase + 8

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long = value.peek().litValue.toLong

  private val region = ysyx_26030103_PMARegion(
    Base = 0x80000000L,
    Size = 0x10000000L,
    Cacheable = true,
    Executable = true
  )

  private def create(
      kind: String,
      dataWidth: Int
  ): ysyx_26030103_DCacheBase =
    if (kind == "reg") {
      new ysyx_26030103_DCacheReg(
        Enable = true,
        BlockSizeLog2 = 4,
        IndexBits = 4,
        AddressWidth = 32,
        DataWidth = dataWidth,
        BusDataWidth = 32,
        PMARegions = Seq(region)
      )
    } else {
      new ysyx_26030103_DCacheSRAM(
        Enable = true,
        BlockSizeLog2 = 4,
        IndexBits = 4,
        AddressWidth = 32,
        DataWidth = dataWidth,
        BusDataWidth = 32,
        Bypass = kind != "nobypass",
        PMARegions = Seq(region)
      )
    }

  private def resetDut(
      io: ysyx_26030103_DCacheInterface,
      clock: Clock,
      reset: Reset,
      defaultWidthSelect: Int
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    io.request.valid.poke(false.B)
    io.request.bits.address.poke(0.U)
    io.request.bits.vaddr.poke(0.U)
    io.request.bits.WidthSelect.poke(defaultWidthSelect.U)
    io.request.bits.signed.poke(false.B)
    io.RequestPaddrValid.poke(true.B)
    io.RequestPaddrVaddr.poke(0.U)
    io.RequestKill.poke(false.B)
    io.response.ready.poke(false.B)
    io.StoreValid.poke(false.B)
    io.StoreResponse.poke(0.U)
    io.StoreAddress.poke(0.U)
    io.StoreVAddress.poke(0.U)
    io.StoreData.poke(0.U)
    io.StoreStrobe.poke(0.U)
    io.flush.poke(false.B)
    io.DMASnoopValid.poke(false.B)
    io.DMASnoopStart.poke(0.U)
    io.DMASnoopEnd.poke(0.U)
    io.AXI.AR.ARREADY.poke(false.B)
    io.AXI.R.RVALID.poke(false.B)
    io.AXI.R.RDATA.poke(0.U)
    io.AXI.R.RRESP.poke(0.U)
    io.AXI.R.RLAST.poke(false.B)
    io.AXI.R.RID.poke(0.U)
  }

  private def acceptLoad(
      dut: ysyx_26030103_DCacheBase,
      widthSelect: Int,
      kind: String
  ): Unit = {
    dut.io.request.bits.address.poke(LoadAddress.U)
    dut.io.request.bits.vaddr.poke(LoadAddress.U)
    dut.io.request.bits.WidthSelect.poke(widthSelect.U)
    dut.io.request.valid.poke(true.B)
    var cycles = 0
    while (!bool(dut.io.request.ready) && cycles < 20) {
      dut.clock.step(); cycles += 1
    }
    withClue(s"$kind DCache 未受理 load: ") {
      bool(dut.io.request.ready) should be(true)
    }
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
  }

  private def startRefill(
      dut: ysyx_26030103_DCacheBase,
      expectLineAddress: Boolean
  ): Unit = {
    var cycles = 0
    while (!bool(dut.io.AXI.AR.ARVALID) && cycles < 20) {
      dut.clock.step(); cycles += 1
    }
    bool(dut.io.AXI.AR.ARVALID) should be(true)
    long(dut.io.AXI.AR.ARLEN) should be(3L)
    if (expectLineAddress) {
      long(dut.io.AXI.AR.ARADDR) should be(LineBase)
    }
    dut.io.AXI.AR.ARREADY.poke(true.B)
    dut.clock.step()
    dut.io.AXI.AR.ARREADY.poke(false.B)
  }

  private def feedBeats(
      dut: ysyx_26030103_DCacheBase,
      dataBase: Long
  ): Unit = {
    for (beat <- 0 until 4) {
      dut.io.AXI.R.RVALID.poke(true.B)
      dut.io.AXI.R.RDATA.poke((dataBase + beat).U)
      dut.io.AXI.R.RRESP.poke(if (beat == 0) 2.U else 0.U)
      dut.io.AXI.R.RLAST.poke((beat == 3).B)
      dut.clock.step()
      dut.io.AXI.R.RVALID.poke(false.B)
      dut.io.AXI.R.RLAST.poke(false.B)
    }
    dut.io.AXI.R.RRESP.poke(0.U)
  }

  private def run64(kind: String): Unit = {
    simulate(create(kind, dataWidth = 64)) { dut =>
      val clock = dut.clock
      resetDut(dut.io, clock, dut.reset, defaultWidthSelect = 3)
      acceptLoad(dut, widthSelect = 3, kind)
      startRefill(dut, expectLineAddress = false)
      // beat0 非关键词，返回 SLVERR；关键词 beat2/high beat3 都正常。
      feedBeats(dut, dataBase = 0x50000000L)

      // 响应槽保持不消费，等待 refill 结束后的 fault。
      var cycles = 0
      while (!bool(dut.io.response.valid) && cycles < 50) {
        clock.step(); cycles += 1
      }
      withClue(
        s"$kind DCache 在非关键词 beat 出错后没有在 50 拍内给出响应: ") {
        bool(dut.io.response.valid) should be(true)
      }
      bool(dut.io.response.bits.fault) should be(true)
      long(dut.io.response.bits.FaultResponse) should be(2L)
    }
  }

  private def run32(kind: String): Unit = {
    simulate(create(kind, dataWidth = 32)) { dut =>
      val clock = dut.clock
      resetDut(dut.io, clock, dut.reset, defaultWidthSelect = 2)
      acceptLoad(dut, widthSelect = 2, kind)
      startRefill(dut, expectLineAddress = true)
      // beat0 错误，beat1/2/3 正常；关键词 beat2 必须不能覆盖先前的 fault。
      feedBeats(dut, dataBase = 0x60000000L)

      var cycles = 0
      while (!bool(dut.io.response.valid) && cycles < 50) {
        clock.step(); cycles += 1
      }
      withClue(s"$kind DCache 未产生响应: ") {
        bool(dut.io.response.valid) should be(true)
      }
      withClue(
        s"$kind DCache 把早期错误 beat 的 fault 覆盖成成功数据: " +
          s"fault=${bool(dut.io.response.bits.fault)} " +
          s"response=${long(dut.io.response.bits.FaultResponse)} " +
          s"data=0x${dut.io.response.bits.data.peek().litValue.toString(16)}: ") {
        bool(dut.io.response.bits.fault) should be(true)
      }
      long(dut.io.response.bits.FaultResponse) should be(2L)
    }
  }

  "DCacheReg" should
    "64 位 load 遇到非关键词 beat 的 SLVERR 时最终返回 fault" in {
    run64("reg")
  }

  "DCacheSRAM" should
    "64 位 load 遇到非关键词 beat 的 SLVERR 时最终返回 fault" in {
    run64("sram")
  }

  "DCacheReg" should
    "32 位 load 在早期 beat 出错后不能被正常关键词 beat 覆盖 fault" in {
    run32("reg")
  }

  "DCacheSRAM" should
    "32 位 load 在早期 beat 出错后不能被正常关键词 beat 覆盖 fault" in {
    run32("sram")
  }

  "DCacheSRAM (NoBypass)" should
    "32 位 load 在早期 beat 出错后不能被正常关键词 beat 覆盖 fault" in {
    run32("nobypass")
  }
}
