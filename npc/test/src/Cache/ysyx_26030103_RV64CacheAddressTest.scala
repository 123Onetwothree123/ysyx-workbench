package ysyx_26030103.cache

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common._

/** RV64 Cache 64 位地址/高位 tag 定向测试。
  *
  * 用高于 4GiB 的 PMA 区域验证 ICache/DCache 的 tag、index、refill AR
  * 地址和 DCache 8 字节访问都不会退化为 32 位。
  */
class ysyx_26030103_RV64CacheAddressTest extends AnyFlatSpec with Matchers {
  private val AddrW = 64
  private val BlockLog2 = 4
  private val IndexBits = 2
  private val HighBase = 0x180000000L
  private val HighEnd = HighBase + 0x10000L
  private val Regions = Seq(
    ysyx_26030103_PMARegion(
      HighBase,
      HighEnd - HighBase,
      Cacheable = true,
      Executable = true
    )
  )

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def big(value: UInt): BigInt = value.peek().litValue

  private def waitUntil(clock: Clock, cond: => Boolean, message: String): Unit = {
    var cycles = 0
    while (!cond && cycles < 500) {
      clock.step(); cycles += 1
    }
    withClue(s"$message（等待 $cycles 拍）") { cond should be(true) }
  }

  private def resetICache(
      io: ysyx_26030103_ICacheInterface,
      clock: Clock,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    io.fetch_addr.poke(0.U(AddrW.W))
    io.fetch_vaddr.poke(0.U(AddrW.W))
    io.fetch_paddr_valid.poke(true.B)
    io.fetch_paddr_vaddr.poke(0.U(AddrW.W))
    io.fetch_valid.poke(false.B)
    io.resp_ready.poke(false.B)
    io.axi.AR.ARREADY.poke(false.B)
    io.axi.R.RDATA.poke(0.U(32.W))
    io.axi.R.RRESP.poke(0.U(2.W))
    io.axi.R.RLAST.poke(false.B)
    io.axi.R.RVALID.poke(false.B)
    io.flush.poke(false.B)
    io.kill.poke(false.B)
  }

  private def runICache(dut: => ysyx_26030103_ICacheReg): Unit = {
    simulate(dut) { dut =>
      val io = dut.io
      val clock = dut.clock
      resetICache(io, clock, dut.reset)

      val Address = BigInt("180003010", 16)
      io.fetch_addr.poke(Address.U(AddrW.W))
      io.fetch_vaddr.poke(Address.U(AddrW.W))
      io.fetch_paddr_vaddr.poke(Address.U(AddrW.W))
      io.fetch_valid.poke(true.B)
      waitUntil(clock, bool(io.fetch_ready), "ICache 未受理高地址取指")
      clock.step()
      io.fetch_valid.poke(false.B)

      waitUntil(clock, bool(io.axi.AR.ARVALID), "ICache 未发出高地址 refill")
      big(io.axi.AR.ARADDR) should be(BigInt("180003010", 16))
      big(io.axi.AR.ARLEN) should be(3)
      io.axi.AR.ARREADY.poke(true.B)
      clock.step()
      io.axi.AR.ARREADY.poke(false.B)

      val words = Seq("11111111", "22222222", "33333333", "44444444")
          .map(BigInt(_, 16))
      for (beat <- 0 until 4) {
        io.axi.R.RDATA.poke(words(beat).U(32.W))
        io.axi.R.RRESP.poke(0.U(2.W))
        io.axi.R.RLAST.poke((beat == 3).B)
        io.axi.R.RVALID.poke(true.B)
        clock.step()
      }
      io.axi.R.RVALID.poke(false.B)
      io.axi.R.RLAST.poke(false.B)

      waitUntil(clock, bool(io.resp_valid), "高地址 ICache 没有响应")
      big(io.resp_data) should be(words(0))
      big(io.resp_addr) should be(Address)
      io.resp_ready.poke(true.B)
      clock.step()
      io.resp_ready.poke(false.B)

      // 同一行命中不能再次发 AR；高 32 位 tag 不同的行必须 miss。
      val HitAddress = BigInt("180003014", 16)
      io.fetch_addr.poke(HitAddress.U(AddrW.W))
      io.fetch_vaddr.poke(HitAddress.U(AddrW.W))
      io.fetch_paddr_vaddr.poke(HitAddress.U(AddrW.W))
      io.fetch_valid.poke(true.B)
      waitUntil(clock, bool(io.fetch_ready), "ICache 同行取指未被受理")
      clock.step()
      io.fetch_valid.poke(false.B)
      clock.step(2)
      bool(io.axi.AR.ARVALID) should be(false)
      waitUntil(clock, bool(io.resp_valid), "ICache 同行命中没有响应")
      big(io.resp_data) should be(words(1))
      io.resp_ready.poke(true.B)
      clock.step()
      io.resp_ready.poke(false.B)

      val AliasAddress = BigInt("180004010", 16)
      io.fetch_addr.poke(AliasAddress.U(AddrW.W))
      io.fetch_vaddr.poke(AliasAddress.U(AddrW.W))
      io.fetch_paddr_vaddr.poke(AliasAddress.U(AddrW.W))
      io.fetch_valid.poke(true.B)
      waitUntil(clock, bool(io.fetch_ready), "ICache 高 tag 别名取指未被受理")
      clock.step()
      io.fetch_valid.poke(false.B)
      waitUntil(clock, bool(io.axi.AR.ARVALID), "高 tag 别名行未 miss")
      big(io.axi.AR.ARADDR) should be(BigInt("180004010", 16))
    }
  }

  "RV64 ICache" should "在高于 4GiB 的地址上完成 refill、命中与 tag 区分（Reg）" in {
    runICache(
      new ysyx_26030103_ICacheReg(
        Enable = true,
        BlockSizeLog2 = BlockLog2,
        IndexBits = IndexBits,
        AddressWidth = AddrW,
        PMARegions = Regions
      )
    )
  }

  "RV64 ICache" should "在高于 4GiB 的地址上完成 refill 与命中（SRAM）" in {
    simulate(
      new ysyx_26030103_ICacheSRAM(
        Enable = true,
        BlockSizeLog2 = BlockLog2,
        IndexBits = IndexBits,
        AddressWidth = AddrW,
        PMARegions = Regions
      )
    ) { dut =>
      val io = dut.io
      val clock = dut.clock
      resetICache(io, clock, dut.reset)
      val Address = BigInt("180003020", 16)
      io.fetch_addr.poke(Address.U(AddrW.W))
      io.fetch_vaddr.poke(Address.U(AddrW.W))
      io.fetch_paddr_vaddr.poke(Address.U(AddrW.W))
      io.fetch_valid.poke(true.B)
      waitUntil(clock, bool(io.fetch_ready), "SRAM ICache 未受理高地址取指")
      clock.step()
      io.fetch_valid.poke(false.B)
      waitUntil(clock, bool(io.axi.AR.ARVALID), "SRAM ICache 未发高地址 refill")
      big(io.axi.AR.ARADDR) should be(BigInt("180003020", 16))
      io.axi.AR.ARREADY.poke(true.B)
      clock.step()
      io.axi.AR.ARREADY.poke(false.B)
      for (beat <- 0 until 4) {
        io.axi.R.RDATA.poke((0x10 + beat).U(32.W))
        io.axi.R.RRESP.poke(0.U(2.W))
        io.axi.R.RLAST.poke((beat == 3).B)
        io.axi.R.RVALID.poke(true.B)
        clock.step()
      }
      io.axi.R.RVALID.poke(false.B)
      io.axi.R.RLAST.poke(false.B)
      waitUntil(clock, bool(io.resp_valid), "SRAM ICache 没有响应")
      big(io.resp_addr) should be(Address)
    }
  }

  private def resetDCache(
      io: ysyx_26030103_DCacheInterface,
      clock: Clock,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    io.req.valid.poke(false.B)
    io.req.bits.poke(0.U.asTypeOf(new ysyx_26030103_DCacheRequest(AddrW)))
    io.req_paddr_valid.poke(true.B)
    io.req_paddr_vaddr.poke(0.U(AddrW.W))
    io.req_kill.poke(false.B)
    io.resp.ready.poke(false.B)
    io.StoreValid.poke(false.B)
    io.StoreResp.poke(0.U(2.W))
    io.StoreAddr.poke(0.U(AddrW.W))
    io.StoreVAddr.poke(0.U(AddrW.W))
    io.StoreData.poke(0.U(64.W))
    io.StoreStrb.poke(0.U(8.W))
    io.AXI.AR.ARREADY.poke(false.B)
    io.AXI.R.RDATA.poke(0.U(32.W))
    io.AXI.R.RRESP.poke(0.U(2.W))
    io.AXI.R.RLAST.poke(false.B)
    io.AXI.R.RVALID.poke(false.B)
    io.flush.poke(false.B)
  }

  private def issueWideRead(
      io: ysyx_26030103_DCacheInterface,
      clock: Clock,
      address: BigInt
  ): Unit = {
    io.req.bits.addr.poke(address.U(AddrW.W))
    io.req.bits.vaddr.poke(address.U(AddrW.W))
    io.req.bits.WidthSelect.poke(3.U(2.W))
    io.req.bits.signed.poke(true.B)
    io.req.valid.poke(true.B)
    waitUntil(clock, bool(io.req.ready), "DCache 未受理 64 位 load")
    clock.step()
    io.req.valid.poke(false.B)
  }

  private def runDCache(dut: => ysyx_26030103_DCacheBase, label: String): Unit = {
    simulate(dut) { dut =>
      val io = dut.io
      val clock = dut.clock
      resetDCache(io, clock, dut.reset)

      val Address = BigInt("180003018", 16)
      issueWideRead(io, clock, Address)
      waitUntil(clock, bool(io.AXI.AR.ARVALID), s"$label DCache 未发出高地址 refill")
      big(io.AXI.AR.ARADDR) should be(BigInt("180003010", 16))
      big(io.AXI.AR.ARLEN) should be(3)
      io.AXI.AR.ARREADY.poke(true.B)
      clock.step()
      io.AXI.AR.ARREADY.poke(false.B)

      val words = Seq("11111111", "22222222", "33333333", "44444444")
          .map(BigInt(_, 16))
      for (beat <- 0 until 4) {
        io.AXI.R.RDATA.poke(words(beat).U(32.W))
        io.AXI.R.RRESP.poke(0.U(2.W))
        io.AXI.R.RLAST.poke((beat == 3).B)
        io.AXI.R.RVALID.poke(true.B)
        clock.step()
      }
      io.AXI.R.RVALID.poke(false.B)
      io.AXI.R.RLAST.poke(false.B)

      waitUntil(clock, bool(io.resp.valid), s"$label DCache 没有 64 位响应")
      big(io.resp.bits.data) should be(BigInt("4444444433333333", 16))
      io.resp.ready.poke(true.B)
      clock.step()
      io.resp.ready.poke(false.B)

      // 同一 8 字节地址必须命中，不得重新发 AR。
      issueWideRead(io, clock, Address)
      waitUntil(clock, bool(io.resp.valid), s"$label DCache 高地址不命中")
      big(io.resp.bits.data) should be(BigInt("4444444433333333", 16))
      bool(io.AXI.AR.ARVALID) should be(false)
      io.resp.ready.poke(true.B)
      clock.step()
      io.resp.ready.poke(false.B)

      // 8 字节 store 通过 B 响应后更新低/高两个 word，后续 load 直接命中。
      io.StoreValid.poke(true.B)
      io.StoreResp.poke(0.U(2.W))
      io.StoreAddr.poke(Address.U(AddrW.W))
      io.StoreVAddr.poke(Address.U(AddrW.W))
      io.StoreData.poke(BigInt("aaaabbbbccccdddd", 16).U(64.W))
      io.StoreStrb.poke("hff".U(8.W))
      clock.step()
      io.StoreValid.poke(false.B)

      issueWideRead(io, clock, Address)
      waitUntil(clock, bool(io.resp.valid), s"$label DCache store 后 load 未命中")
      big(io.resp.bits.data) should be(BigInt("aaaabbbbccccdddd", 16))
      bool(io.AXI.AR.ARVALID) should be(false)
    }
  }

  "RV64 DCache" should "在高于 4GiB 地址上完成 8 字节 refill/hit/store（Reg）" in {
    runDCache(
      new ysyx_26030103_DCacheReg(
        Enable = true,
        BlockSizeLog2 = BlockLog2,
        IndexBits = IndexBits,
        AddressWidth = AddrW,
        DataWidth = 64,
        PMARegions = Regions
      ),
      "Reg"
    )
  }

  "RV64 DCache" should "在高于 4GiB 地址上完成 8 字节 refill/hit/store（SRAM）" in {
    runDCache(
      new ysyx_26030103_DCacheSRAM(
        Enable = true,
        BlockSizeLog2 = BlockLog2,
        IndexBits = IndexBits,
        AddressWidth = AddrW,
        DataWidth = 64,
        PMARegions = Regions
      ),
      "SRAM"
    )
  }
}
