package ysyx_26030103.mmu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** TLB/PTW/MMU 的定向行为测试。
  *
  * 页表放在一个 Scala 侧的稀疏内存里，通过 PTW 总线请求逐拍响应。
  * 这些测试不依赖 SoC，便于单独定位 MMU 的握手、权限和页错误问题。
  */
class ysyx_26030103_MMUBehaviorTest extends AnyFlatSpec with Matchers {
  private val PageShift = 12
  private val RootPPN = 0x80001L
  private val Root = RootPPN << PageShift
  private val LeafPPN = 0x80003L
  private val Leaf = LeafPPN << PageShift

  private def pte(ppn: Long, flags: Long): BigInt =
    ((ppn & 0x3fffffL) << 10) | flags

  private val PointerPTE = pte(0x80002L, 0x1L) // V，非叶子
  private val LeafPTE = pte(LeafPPN, 0xdfL) // V R W X U A D

  private class Memory {
    private val data = scala.collection.mutable.Map[Long, BigInt](
      (Root + 0x0L) -> PointerPTE,
      (0x80002000L + 0x4L) -> LeafPTE
    )
    def read(addr: Long): BigInt = data.getOrElse(addr & 0xffffffffL, BigInt(0))
    def write(addr: Long, value: BigInt): Unit = data(addr & 0xffffffffL) = value
  }

  private def asBool(value: UInt): Boolean = value.peek().litValue == 1
  private def asLong(value: UInt): Long = value.peek().litValue.toLong

  "TLB" should "命中已填充的页，并在 ASID/权限不匹配时失效" in {
    simulate(new ysyx_26030103_TLB(
      entries = 4,
      ways = 1,
      asidBits = 9,
      globalEnable = true,
      adMode = "fault"
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.io.fillValid.poke(false.B)
      dut.io.fillVaddr.poke(0.U)
      dut.io.fillAsid.poke(0.U)
      dut.io.fillPte.poke(0.U)
      dut.io.fillLevel.poke(0.U)
      dut.io.lookup.vaddr.poke(0.U)
      dut.io.lookup.asid.poke(0.U)
      dut.io.lookup.privilege.poke(0.U)
      dut.io.lookup.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.lookup.sum.poke(false.B)
      dut.io.lookup.mxr.poke(false.B)
      dut.clock.step()

      dut.io.flush.poke(false.B)
      dut.io.fillValid.poke(true.B)
      dut.io.fillVaddr.poke(0x1234.U)
      dut.io.fillAsid.poke(7.U)
      dut.io.fillPte.poke(LeafPTE.U(32.W))
      dut.io.fillLevel.poke(0.U)
      dut.clock.step()
      dut.io.fillValid.poke(false.B)

      dut.io.lookup.vaddr.poke(0x1234.U)
      dut.io.lookup.asid.poke(7.U)
      dut.io.lookup.privilege.poke(0.U)
      dut.io.lookup.access.poke(ysyx_26030103_AccessType.Load)
      asBool(dut.io.result.hit) should be(true)
      asBool(dut.io.result.fault) should be(false)
      asLong(dut.io.result.paddr) should be(Leaf + 0x234L)

      // ASID 不同，不应命中（Global=0）。
      dut.io.lookup.asid.poke(8.U)
      dut.io.lookup.vaddr.poke(0x1234.U)
      asBool(dut.io.result.hit) should be(false)

      // U 页的取指在 S 模式必须失败；S 模式数据访问需要 SUM。
      dut.io.lookup.asid.poke(7.U)
      dut.io.lookup.privilege.poke(1.U)
      dut.io.lookup.access.poke(ysyx_26030103_AccessType.Fetch)
      asBool(dut.io.result.hit) should be(true)
      asBool(dut.io.result.fault) should be(true)

      dut.io.lookup.sum.poke(false.B)
      dut.io.lookup.access.poke(ysyx_26030103_AccessType.Load)
      asBool(dut.io.result.fault) should be(true)
      dut.io.lookup.sum.poke(true.B)
      asBool(dut.io.result.fault) should be(false)
    }
  }

  "TLB" should "缓存 4MiB 超级页并可跨 4KiB 地址命中" in {
    simulate(new ysyx_26030103_TLB(
      entries = 4, ways = 1, asidBits = 9, globalEnable = true,
      adMode = "fault", superpage = true
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.io.fillValid.poke(false.B)
      dut.io.fillVaddr.poke(0.U)
      dut.io.fillAsid.poke(0.U)
      dut.io.fillPte.poke(0.U)
      dut.io.fillLevel.poke(0.U)
      dut.io.lookup.vaddr.poke(0.U)
      dut.io.lookup.asid.poke(0.U)
      dut.io.lookup.privilege.poke(0.U)
      dut.io.lookup.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.lookup.sum.poke(false.B)
      dut.io.lookup.mxr.poke(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      // PPN[1]=0x201 -> 4MiB 基址 0x80400000。
      dut.io.fillValid.poke(true.B)
      dut.io.fillVaddr.poke("h80501234".U)
      dut.io.fillAsid.poke(0.U)
      dut.io.fillPte.poke((BigInt("201", 16) << 20 | 0xdf).U(32.W))
      dut.io.fillLevel.poke(1.U)
      dut.clock.step()
      dut.io.fillValid.poke(false.B)
      dut.io.lookup.vaddr.poke("h80543210".U)
      asBool(dut.io.result.hit) should be(true)
      asLong(dut.io.result.paddr) should be(BigInt("80543210", 16).toLong)
    }
  }

  "TLB" should "支持 SRAM 存储的一拍查找" in {
    simulate(new ysyx_26030103_TLB(
      entries = 4,
      ways = 1,
      asidBits = 9,
      globalEnable = true,
      storage = "sram"
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.io.fillValid.poke(false.B)
      dut.io.fillVaddr.poke(0.U)
      dut.io.fillAsid.poke(0.U)
      dut.io.fillPte.poke(0.U)
      dut.io.fillLevel.poke(0.U)
      dut.io.lookup.vaddr.poke(0.U)
      dut.io.lookup.asid.poke(0.U)
      dut.io.lookup.privilege.poke(0.U)
      dut.io.lookup.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.lookup.sum.poke(false.B)
      dut.io.lookup.mxr.poke(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      dut.io.fillValid.poke(true.B)
      dut.io.fillVaddr.poke(0x1234.U)
      dut.io.fillAsid.poke(7.U)
      dut.io.fillPte.poke(LeafPTE.U(32.W))
      dut.io.fillLevel.poke(0.U)
      dut.clock.step()
      dut.io.fillValid.poke(false.B)

      dut.io.lookup.vaddr.poke(0x1234.U)
      dut.io.lookup.asid.poke(7.U)
      dut.clock.step()
      dut.io.result.resultValid.peek().litValue == 1 should be(true)
      asBool(dut.io.result.hit) should be(true)
      asBool(dut.io.result.fault) should be(false)
      asLong(dut.io.result.paddr) should be(Leaf + 0x234L)
    }
  }

  "PTW" should "完成两级 Sv32 walk 并返回物理地址" in {
    val memory = new Memory
    simulate(new ysyx_26030103_PTW("fault")) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var pendingData = BigInt(0)
      var response: Option[(BigInt, Boolean, BigInt)] = None

      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.vaddr.poke(0.U)
      dut.io.req.bits.satp.poke(0.U)
      dut.io.req.bits.privilege.poke(0.U)
      dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.req.bits.sum.poke(false.B)
      dut.io.req.bits.mxr.poke(false.B)
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.data.poke(0.U)
      dut.io.bus.resp.bits.fault.poke(false.B)
      dut.io.resp.ready.poke(true.B)

      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.vaddr.poke(0x1234.U)
      dut.io.req.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.clock.step()
      dut.io.req.valid.poke(false.B)

      for (_ <- 0 until 20 if response.isEmpty) {
        if (!pending && asBool(dut.io.bus.req.valid)) {
          pending = true
          pendingData = memory.read(asLong(dut.io.bus.req.bits.addr))
        }
        dut.io.bus.resp.valid.poke(pending.B)
        dut.io.bus.resp.bits.data.poke(pendingData.U(32.W))
        dut.io.bus.resp.bits.fault.poke(false.B)
        val ready = asBool(dut.io.bus.resp.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.resp.valid)) {
          response = Some((
            dut.io.resp.bits.paddr.peek().litValue,
            asBool(dut.io.resp.bits.fault),
            dut.io.resp.bits.cause.peek().litValue
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
      cacheWays = 2
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var pendingData = BigInt(0)
      var busReads = 0
      var responses = 0

      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.vaddr.poke(0.U)
      dut.io.req.bits.satp.poke(0.U)
      dut.io.req.bits.privilege.poke(0.U)
      dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.req.bits.sum.poke(false.B)
      dut.io.req.bits.mxr.poke(false.B)
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.data.poke(0.U)
      dut.io.bus.resp.bits.fault.poke(false.B)
      dut.io.resp.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def access(): Unit = {
        dut.io.req.valid.poke(true.B)
        dut.io.req.bits.vaddr.poke(0x1234.U)
        dut.io.req.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        var fired = false
        for (_ <- 0 until 30 if !fired) {
          if (!pending && asBool(dut.io.bus.req.valid)) {
            pending = true
            busReads += 1
            pendingData = memory.read(asLong(dut.io.bus.req.bits.addr))
          }
          dut.io.bus.resp.valid.poke(pending.B)
          dut.io.bus.resp.bits.data.poke(pendingData.U(32.W))
          val ready = asBool(dut.io.bus.resp.ready)
          dut.clock.step()
          if (pending && ready) pending = false
          if (asBool(dut.io.resp.valid)) {
            responses += 1
            fired = true
          }
        }
        dut.io.req.valid.poke(false.B)
      }

      access()
      access()
      busReads should be(2)
      responses should be(2)
    }
  }

  "PTW" should "支持 Sv32 4MiB 超级页" in {
    val memory = new Memory
    val superPte = pte(0x80001L, 0xdfL)
    memory.write(Root + 0x0L, superPte)
    simulate(new ysyx_26030103_PTW("fault", superpage = true)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var pendingData = BigInt(0)
      var busReads = 0
      var result: Option[(BigInt, Boolean)] = None

      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.vaddr.poke(0.U)
      dut.io.req.bits.satp.poke(0.U)
      dut.io.req.bits.privilege.poke(0.U)
      dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.req.bits.sum.poke(false.B)
      dut.io.req.bits.mxr.poke(false.B)
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.data.poke(0.U)
      dut.io.bus.resp.bits.fault.poke(false.B)
      dut.io.resp.ready.poke(false.B)
      dut.io.flush.poke(false.B)

      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.vaddr.poke(0x1234.U)
      dut.io.req.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.clock.step()
      dut.io.req.valid.poke(false.B)

      for (_ <- 0 until 15 if result.isEmpty) {
        if (!pending && asBool(dut.io.bus.req.valid)) {
          pending = true
          busReads += 1
          pendingData = memory.read(asLong(dut.io.bus.req.bits.addr))
        }
        dut.io.bus.resp.valid.poke(pending.B)
        dut.io.bus.resp.bits.data.poke(pendingData.U(32.W))
        val ready = asBool(dut.io.bus.resp.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.resp.valid)) {
          result = Some((
            dut.io.resp.bits.paddr.peek().litValue,
            asBool(dut.io.resp.bits.fillValid)
          ))
        }
      }
      busReads should be(1)
      result should be(Some((BigInt("80001234", 16), false)))
    }
  }

  "PTW" should "支持非 4KiB 基础页和不同页表项数" in {
    // 1KiB 页 + 2048 项/级：10 + 2*11 = 32。
    val localRoot = 0x20000400L
    val localLeafTable = 0x20000800L
    val localLeaf = 0x20000c00L
    val data = scala.collection.mutable.Map[Long, BigInt](
      localRoot -> pte(0x80002L, 0x1L),
      (localLeafTable + 4L * 4L) -> pte(0x80003L, 0xdfL)
    )
    simulate(new ysyx_26030103_PTW(
      adMode = "fault", pageBits = 10, levels = 2, tableEntries = 2048
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var pendingData = BigInt(0)
      var result: Option[BigInt] = None
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.vaddr.poke(0.U)
      dut.io.req.bits.satp.poke(0.U)
      dut.io.req.bits.privilege.poke(0.U)
      dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.req.bits.sum.poke(false.B)
      dut.io.req.bits.mxr.poke(false.B)
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.data.poke(0.U)
      dut.io.bus.resp.bits.fault.poke(false.B)
      dut.io.resp.ready.poke(false.B)
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.vaddr.poke(0x1234.U)
      dut.io.req.bits.satp.poke((0x80000000L | 0x80001L).U(32.W))
      dut.clock.step()
      dut.io.req.valid.poke(false.B)
      for (_ <- 0 until 20 if result.isEmpty) {
        if (!pending && asBool(dut.io.bus.req.valid)) {
          pending = true
          pendingData = data.getOrElse(asLong(dut.io.bus.req.bits.addr), BigInt(0))
        }
        dut.io.bus.resp.valid.poke(pending.B)
        dut.io.bus.resp.bits.data.poke(pendingData.U(32.W))
        val ready = asBool(dut.io.bus.resp.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.resp.valid)) result = Some(dut.io.resp.bits.paddr.peek().litValue)
      }
      result should be(Some(BigInt("20000e34", 16)))
    }
  }

  "PTW" should "hardware A/D 模式写回页表项" in {
    val memory = new Memory
    // 有效叶子，但 A/D 清零。
    memory.write(0x80002004L, pte(LeafPPN, 0x1fL))
    simulate(new ysyx_26030103_PTW("hardware")) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var pendingWrite = false
      var pendingData = BigInt(0)
      var writeSeen = false
      var response: Option[BigInt] = None

      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.vaddr.poke(0.U)
      dut.io.req.bits.satp.poke(0.U)
      dut.io.req.bits.privilege.poke(0.U)
      dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.req.bits.sum.poke(false.B)
      dut.io.req.bits.mxr.poke(false.B)
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.data.poke(0.U)
      dut.io.bus.resp.bits.fault.poke(false.B)
      dut.io.resp.ready.poke(false.B)
      dut.io.flush.poke(false.B)

      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.vaddr.poke(0x1234.U)
      dut.io.req.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.clock.step()
      dut.io.req.valid.poke(false.B)

      for (_ <- 0 until 20 if response.isEmpty) {
        if (!pending && asBool(dut.io.bus.req.valid)) {
          pending = true
          pendingWrite = asBool(dut.io.bus.req.bits.write)
          val addr = asLong(dut.io.bus.req.bits.addr)
          if (pendingWrite) {
            memory.write(addr, dut.io.bus.req.bits.data.peek().litValue)
            pendingData = BigInt(0)
            writeSeen = true
          } else {
            pendingData = memory.read(addr)
          }
        }
        dut.io.bus.resp.valid.poke(pending.B)
        dut.io.bus.resp.bits.data.poke(pendingData.U(32.W))
        val ready = asBool(dut.io.bus.resp.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.resp.valid)) {
          response = Some(dut.io.resp.bits.paddr.peek().litValue)
        }
      }
      writeSeen should be(true)
      response should be(Some(BigInt(Leaf + 0x234L)))
      (memory.read(0x80002004L) & BigInt(0xc0)) should be(BigInt(0x40))
    }
  }

  "PTW" should "支持自定义 PTE 字段位置" in {
    val memory = new Memory
    val cfg = _root_.ysyx_26030103.config.ysyx_26030103_PteConfig(
      ppnLow = 12,
      ppnHigh = 31
    )
    memory.write(Root + 0x0L, (BigInt(0x80002) << 12) | 1)
    memory.write(0x80002004L, (BigInt(0x80003) << 12) | 0xdf)
    simulate(new ysyx_26030103_PTW("fault", pteConfig = cfg)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var pendingData = BigInt(0)
      var result: Option[BigInt] = None
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.vaddr.poke(0.U)
      dut.io.req.bits.satp.poke(0.U)
      dut.io.req.bits.privilege.poke(0.U)
      dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.req.bits.sum.poke(false.B)
      dut.io.req.bits.mxr.poke(false.B)
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.data.poke(0.U)
      dut.io.bus.resp.bits.fault.poke(false.B)
      dut.io.resp.ready.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.vaddr.poke(0x1234.U)
      dut.io.req.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.clock.step()
      dut.io.req.valid.poke(false.B)
      for (_ <- 0 until 20 if result.isEmpty) {
        if (!pending && asBool(dut.io.bus.req.valid)) {
          pending = true
          pendingData = memory.read(asLong(dut.io.bus.req.bits.addr))
        }
        dut.io.bus.resp.valid.poke(pending.B)
        dut.io.bus.resp.bits.data.poke(pendingData.U(32.W))
        val ready = asBool(dut.io.bus.resp.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.resp.valid)) {
          result = Some(dut.io.resp.bits.paddr.peek().litValue)
        }
      }
      result should be(Some(BigInt("80003234", 16)))
    }
  }

  "PTW" should "hardware A/D 模式为 store 写回 A 和 D" in {
    val memory = new Memory
    memory.write(0x80002004L, pte(LeafPPN, 0x1fL))
    simulate(new ysyx_26030103_PTW("hardware")) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var pendingData = BigInt(0)
      var result: Option[BigInt] = None
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.vaddr.poke(0.U)
      dut.io.req.bits.satp.poke(0.U)
      dut.io.req.bits.privilege.poke(0.U)
      dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Store)
      dut.io.req.bits.sum.poke(false.B)
      dut.io.req.bits.mxr.poke(false.B)
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.data.poke(0.U)
      dut.io.bus.resp.bits.fault.poke(false.B)
      dut.io.resp.ready.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.vaddr.poke(0x1234.U)
      dut.io.req.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.clock.step()
      dut.io.req.valid.poke(false.B)
      for (_ <- 0 until 20 if result.isEmpty) {
        if (!pending && asBool(dut.io.bus.req.valid)) {
          pending = true
          val addr = asLong(dut.io.bus.req.bits.addr)
          if (asBool(dut.io.bus.req.bits.write)) {
            memory.write(addr, dut.io.bus.req.bits.data.peek().litValue)
            pendingData = BigInt(0)
          } else {
            pendingData = memory.read(addr)
          }
        }
        dut.io.bus.resp.valid.poke(pending.B)
        dut.io.bus.resp.bits.data.poke(pendingData.U(32.W))
        val ready = asBool(dut.io.bus.resp.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.resp.valid)) {
          result = Some(dut.io.resp.bits.paddr.peek().litValue)
        }
      }
      result should be(Some(BigInt(Leaf + 0x234L)))
      (memory.read(0x80002004L) & BigInt(0xc0)) should be(BigInt(0xc0))
    }
  }

  "MMU" should "同时调度两个上下文并按请求顺序返回" in {
    val memory = new Memory
    memory.write(0x80002014L, pte(LeafPPN, 0xdfL))
    simulate(new ysyx_26030103_MMU(ysyx_26030103_MMUProfile(
      enabled = true, tlbEnabled = false, ptwOutstanding = 2
    ))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var pendingData = BigInt(0)
      val responses = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.vaddr.poke(0.U)
      dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.req.bits.privilege.poke(0.U)
      dut.io.req.bits.sum.poke(false.B)
      dut.io.req.bits.mxr.poke(false.B)
      dut.io.req.bits.satp.poke(0.U)
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.data.poke(0.U)
      dut.io.bus.resp.bits.fault.poke(false.B)
      dut.io.resp.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.id.poke(0.U)
      dut.io.req.bits.vaddr.poke(0x1234.U)
      dut.io.req.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      dut.clock.step()
      dut.io.req.bits.id.poke(1.U)
      dut.io.req.bits.vaddr.poke(0x5678.U)
      dut.clock.step()
      dut.io.req.valid.poke(false.B)

      for (_ <- 0 until 80 if responses.size < 2) {
        if (!pending && asBool(dut.io.bus.req.valid)) {
          pending = true
          pendingData = memory.read(asLong(dut.io.bus.req.bits.addr))
        }
        dut.io.bus.resp.valid.poke(pending.B)
        dut.io.bus.resp.bits.data.poke(pendingData.U(32.W))
        val ready = asBool(dut.io.bus.resp.ready)
        dut.clock.step()
        if (pending && ready) pending = false
        if (asBool(dut.io.resp.valid)) {
          responses += dut.io.resp.bits.paddr.peek().litValue
        }
      }
      responses.toSeq should be(Seq(BigInt("80003234", 16), BigInt("80003678", 16)))
    }
  }

  "MMU" should "miss 后 refill TLB，第二次访问命中且不再走 PTW" in {
    val memory = new Memory
    simulate(new ysyx_26030103_MMU(ysyx_26030103_MMUProfile(
      enabled = true,
      tlbEnabled = true,
      tlbAsidBits = 9
    ))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var pendingData = BigInt(0)
      var busReads = 0

      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.vaddr.poke(0.U)
      dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.req.bits.privilege.poke(0.U)
      dut.io.req.bits.sum.poke(false.B)
      dut.io.req.bits.mxr.poke(false.B)
      dut.io.req.bits.satp.poke(0.U)
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.data.poke(0.U)
      dut.io.bus.resp.bits.fault.poke(false.B)
      dut.io.resp.ready.poke(false.B)
      dut.io.flush.poke(false.B)

      def stepBus(): Unit = {
        if (!pending && asBool(dut.io.bus.req.valid)) {
          pending = true
          busReads += 1
          pendingData = memory.read(asLong(dut.io.bus.req.bits.addr))
        }
        dut.io.bus.resp.valid.poke(pending.B)
        dut.io.bus.resp.bits.data.poke(pendingData.U(32.W))
        val ready = asBool(dut.io.bus.resp.ready)
        dut.clock.step()
        if (pending && ready) pending = false
      }

      def access(vaddr: Long): (BigInt, Boolean, BigInt) = {
        dut.io.req.valid.poke(true.B)
        dut.io.req.bits.vaddr.poke(vaddr.U)
        dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
        dut.io.req.bits.privilege.poke(0.U)
        dut.io.req.bits.sum.poke(false.B)
        dut.io.req.bits.mxr.poke(false.B)
        dut.io.req.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
        while (!asBool(dut.io.req.ready)) { stepBus() }
        dut.clock.step()
        dut.io.req.valid.poke(false.B)
        var result: Option[(BigInt, Boolean, BigInt)] = None
        for (_ <- 0 until 20 if result.isEmpty) {
          stepBus()
          if (asBool(dut.io.resp.valid)) {
            result = Some((
              dut.io.resp.bits.paddr.peek().litValue,
              asBool(dut.io.resp.bits.fault),
              dut.io.resp.bits.cause.peek().litValue
            ))
          }
        }
        dut.io.resp.ready.poke(true.B)
        dut.clock.step()
        dut.io.resp.ready.poke(false.B)
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

  it should "在没有 A 位时产生 load page fault" in {
    val memory = new Memory
    memory.write(0x80002004L, pte(LeafPPN, 0x1L | 0x2L | 0x4L | 0x8L | 0x10L))
    simulate(new ysyx_26030103_MMU(ysyx_26030103_MMUProfile(
      enabled = true,
      tlbEnabled = false
    ))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      var pending = false
      var pendingData = BigInt(0)
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.vaddr.poke(0.U)
      dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.req.bits.privilege.poke(0.U)
      dut.io.req.bits.sum.poke(false.B)
      dut.io.req.bits.mxr.poke(false.B)
      dut.io.req.bits.satp.poke(0.U)
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.data.poke(0.U)
      dut.io.bus.resp.bits.fault.poke(false.B)
      dut.io.resp.ready.poke(false.B)
      dut.io.flush.poke(false.B)

      def stepBus(): Unit = {
        if (!pending && asBool(dut.io.bus.req.valid)) {
          pending = true
          pendingData = memory.read(asLong(dut.io.bus.req.bits.addr))
        }
        dut.io.bus.resp.valid.poke(pending.B)
        dut.io.bus.resp.bits.data.poke(pendingData.U(32.W))
        val ready = asBool(dut.io.bus.resp.ready)
        dut.clock.step()
        if (pending && ready) pending = false
      }

      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.vaddr.poke(0x1234.U)
      dut.io.req.bits.satp.poke((0x80000000L | RootPPN).U(32.W))
      while (!asBool(dut.io.req.ready)) { stepBus() }
      dut.clock.step()
      dut.io.req.valid.poke(false.B)

      var fault = false
      var cause = BigInt(0)
      for (_ <- 0 until 20 if !fault) {
        stepBus()
        if (asBool(dut.io.resp.valid)) {
          fault = asBool(dut.io.resp.bits.fault)
          cause = dut.io.resp.bits.cause.peek().litValue
        }
      }
      fault should be(true)
      cause should be(BigInt(13))
      dut.io.resp.ready.poke(true.B)
      dut.clock.step()
    }
  }

  it should "satp.MODE=0 时旁路翻译" in {
    simulate(new ysyx_26030103_MMU(ysyx_26030103_MMUProfile(
      enabled = true,
      tlbEnabled = true
    ))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.vaddr.poke(0.U)
      dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.req.bits.privilege.poke(0.U)
      dut.io.req.bits.sum.poke(false.B)
      dut.io.req.bits.mxr.poke(false.B)
      dut.io.req.bits.satp.poke(0.U)
      dut.io.bus.req.ready.poke(true.B)
      dut.io.bus.resp.valid.poke(false.B)
      dut.io.bus.resp.bits.data.poke(0.U)
      dut.io.bus.resp.bits.fault.poke(false.B)
      dut.io.resp.ready.poke(false.B)
      dut.io.flush.poke(false.B)

      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.vaddr.poke(0x12345678.U)
      dut.clock.step()
      dut.io.req.valid.poke(false.B)
      var done = false
      for (_ <- 0 until 4 if !done) {
        dut.clock.step()
        done = asBool(dut.io.resp.valid)
      }
      done should be(true)
      asBool(dut.io.resp.bits.fault) should be(false)
      asLong(dut.io.resp.bits.paddr) should be(0x12345678L)
      dut.io.resp.ready.poke(true.B)
      dut.clock.step()
    }
  }
}
