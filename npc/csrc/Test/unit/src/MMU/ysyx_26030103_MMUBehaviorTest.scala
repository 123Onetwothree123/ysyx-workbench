package ysyx_26030103.mmu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile
import _root_.ysyx_26030103.config.ysyx_26030103_PTEConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** TLB/PTW/MMU 的定向行为测试。
  *
  * 页表放在一个 Scala 侧的稀疏内存里，通过 PTW 总线请求逐拍响应。
  * 这些测试不依赖 SoC，便于单独定位 MMU 的握手、权限和页错误问题。
  */
class ysyx_26030103_MMUBehaviorTest extends AnyFlatSpec with Matchers {
  // 本套件按 Sv32（2 级、4B PTE、32 位 VA/PA）编写。64 位 Kconfig 默认下
  // 必须显式构造 RV32 profile/PTE，否则默认 Sv39 会让 satp 与页表布局错位。
  private def rv32Profile: ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile.fromEnv(_ => None, xlen = 32)
  private val Rv32PTE: ysyx_26030103_PTEConfig =
    ysyx_26030103_PTEConfig(PTEBits = 32, PPNHigh = 31)

  private val PageShift = 12
  private val RootPPN = 0x80001L
  private val Root = RootPPN << PageShift
  private val LeafPPN = 0x80003L
  private val Leaf = LeafPPN << PageShift

  private def PTE(PPN: Long, flags: Long): BigInt =
    ((PPN & 0x3fffffL) << 10) | flags

  private val PointerPTE = PTE(0x80002L, 0x1L) // V，非叶子
  private val LeafPTE = PTE(LeafPPN, 0xdfL) // V R W X U A D

  /** 稀疏页表内存。默认预置一份 4B PTE 的两级 Sv32 页表；8B PTE 用例
    * 必须使用 withDefaults=false，否则默认的两级表项会落进 8B 表项的
    * 高半字，被组装成非法 PTE。
    */
  private class Memory(withDefaults: Boolean = true) {
    private val data = scala.collection.mutable.Map.empty[Long, BigInt]
    if (withDefaults) {
      data(Root + 0x0L) = PointerPTE
      data(0x80002000L + 0x4L) = LeafPTE
    }
    def read(addr: Long): BigInt = data.getOrElse(addr & 0xffffffffL, BigInt(0))
    def write(addr: Long, value: BigInt): Unit = data(addr & 0xffffffffL) = value
  }

  private def asBool(value: UInt): Boolean = value.peek().litValue == 1
  private def asLong(value: UInt): Long = value.peek().litValue.toLong

  "TLB" should "命中已填充的页，并在 ASID/权限不匹配时失效" in {
    simulate(new ysyx_26030103_TLB(
      entries = 4,
      ways = 1,
      ASIDBits = 9,
      globalEnable = true,
      adMode = "fault"
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.io.fillValid.poke(false.B)
      dut.io.fillVirtualAddress.poke(0.U)
      dut.io.FillASID.poke(0.U)
      dut.io.FillPTE.poke(0.U)
      dut.io.fillLevel.poke(0.U)
      dut.io.lookup(0).vaddr.poke(0.U)
      dut.io.lookup(0).ASID.poke(0.U)
      dut.io.lookup(0).privilege.poke(0.U)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Load)
      dut.io.lookup(0).sum.poke(false.B)
      dut.io.lookup(0).mxr.poke(false.B)
      dut.clock.step()

      dut.io.flush.poke(false.B)
      dut.io.fillValid.poke(true.B)
      dut.io.fillVirtualAddress.poke(0x1234.U)
      dut.io.FillASID.poke(7.U)
      dut.io.FillPTE.poke(LeafPTE.U(32.W))
      dut.io.fillLevel.poke(0.U)
      dut.clock.step()
      dut.io.fillValid.poke(false.B)

      dut.io.lookup(0).vaddr.poke(0x1234.U)
      dut.io.lookup(0).ASID.poke(7.U)
      dut.io.lookup(0).privilege.poke(0.U)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Load)
      asBool(dut.io.result(0).hit) should be(true)
      asBool(dut.io.result(0).fault) should be(false)
      asLong(dut.io.result(0).paddr) should be(Leaf + 0x234L)

      // ASID 不同，不应命中（Global=0）。
      dut.io.lookup(0).ASID.poke(8.U)
      dut.io.lookup(0).vaddr.poke(0x1234.U)
      asBool(dut.io.result(0).hit) should be(false)

      // U 页的取指在 S 模式必须失败；S 模式数据访问需要 SUM。
      dut.io.lookup(0).ASID.poke(7.U)
      dut.io.lookup(0).privilege.poke(1.U)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Fetch)
      asBool(dut.io.result(0).hit) should be(true)
      asBool(dut.io.result(0).fault) should be(true)

      dut.io.lookup(0).sum.poke(false.B)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Load)
      asBool(dut.io.result(0).fault) should be(true)
      dut.io.lookup(0).sum.poke(true.B)
      asBool(dut.io.result(0).fault) should be(false)
    }
  }

  "TLB" should "同拍接受两个 refill 端口" in {
    simulate(new ysyx_26030103_TLB(
      entries = 4, ways = 1, ASIDBits = 9, globalEnable = false,
      adMode = "ignore", refillPorts = 2
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.io.fillValid.poke(false.B)
      dut.io.fillVirtualAddress.poke(0.U)
      dut.io.FillASID.poke(0.U)
      dut.io.FillPTE.poke(0.U)
      dut.io.fillLevel.poke(0.U)
      val extra = dut.io.fillExtra.get(0)
      extra.valid.poke(false.B)
      extra.vaddr.poke(0.U)
      extra.ASID.poke(0.U)
      extra.PTE.poke(0.U)
      extra.level.poke(0.U)
      dut.io.lookup(0).vaddr.poke(0.U)
      dut.io.lookup(0).ASID.poke(0.U)
      dut.io.lookup(0).privilege.poke(0.U)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Load)
      dut.io.lookup(0).sum.poke(false.B)
      dut.io.lookup(0).mxr.poke(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.fillValid.poke(true.B)
      dut.io.fillVirtualAddress.poke(0x1000.U)
      dut.io.FillPTE.poke(LeafPTE.U(32.W))
      extra.valid.poke(true.B)
      extra.vaddr.poke(0x2000.U)
      extra.PTE.poke(LeafPTE.U(32.W))
      dut.clock.step()
      dut.io.fillValid.poke(false.B)
      extra.valid.poke(false.B)
      dut.io.lookup(0).vaddr.poke(0x1000.U)
      asBool(dut.io.result(0).hit) should be(true)
      dut.io.lookup(0).vaddr.poke(0x2000.U)
      asBool(dut.io.result(0).hit) should be(true)
    }
  }

  "TLB" should "同一拍用两个查找端口命中不同表项" in {
    val OtherLeafPTE = PTE(0x80004L, 0xdfL)
    simulate(new ysyx_26030103_TLB(
      entries = 8, ways = 1, ASIDBits = 9, globalEnable = true,
      adMode = "ignore", refillPorts = 2, lookupPorts = 2
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.io.fillValid.poke(false.B)
      dut.io.fillVirtualAddress.poke(0.U)
      dut.io.FillASID.poke(0.U)
      dut.io.FillPTE.poke(0.U)
      dut.io.fillLevel.poke(0.U)
      val extra = dut.io.fillExtra.get(0)
      extra.valid.poke(false.B)
      extra.vaddr.poke(0.U)
      extra.ASID.poke(0.U)
      extra.PTE.poke(0.U)
      extra.level.poke(0.U)
      for (p <- 0 until 2) {
        dut.io.lookup(p).vaddr.poke(0.U)
        dut.io.lookup(p).ASID.poke(0.U)
        dut.io.lookup(p).privilege.poke(0.U)
        dut.io.lookup(p).access.poke(ysyx_26030103_AccessType.Load)
        dut.io.lookup(p).sum.poke(false.B)
        dut.io.lookup(p).mxr.poke(false.B)
      }
      dut.clock.step()
      dut.io.flush.poke(false.B)

      // 同一拍通过两个 refill 端口填入两个不同集合的表项。
      dut.io.fillValid.poke(true.B)
      dut.io.fillVirtualAddress.poke(0x1234.U)
      dut.io.FillASID.poke(7.U)
      dut.io.FillPTE.poke(LeafPTE.U(32.W))
      extra.valid.poke(true.B)
      extra.vaddr.poke(0x5678.U)
      extra.ASID.poke(8.U)
      extra.PTE.poke(OtherLeafPTE.U(32.W))
      dut.clock.step()
      dut.io.fillValid.poke(false.B)
      extra.valid.poke(false.B)

      // 两个查找端口在同一拍分别查询不同 ASID/地址，结果互不干扰。
      dut.io.lookup(0).vaddr.poke(0x1234.U)
      dut.io.lookup(0).ASID.poke(7.U)
      dut.io.lookup(1).vaddr.poke(0x5678.U)
      dut.io.lookup(1).ASID.poke(8.U)
      asBool(dut.io.result(0).hit) should be(true)
      asBool(dut.io.result(1).hit) should be(true)
      asBool(dut.io.result(0).fault) should be(false)
      asBool(dut.io.result(1).fault) should be(false)
      asLong(dut.io.result(0).paddr) should be(Leaf + 0x234L)
      asLong(dut.io.result(1).paddr) should be(BigInt("80004678", 16).toLong)

      // 端口 1 的 ASID 不匹配时只让端口 1 失效，端口 0 仍保持命中。
      dut.io.lookup(1).ASID.poke(9.U)
      asBool(dut.io.result(0).hit) should be(true)
      asBool(dut.io.result(1).hit) should be(false)
      asLong(dut.io.result(0).paddr) should be(Leaf + 0x234L)
    }
  }

  "TLB" should "缓存 4MiB 超级页并可跨 4KiB 地址命中" in {
    simulate(new ysyx_26030103_TLB(
      entries = 4, ways = 1, ASIDBits = 9, globalEnable = true,
      adMode = "fault", superpage = true
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.io.fillValid.poke(false.B)
      dut.io.fillVirtualAddress.poke(0.U)
      dut.io.FillASID.poke(0.U)
      dut.io.FillPTE.poke(0.U)
      dut.io.fillLevel.poke(0.U)
      dut.io.lookup(0).vaddr.poke(0.U)
      dut.io.lookup(0).ASID.poke(0.U)
      dut.io.lookup(0).privilege.poke(0.U)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Load)
      dut.io.lookup(0).sum.poke(false.B)
      dut.io.lookup(0).mxr.poke(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      // PPN[1]=0x201 -> 4MiB 基址 0x80400000。
      dut.io.fillValid.poke(true.B)
      dut.io.fillVirtualAddress.poke("h80501234".U)
      dut.io.FillASID.poke(0.U)
      dut.io.FillPTE.poke((BigInt("201", 16) << 20 | 0xdf).U(32.W))
      dut.io.fillLevel.poke(1.U)
      dut.clock.step()
      dut.io.fillValid.poke(false.B)
      dut.io.lookup(0).vaddr.poke("h80543210".U)
      asBool(dut.io.result(0).hit) should be(true)
      asLong(dut.io.result(0).paddr) should be(BigInt("80543210", 16).toLong)
    }
  }

  "TLB" should "按 pageBits/levels/indexBits 缓存非标准 16MiB 超级页" in {
    // 64KiB 基础页、2 级、每级 256 项 => 16 + 2*8 = 32。
    // fillLevel=1 表示 2^(16+8)=16MiB 超级页，tag 为 vaddr[31:24]。
    simulate(new ysyx_26030103_TLB(
      entries = 4, ways = 1, ASIDBits = 9, globalEnable = true,
      adMode = "fault", superpage = true,
      pageBits = 16, levels = 2, indexBits = 8,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      PTEWidth = 32, PTEPPNHigh = 31
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.io.fillValid.poke(false.B)
      dut.io.fillVirtualAddress.poke(0.U)
      dut.io.FillASID.poke(0.U)
      dut.io.FillPTE.poke(0.U)
      dut.io.fillLevel.poke(0.U)
      dut.io.lookup(0).vaddr.poke(0.U)
      dut.io.lookup(0).ASID.poke(0.U)
      dut.io.lookup(0).privilege.poke(0.U)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Load)
      dut.io.lookup(0).sum.poke(false.B)
      dut.io.lookup(0).mxr.poke(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      // PPN=0x8200（以 64KiB 为基础页）=> 物理基址 0x82000000。
      dut.io.fillValid.poke(true.B)
      dut.io.fillVirtualAddress.poke("h81001234".U)
      dut.io.FillASID.poke(0.U)
      dut.io.FillPTE.poke(((BigInt("8200", 16) << 10) | 0xdf).U(32.W))
      dut.io.fillLevel.poke(1.U)
      dut.clock.step()
      dut.io.fillValid.poke(false.B)
      // 同一 16MiB 超级页的另一处地址应命中，并保留 vaddr[23:0]。
      dut.io.lookup(0).vaddr.poke("h81abcdef".U)
      asBool(dut.io.result(0).hit) should be(true)
      asLong(dut.io.result(0).paddr) should be(BigInt("82abcdef", 16).toLong)
      // 超级页 tag 不匹配的地址不能命中。
      dut.io.lookup(0).vaddr.poke("h82abcdef".U)
      asBool(dut.io.result(0).hit) should be(false)

      // 同一几何下 fillLevel=0 仍按 64KiB 基础页填充。
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.fillValid.poke(true.B)
      dut.io.fillVirtualAddress.poke("h81234567".U)
      dut.io.FillPTE.poke(((BigInt("8200", 16) << 10) | 0xdf).U(32.W))
      dut.io.fillLevel.poke(0.U)
      dut.clock.step()
      dut.io.fillValid.poke(false.B)
      dut.io.lookup(0).vaddr.poke("h81234567".U)
      asBool(dut.io.result(0).hit) should be(true)
      asLong(dut.io.result(0).paddr) should be(BigInt("82004567", 16).toLong)
    }
  }

  "TLB" should "支持 SRAM 存储的一拍查找" in {
    simulate(new ysyx_26030103_TLB(
      entries = 4,
      ways = 1,
      ASIDBits = 9,
      globalEnable = true,
      storage = "sram"
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.io.fillValid.poke(false.B)
      dut.io.fillVirtualAddress.poke(0.U)
      dut.io.FillASID.poke(0.U)
      dut.io.FillPTE.poke(0.U)
      dut.io.fillLevel.poke(0.U)
      dut.io.lookup(0).vaddr.poke(0.U)
      dut.io.lookup(0).ASID.poke(0.U)
      dut.io.lookup(0).privilege.poke(0.U)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Load)
      dut.io.lookup(0).sum.poke(false.B)
      dut.io.lookup(0).mxr.poke(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      dut.io.fillValid.poke(true.B)
      dut.io.fillVirtualAddress.poke(0x1234.U)
      dut.io.FillASID.poke(7.U)
      dut.io.FillPTE.poke(LeafPTE.U(32.W))
      dut.io.fillLevel.poke(0.U)
      dut.clock.step()
      dut.io.fillValid.poke(false.B)

      dut.io.lookup(0).vaddr.poke(0x1234.U)
      dut.io.lookup(0).ASID.poke(7.U)
      dut.clock.step()
      dut.io.result(0).resultValid.peek().litValue == 1 should be(true)
      asBool(dut.io.result(0).hit) should be(true)
      asBool(dut.io.result(0).fault) should be(false)
      asLong(dut.io.result(0).paddr) should be(Leaf + 0x234L)
    }
  }

  "PTW" should "完成两级 Sv32 walk 并返回物理地址" in {
    val memory = new Memory
    simulate(new ysyx_26030103_PTW(
      "fault", XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTEConfig = Rv32PTE,
      PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var response: Option[(BigInt, Boolean, BigInt)] = None

      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(true.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.vaddr.poke(0x1234.U)
      dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      for (_ <- 0 until 20 if response.isEmpty) {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          pendingData = memory.read(asLong(dut.io.bus.request.bits.address))
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        dut.io.bus.response.bits.fault.poke(false.B)
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.response.valid)) {
          response = Some((
            dut.io.response.bits.paddr.peek().litValue,
            asBool(dut.io.response.bits.fault),
            dut.io.response.bits.cause.peek().litValue
          ))
        }
      }

      response.map(_._1) should be(Some(BigInt(Leaf + 0x234L)))
      response.map(_._2) should be(Some(false))
    }
  }

  "PTW" should "PTE cache 命中后不再访问页表总线" in {
    val memory = new Memory
    simulate(new ysyx_26030103_PTW(
      adMode = "fault",
      cacheEnable = true,
      cacheEntries = 16,
      cacheWays = 2,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTEConfig = Rv32PTE, PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var busReads = 0
      var responses = 0

      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def access(): Unit = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.vaddr.poke(0x1234.U)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        var fired = false
        for (_ <- 0 until 30 if !fired) {
          if (!pending && asBool(dut.io.bus.request.valid)) {
            pending = true
            PendingID = asLong(dut.io.bus.request.bits.ID).toInt
            busReads += 1
            pendingData = memory.read(asLong(dut.io.bus.request.bits.address))
          }
          dut.io.bus.response.valid.poke(pending.B)
          dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
          dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
          val ready = asBool(dut.io.bus.response.ready)
          dut.clock.step()
          if (pending && ready) pending = false
          if (asBool(dut.io.response.valid)) {
            responses += 1
            fired = true
          }
        }
        dut.io.request.valid.poke(false.B)
      }

      access()
      access()
      busReads should be(2)
      responses should be(2)
    }
  }

  "PTW" should "支持 Sv32 4MiB 超级页" in {
    val memory = new Memory
    val SuperPTE = PTE(0x80000L, 0xdfL)
    memory.write(Root + 0x0L, SuperPTE)
    simulate(new ysyx_26030103_PTW(
      "fault", superpage = true, fillSuperpage = true,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTEConfig = Rv32PTE,
      PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var busReads = 0
      var result: Option[(BigInt, Boolean, Int)] = None

      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(false.B)
      dut.io.flush.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.vaddr.poke(0x1234.U)
      dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      for (_ <- 0 until 15 if result.isEmpty) {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          busReads += 1
          pendingData = memory.read(asLong(dut.io.bus.request.bits.address))
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.response.valid)) {
          result = Some((
            dut.io.response.bits.paddr.peek().litValue,
            asBool(dut.io.response.bits.fillValid),
            dut.io.response.bits.fillLevel.peek().litValue.toInt
          ))
        }
      }
      busReads should be(1)
      // 默认 2 级里 level0 是 4MiB 超级页，fillLevel 编码为 1。
      result should be(Some((BigInt("80001234", 16), true, 1)))
    }
  }

  "PTW" should "非标准页几何的 fillLevel 与 TLB 超级页 shift 对齐" in {
    // 64KiB 页、2 级、每级 256 项：16 + 2*8 = 32。
    // 根级叶子是 16MiB 超级页，fillLevel 必须是 1（shift=16+8=24）。
    val root = 0x30000000L
    val RootPPN = 0x3000L
    val memory = scala.collection.mutable.Map[Long, BigInt](
      (root + 0x81L * 4L) -> ((BigInt("8200", 16) << 10) | 0xdfL)
    )
    simulate(new ysyx_26030103_PTW(
      adMode = "fault", superpage = true, fillSuperpage = true,
      pageBits = 16, levels = 2, tableEntries = 256,
      XLEN = 32, addressWidth = 32, vaBits = 32, PTEConfig = Rv32PTE,
      PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var result: Option[(BigInt, Boolean, Int)] = None

      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(false.B)
      dut.io.flush.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.vaddr.poke("h81001234".U)
      dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      for (_ <- 0 until 15 if result.isEmpty) {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          pendingData = memory.getOrElse(
            asLong(dut.io.bus.request.bits.address), BigInt(0)
          )
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.response.valid)) {
          result = Some((
            dut.io.response.bits.paddr.peek().litValue,
            asBool(dut.io.response.bits.fillValid),
            dut.io.response.bits.fillLevel.peek().litValue.toInt
          ))
        }
      }
      result should be(Some((BigInt("82001234", 16), true, 1)))
    }
  }

  "PTW" should "支持非 4KiB 基础页和不同页表项数" in {
    // 1KiB 页 + 2048 项/级：10 + 2*11 = 32。
    val localRoot = 0x20000400L
    val localLeafTable = 0x20000800L
    val localLeaf = 0x20000c00L
    val data = scala.collection.mutable.Map[Long, BigInt](
      localRoot -> PTE(0x80002L, 0x1L),
      (localLeafTable + 4L * 4L) -> PTE(0x80003L, 0xdfL)
    )
    simulate(new ysyx_26030103_PTW(
      adMode = "fault", pageBits = 10, levels = 2, tableEntries = 2048,
      XLEN = 32, addressWidth = 32, vaBits = 32, PTEConfig = Rv32PTE,
      PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var result: Option[BigInt] = None
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(false.B)
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.vaddr.poke(0x1234.U)
      dut.io.request.bits.satp.poke((0x80000000L | 0x80001L).U(32.W))
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      for (_ <- 0 until 20 if result.isEmpty) {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          pendingData = data.getOrElse(asLong(dut.io.bus.request.bits.address), BigInt(0))
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.response.valid)) result = Some(dut.io.response.bits.paddr.peek().litValue)
      }
      result should be(Some(BigInt("20000e34", 16)))
    }
  }

  "PTW" should "支持 8 字节 PTE 的两拍读取" in {
    val memory = new Memory(withDefaults = false)
    // 4 级、每级 32 项、8B PTE：12 + 4*5 = 32。
    memory.write(0x80001000L, PTE(0x80002L, 0x1L))
    memory.write(0x80002000L, PTE(0x80003L, 0x1L))
    memory.write(0x80003000L, PTE(0x80004L, 0x1L))
    memory.write(0x80004008L, PTE(0x80005L, 0xdfL))
    simulate(new ysyx_26030103_PTW(
      adMode = "fault", levels = 4, tableEntries = 32, PTESizeBytes = 8,
      XLEN = 32, addressWidth = 32, vaBits = 32
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var result: Option[BigInt] = None
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(false.B)
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.vaddr.poke(0x1234.U)
      dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      for (_ <- 0 until 40 if result.isEmpty) {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          pendingData = memory.read(asLong(dut.io.bus.request.bits.address))
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.response.valid)) result = Some(dut.io.response.bits.paddr.peek().litValue)
      }
      result should be(Some(BigInt("80005234", 16)))
    }
  }

  "PTW" should "hardware A/D 模式写回页表项" in {
    val memory = new Memory
    // 有效叶子，但 A/D 清零。
    memory.write(0x80002004L, PTE(LeafPPN, 0x1fL))
    simulate(new ysyx_26030103_PTW(
      "hardware", XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTEConfig = Rv32PTE,
      PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingWrite = false
      var pendingData = BigInt(0)
      var writeSeen = false
      var response: Option[BigInt] = None

      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(false.B)
      dut.io.flush.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.vaddr.poke(0x1234.U)
      dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      for (_ <- 0 until 20 if response.isEmpty) {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          pendingWrite = asBool(dut.io.bus.request.bits.write)
          val addr = asLong(dut.io.bus.request.bits.address)
          if (pendingWrite) {
            memory.write(addr, dut.io.bus.request.bits.data.peek().litValue)
            pendingData = BigInt(0)
            writeSeen = true
          } else {
            pendingData = memory.read(addr)
          }
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.response.valid)) {
          response = Some(dut.io.response.bits.paddr.peek().litValue)
        }
      }
      writeSeen should be(true)
      response should be(Some(BigInt(Leaf + 0x234L)))
      (memory.read(0x80002004L) & BigInt(0xc0)) should be(BigInt(0x40))
    }
  }

  "PTW" should "支持自定义 PTE 字段位置" in {
    val memory = new Memory
    val cfg = _root_.ysyx_26030103.config.ysyx_26030103_PTEConfig(
      PPNLow = 12,
      PPNHigh = 31
    )
    memory.write(Root + 0x0L, (BigInt(0x80002) << 12) | 1)
    memory.write(0x80002004L, (BigInt(0x80003) << 12) | 0xdf)
    simulate(new ysyx_26030103_PTW("fault", PTEConfig = cfg,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTESizeBytes = 4)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var result: Option[BigInt] = None
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.vaddr.poke(0x1234.U)
      dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      for (_ <- 0 until 20 if result.isEmpty) {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          pendingData = memory.read(asLong(dut.io.bus.request.bits.address))
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.response.valid)) {
          result = Some(dut.io.response.bits.paddr.peek().litValue)
        }
      }
      result should be(Some(BigInt("80003234", 16)))
    }
  }

  "PTW" should "hardware A/D 模式为 store 写回 A 和 D" in {
    val memory = new Memory
    memory.write(0x80002004L, PTE(LeafPPN, 0x1fL))
    simulate(new ysyx_26030103_PTW(
      "hardware", XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTEConfig = Rv32PTE,
      PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var result: Option[BigInt] = None
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Store)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.vaddr.poke(0x1234.U)
      dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      for (_ <- 0 until 20 if result.isEmpty) {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          val addr = asLong(dut.io.bus.request.bits.address)
          if (asBool(dut.io.bus.request.bits.write)) {
            memory.write(addr, dut.io.bus.request.bits.data.peek().litValue)
            pendingData = BigInt(0)
          } else {
            pendingData = memory.read(addr)
          }
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.response.valid)) {
          result = Some(dut.io.response.bits.paddr.peek().litValue)
        }
      }
      result should be(Some(BigInt(Leaf + 0x234L)))
      (memory.read(0x80002004L) & BigInt(0xc0)) should be(BigInt(0xc0))
    }
  }

  "MMU" should "在非标准页大小下完成 PTW 与 TLB 翻译" in {
    val memory = new Memory
    memory.write(0x20000400L, PTE(0x80002L, 0x1L))
    memory.write(0x20000810L, PTE(0x80003L, 0xdfL))
    simulate(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true, TLBEnabled = true, pageSizeBytes = 1024,
      pageTableSizeBytes = 8192, PTWOutstanding = 1
    ))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var result: Option[BigInt] = None
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.flush.poke(false.B)
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.vaddr.poke(0x1234.U)
      dut.io.request.bits.satp.poke((0x80000000L | 0x80001L).U(32.W))
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      for (_ <- 0 until 40 if result.isEmpty) {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          pendingData = memory.read(asLong(dut.io.bus.request.bits.address))
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.response.valid)) result = Some(dut.io.response.bits.paddr.peek().litValue)
      }
      result should be(Some(BigInt("20000e34", 16)))
    }
  }

  "MMU" should "同时调度两个上下文并按请求顺序返回" in {
    val memory = new Memory
    memory.write(0x80002014L, PTE(LeafPPN, 0xdfL))
    simulate(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true, TLBEnabled = false, PTWOutstanding = 2
    ))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      val responses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.ID.poke(0.U)
      dut.io.request.bits.vaddr.poke(0x1234.U)
      dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.clock.step()
      dut.io.request.bits.ID.poke(1.U)
      dut.io.request.bits.vaddr.poke(0x5678.U)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      for (_ <- 0 until 80 if responses.size < 2) {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          pendingData = memory.read(asLong(dut.io.bus.request.bits.address))
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.response.valid)) {
          responses += dut.io.response.bits.paddr.peek().litValue
        }
      }
      responses.toSeq should be(Seq(BigInt("80003234", 16), BigInt("80003678", 16)))
    }
  }

  "MMU" should "miss 后 refill TLB，第二次访问命中且不再走 PTW" in {
    val memory = new Memory
    simulate(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true,
      TLBEnabled = true,
      TLBASIDBits = 9
    ))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var busReads = 0

      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(false.B)
      dut.io.flush.poke(false.B)

      def stepBus(): Unit = {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          busReads += 1
          pendingData = memory.read(asLong(dut.io.bus.request.bits.address))
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
      }

      def access(vaddr: Long): (BigInt, Boolean, BigInt) = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.vaddr.poke(vaddr.U)
        dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.request.bits.privilege.poke(0.U)
        dut.io.request.bits.sum.poke(false.B)
        dut.io.request.bits.mxr.poke(false.B)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        while (!asBool(dut.io.request.ready)) { stepBus() }
        dut.clock.step()
        dut.io.request.valid.poke(false.B)
        var result: Option[(BigInt, Boolean, BigInt)] = None
        for (_ <- 0 until 20 if result.isEmpty) {
          stepBus()
          if (asBool(dut.io.response.valid)) {
            result = Some((
              dut.io.response.bits.paddr.peek().litValue,
              asBool(dut.io.response.bits.fault),
              dut.io.response.bits.cause.peek().litValue
            ))
          }
        }
        dut.io.response.ready.poke(true.B)
        dut.clock.step()
        dut.io.response.ready.poke(false.B)
        result.get
      }

      val first = access(0x1234L)
      first._1 should be(BigInt(Leaf + 0x234L))
      first._2 should be(false)
      busReads should be(2)

      val second = access(0x1234L)
      second._1 should be(BigInt(Leaf + 0x234L))
      second._2 should be(false)
      busReads should be(2)
    }
  }

  "MMU" should "在 unified 双查找端口配置下同拍完成两个 TLB 命中" in {
    val memory = new Memory
    // vaddr=0 的叶子项位于页表偏移 0；默认 Memory 只填了偏移 4 的项。
    memory.write(0x80002000L, LeafPTE)
    simulate(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true,
      TLBEnabled = true,
      TLBOrganization = "unified",
      TLBStorage = "sram",
      utlbLookupPorts = 2,
      refillPorts = 2,
      PTWOutstanding = 2
    ))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var busReads = 0
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(false.B)
      dut.io.flush.poke(false.B)

      def stepBus(): Unit = {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          busReads += 1
          pendingData = memory.read(asLong(dut.io.bus.request.bits.address))
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
      }

      def issue(vaddr: Long): Unit = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.vaddr.poke(vaddr.U)
        while (!asBool(dut.io.request.ready)) { stepBus() }
        dut.clock.step()
        dut.io.request.valid.poke(false.B)
      }

      def waitResponse(): BigInt = {
        var result: Option[BigInt] = None
        for (_ <- 0 until 20 if result.isEmpty) {
          stepBus()
          if (asBool(dut.io.response.valid)) result = Some(dut.io.response.bits.paddr.peek().litValue)
        }
        val value = result.get
        dut.io.response.ready.poke(true.B)
        dut.clock.step()
        dut.io.response.ready.poke(false.B)
        value
      }

      // 先把两页装入 TLB，并让最后一次查找停在 0x0，使下一笔 0x1234
      // 在第一拍处于 SRAM 结果未就绪状态。
      issue(0x1234L)
      waitResponse() should be(BigInt("80003234", 16))
      issue(0x0L)
      waitResponse() should be(BigInt("80003000", 16))
      busReads should be(4)

      // 第一笔先在端口 0 等待 SRAM 结果；第二笔进入端口 1 后两笔同拍
      // 命中，验证两个上下文可以通过双查找端口同时完成。
      issue(0x1234L)
      issue(0x0L)
      val responses = Seq(waitResponse(), waitResponse())
      responses should be(Seq(BigInt("80003234", 16), BigInt("80003000", 16)))
      busReads should be(4)
    }
  }

  it should "在没有 A 位时产生 load page fault" in {
    val memory = new Memory
    memory.write(0x80002004L, PTE(LeafPPN, 0x1L | 0x2L | 0x4L | 0x8L | 0x10L))
    simulate(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true,
      TLBEnabled = false
    ))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(false.B)
      dut.io.flush.poke(false.B)

      def stepBus(): Unit = {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          pendingData = memory.read(asLong(dut.io.bus.request.bits.address))
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
      }

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.vaddr.poke(0x1234.U)
      dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      while (!asBool(dut.io.request.ready)) { stepBus() }
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      var fault = false
      var cause = BigInt(0)
      for (_ <- 0 until 20 if !fault) {
        stepBus()
        if (asBool(dut.io.response.valid)) {
          fault = asBool(dut.io.response.bits.fault)
          cause = dut.io.response.bits.cause.peek().litValue
        }
      }
      fault should be(true)
      cause should be(BigInt(13))
      dut.io.response.ready.poke(true.B)
      dut.clock.step()
    }
  }

  it should "satp.MODE=0 时旁路翻译" in {
    simulate(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true,
      TLBEnabled = true
    ))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(false.B)
      dut.io.flush.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.vaddr.poke(0x12345678.U)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      var done = false
      for (_ <- 0 until 4 if !done) {
        dut.clock.step()
        done = asBool(dut.io.response.valid)
      }
      done should be(true)
      asBool(dut.io.response.bits.fault) should be(false)
      asLong(dut.io.response.bits.paddr) should be(0x12345678L)
      dut.io.response.ready.poke(true.B)
      dut.clock.step()
    }
  }

  "TLB" should "HAS_U=n 时不检查 U 位与 SUM" in {
    simulate(new ysyx_26030103_TLB(
      entries = 4,
      ways = 1,
      ASIDBits = 9,
      globalEnable = true,
      adMode = "fault",
      hasU = false
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.io.fillValid.poke(false.B)
      dut.io.fillVirtualAddress.poke(0.U)
      dut.io.FillASID.poke(0.U)
      dut.io.FillPTE.poke(0.U)
      dut.io.fillLevel.poke(0.U)
      dut.io.lookup(0).vaddr.poke(0.U)
      dut.io.lookup(0).ASID.poke(0.U)
      dut.io.lookup(0).privilege.poke(1.U)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Load)
      dut.io.lookup(0).sum.poke(false.B)
      dut.io.lookup(0).mxr.poke(false.B)
      dut.clock.step()

      // 填入 U=1 的可执行叶子页。
      dut.io.flush.poke(false.B)
      dut.io.fillValid.poke(true.B)
      dut.io.fillVirtualAddress.poke(0x1234.U)
      dut.io.FillASID.poke(0.U)
      dut.io.FillPTE.poke(LeafPTE.U(32.W))
      dut.io.fillLevel.poke(0.U)
      dut.clock.step()
      dut.io.fillValid.poke(false.B)

      // HAS_U=n：S 模式取指和 SUM=0 的数据访问都不再触发 U 位错误。
      dut.io.lookup(0).vaddr.poke(0x1234.U)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Fetch)
      asBool(dut.io.result(0).hit) should be(true)
      asBool(dut.io.result(0).fault) should be(false)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Load)
      dut.io.lookup(0).sum.poke(false.B)
      asBool(dut.io.result(0).fault) should be(false)
      asLong(dut.io.result(0).paddr) should be(Leaf + 0x234L)
    }
  }

  "PTW" should "HAS_U=n 时 U=1 叶子页对 S 模式不产生页错误" in {
    val memory = new Memory
    simulate(new ysyx_26030103_PTW("fault", hasU = false, XLEN = 32, addressWidth = 32, vaBits = 32, levels = 2, tableEntries = 1024, PTEConfig = Rv32PTE, PTESizeBytes = 4)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var response: Option[(BigInt, Boolean)] = None

      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.request.bits.privilege.poke(1.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.vaddr.poke(0x1234.U)
      dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      for (_ <- 0 until 20 if response.isEmpty) {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          pendingData = memory.read(asLong(dut.io.bus.request.bits.address))
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.response.valid)) {
          response = Some((
            dut.io.response.bits.paddr.peek().litValue,
            asBool(dut.io.response.bits.fault)
          ))
        }
      }
      response.map(_._1) should be(Some(BigInt(Leaf + 0x234L)))
      response.map(_._2) should be(Some(false))
    }
  }

  "PTW" should "8B PTE cache 复用低/高字并避免重复页表读" in {
    val memory = new Memory(withDefaults = false)
    // 4 级、每级 32 项、8B PTE：12 + 4*5 = 32。
    memory.write(0x80001000L, PTE(0x80002L, 0x1L))
    memory.write(0x80002000L, PTE(0x80003L, 0x1L))
    memory.write(0x80003000L, PTE(0x80004L, 0x1L))
    memory.write(0x80004008L, PTE(0x80005L, 0xdfL))
    simulate(new ysyx_26030103_PTW(
      adMode = "fault", levels = 4, tableEntries = 32, PTESizeBytes = 8,
      cacheEnable = true, cacheEntries = 8, cacheWays = 8,
      XLEN = 32, addressWidth = 32, vaBits = 32
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var busReads = 0

      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def stepBus(): Unit = {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          busReads += 1
          pendingData = memory.read(asLong(dut.io.bus.request.bits.address))
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
      }

      def access(): BigInt = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.vaddr.poke(0x1234.U)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        while (!asBool(dut.io.request.ready)) { stepBus() }
        dut.clock.step()
        dut.io.request.valid.poke(false.B)
        var result: Option[BigInt] = None
        for (_ <- 0 until 40 if result.isEmpty) {
          stepBus()
          if (asBool(dut.io.response.valid)) {
            result = Some(dut.io.response.bits.paddr.peek().litValue)
          }
        }
        result.get
      }

      access() should be(BigInt("80005234", 16))
      // 4 级 × 低/高两个字。
      busReads should be(8)
      // 第二次走完整 64 位 PTE cache，不再产生任何页表读。
      access() should be(BigInt("80005234", 16))
      busReads should be(8)
    }
  }

  "PTW" should "8B PTE hardware A/D 写回后可命中 cache" in {
    val memory = new Memory(withDefaults = false)
    memory.write(0x80001000L, PTE(0x80002L, 0x1L))
    memory.write(0x80002000L, PTE(0x80003L, 0x1L))
    memory.write(0x80003000L, PTE(0x80004L, 0x1L))
    // A/D 清零，hardware 模式应在低字写回 0xc0。
    memory.write(0x80004008L, PTE(0x80005L, 0x1fL))
    simulate(new ysyx_26030103_PTW(
      adMode = "hardware", levels = 4, tableEntries = 32, PTESizeBytes = 8,
      cacheEnable = true, cacheEntries = 8, cacheWays = 8,
      XLEN = 32, addressWidth = 32, vaBits = 32
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var busReads = 0
      var busWrites = 0

      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Store)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def stepBus(): Unit = {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          val addr = asLong(dut.io.bus.request.bits.address)
          if (asBool(dut.io.bus.request.bits.write)) {
            busWrites += 1
            memory.write(addr, dut.io.bus.request.bits.data.peek().litValue)
            pendingData = BigInt(0)
          } else {
            busReads += 1
            pendingData = memory.read(addr)
          }
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
      }

      def access(): BigInt = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.vaddr.poke(0x1234.U)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        while (!asBool(dut.io.request.ready)) { stepBus() }
        dut.clock.step()
        dut.io.request.valid.poke(false.B)
        var result: Option[BigInt] = None
        for (_ <- 0 until 40 if result.isEmpty) {
          stepBus()
          if (asBool(dut.io.response.valid)) {
            result = Some(dut.io.response.bits.paddr.peek().litValue)
          }
        }
        result.get
      }

      access() should be(BigInt("80005234", 16))
      busWrites should be(1)
      (memory.read(0x80004008L) & BigInt(0xc0)) should be(BigInt(0xc0))
      val readsAfterWrite = busReads
      // cache 中的 PTE 已带 A/D，第二次 store 直接命中。
      access() should be(BigInt("80005234", 16))
      busReads should be(readsAfterWrite)
      busWrites should be(1)
    }
  }

  "PTW" should "自定义 PTE 打开 cache 后仍按配置字段归一化" in {
    val memory = new Memory
    val cfg = _root_.ysyx_26030103.config.ysyx_26030103_PTEConfig(
      PPNLow = 12,
      PPNHigh = 31
    )
    memory.write(Root + 0x0L, (BigInt(0x80002) << 12) | 1)
    memory.write(0x80002004L, (BigInt(0x80003) << 12) | 0xdf)
    simulate(new ysyx_26030103_PTW(
      adMode = "fault", PTEConfig = cfg,
      cacheEnable = true, cacheEntries = 16, cacheWays = 2,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var busReads = 0

      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def stepBus(): Unit = {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          busReads += 1
          pendingData = memory.read(asLong(dut.io.bus.request.bits.address))
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
      }

      def access(): BigInt = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.vaddr.poke(0x1234.U)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        while (!asBool(dut.io.request.ready)) { stepBus() }
        dut.clock.step()
        dut.io.request.valid.poke(false.B)
        var result: Option[BigInt] = None
        for (_ <- 0 until 20 if result.isEmpty) {
          stepBus()
          if (asBool(dut.io.response.valid)) {
            result = Some(dut.io.response.bits.paddr.peek().litValue)
          }
        }
        result.get
      }

      access() should be(BigInt("80003234", 16))
      busReads should be(2)
      // cache 保存原始自定义 PTE，第二次命中后重新归一化，不能把已经
      // 归一化的 32 位标准 PTE 再按自定义位域解析一次。
      access() should be(BigInt("80003234", 16))
      busReads should be(2)
    }
  }

  "PTW" should "超级页打开 cache 后仍返回正确 fillLevel" in {
    val memory = new Memory
    memory.write(Root + 0x0L, PTE(0x80000L, 0xdfL))
    simulate(new ysyx_26030103_PTW(
      adMode = "fault", superpage = true, fillSuperpage = true,
      cacheEnable = true, cacheEntries = 16, cacheWays = 2,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      levels = 2, tableEntries = 1024, PTEConfig = Rv32PTE, PTESizeBytes = 4
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var PendingID = 0
      var pendingData = BigInt(0)
      var busReads = 0

      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.request.bits.privilege.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.sum.poke(false.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def stepBus(): Unit = {
        if (!pending && asBool(dut.io.bus.request.valid)) {
          pending = true
          PendingID = asLong(dut.io.bus.request.bits.ID).toInt
          busReads += 1
          pendingData = memory.read(asLong(dut.io.bus.request.bits.address))
        }
        dut.io.bus.response.valid.poke(pending.B)
        dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
        val ready = asBool(dut.io.bus.response.ready)
        dut.clock.step()
        if (pending && ready) pending = false
      }

      def access(): (BigInt, Int) = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.vaddr.poke(0x1234.U)
        dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        while (!asBool(dut.io.request.ready)) { stepBus() }
        dut.clock.step()
        dut.io.request.valid.poke(false.B)
        var result: Option[(BigInt, Int)] = None
        for (_ <- 0 until 20 if result.isEmpty) {
          stepBus()
          if (asBool(dut.io.response.valid)) {
            result = Some((
              dut.io.response.bits.paddr.peek().litValue,
              dut.io.response.bits.fillLevel.peek().litValue.toInt
            ))
          }
        }
        result.get
      }

      access() should be((BigInt("80001234", 16), 1))
      busReads should be(1)
      access() should be((BigInt("80001234", 16), 1))
      busReads should be(1)
    }
  }

  "MMU" should "follow_tlb/global miss queue 均按请求顺序返回" in {
    val memory = new Memory
    memory.write(0x80002004L, PTE(0x80003L, 0xdfL))
    memory.write(0x80002008L, PTE(0x80004L, 0xdfL))
    memory.write(0x8000200cL, PTE(0x80005L, 0xdfL))
    val base = rv32Profile.copy(
      enabled = true, TLBEnabled = true, PTWOutstanding = 1
    )
    val profiles = Seq(
      // split follow_tlb：I/D 各自一条深度 1 的队列。
      base.copy(TLBOrganization = "split", TLBMissQueueMode = "follow_tlb",
        itlbMissQueueEntries = 1, dtlbMissQueueEntries = 1),
      // unified follow_tlb：只有 UTLB 队列。
      base.copy(TLBOrganization = "unified", TLBMissQueueMode = "follow_tlb",
        utlbMissQueueEntries = 1, utlbLookupPorts = 1),
      // global：fetch/load 共享同一条深度 1 的队列。
      base.copy(TLBMissQueueMode = "global", globalMissQueueEntries = 1)
    )

    profiles.foreach { profile =>
      simulate(new ysyx_26030103_MMU(profile)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        var pending = false
        var PendingID = 0
        var pendingData = BigInt(0)
        val responses = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]

        dut.io.request.valid.poke(false.B)
        dut.io.request.bits.vaddr.poke(0.U)
        dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.request.bits.privilege.poke(0.U)
        dut.io.request.bits.sum.poke(false.B)
        dut.io.request.bits.mxr.poke(false.B)
        dut.io.request.bits.satp.poke(0.U)
        dut.io.bus.request.ready.poke(true.B)
        dut.io.bus.response.valid.poke(false.B)
        dut.io.bus.response.bits.data.poke(0.U)
        dut.io.bus.response.bits.ID.poke(0.U)
        dut.io.bus.response.bits.fault.poke(false.B)
        dut.io.response.ready.poke(true.B)
        dut.io.flush.poke(false.B)
        PendingID = 0

        def stepBus(): Unit = {
          if (!pending && asBool(dut.io.bus.request.valid)) {
            pending = true
            PendingID = asLong(dut.io.bus.request.bits.ID).toInt
            pendingData = memory.read(asLong(dut.io.bus.request.bits.address))
          }
          dut.io.bus.response.valid.poke(pending.B)
          dut.io.bus.response.bits.data.poke(pendingData.U(32.W))
          dut.io.bus.response.bits.ID.poke(PendingID.U(4.W))
          val ready = asBool(dut.io.bus.response.ready)
          if (asBool(dut.io.response.valid)) {
            responses += ((
              dut.io.response.bits.ID.peek().litValue,
              dut.io.response.bits.paddr.peek().litValue
            ))
          }
          dut.clock.step()
          if (pending && ready) pending = false
        }

        def issue(ID: Int, vaddr: Long, fetch: Boolean): Unit = {
          dut.io.request.valid.poke(true.B)
          dut.io.request.bits.ID.poke(ID.U)
          dut.io.request.bits.vaddr.poke(vaddr.U)
          dut.io.request.bits.access.poke(
            if (fetch) ysyx_26030103_AccessType.Fetch
            else ysyx_26030103_AccessType.Load)
          dut.io.request.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
          while (!asBool(dut.io.request.ready)) { stepBus() }
          dut.clock.step()
          dut.io.request.valid.poke(false.B)
        }

        issue(0, 0x1234L, fetch = true)
        issue(1, 0x2234L, fetch = false)
        issue(2, 0x3234L, fetch = true)
        var guard = 0
        while (responses.size < 3 && guard < 300) { stepBus(); guard += 1 }

        responses.map(_._1) should be(Seq(0, 1, 2))
        responses.map(_._2) should be(Seq(
          BigInt("80003234", 16),
          BigInt("80004234", 16),
          BigInt("80005234", 16)
        ))
      }
    }
  }

  "MMU" should "flush 后排空 reqQueue 且不让旧请求在新 satp 下继续翻译" in {
    val profile = rv32Profile.copy(
      enabled = true,
      TLBEnabled = false,
      PTWOutstanding = 1
    )
    simulate(new ysyx_26030103_MMU(profile)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.poke(0.U.asTypeOf(new ysyx_26030103_MMURequest))
      dut.io.bus.request.ready.poke(false.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.poke(0.U.asTypeOf(new ysyx_26030103_PTEBusResponse))
      dut.io.response.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      val pagingSatp = 0x80000000L | RootPPN

      def offer(ID: Int, vaddr: Long, satp: Long): Boolean = {
        dut.io.request.bits.ID.poke(ID.U(4.W))
        dut.io.request.bits.vaddr.poke(vaddr.U(32.W))
        dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.request.bits.privilege.poke(0.U(2.W))
        dut.io.request.bits.sum.poke(false.B)
        dut.io.request.bits.mxr.poke(false.B)
        dut.io.request.bits.satp.poke(satp.U(32.W))
        dut.io.request.valid.poke(true.B)
        val ready = asBool(dut.io.request.ready)
        if (ready) { dut.clock.step() }
        ready
      }

      // 1 个上下文 + 深度 2 的 reqQueue：先让第一笔进入 PTW（不响应），
      // 后续请求就会积压在 reqQueue 中。
      var issued = 0
      while (issued < 6 && offer(issued,
          0x1000L + issued.toLong * 0x100L, pagingSatp)) {
        issued += 1
      }
      dut.io.request.valid.poke(false.B)
      issued should be(3)
      asBool(dut.io.request.ready) should be(false)

      dut.io.flush.poke(true.B)
      // flush 当拍也必须拒绝新请求。
      asBool(dut.io.request.ready) should be(false)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      // 队列里仍有旧请求，flushDrain 期间必须拒绝新请求。
      asBool(dut.io.request.ready) should be(false)

      var drainCycles = 0
      while (!asBool(dut.io.request.ready) && drainCycles < 20) {
        dut.clock.step()
        drainCycles += 1
      }
      withClue(s"reqQueue 未在 flush 后排空（$drainCycles 拍）") {
        asBool(dut.io.request.ready) should be(true)
      }

      // 新请求使用 Bare 模式，只需旁路即可返回；若旧请求仍在队列中，
      // 它会挡在 FIFO 前面并等待页表总线，导致这里等不到新响应。
      val FreshID = 9
      offer(FreshID, 0x2000L, 0L) should be(true)
      dut.io.request.valid.poke(false.B)

      var response: Option[(Long, Long, Boolean)] = None
      var guard = 0
      while (response.isEmpty && guard < 40) {
        if (asBool(dut.io.response.valid)) {
          response = Some((
            asLong(dut.io.response.bits.ID),
            asLong(dut.io.response.bits.paddr),
            asBool(dut.io.response.bits.fault)
          ))
        }
        dut.clock.step()
        guard += 1
      }
      withClue(s"flush 后新请求未返回（$guard 拍）") {
        response should be(Some((FreshID.toLong, 0x2000L, false)))
      }
    }
  }
}
