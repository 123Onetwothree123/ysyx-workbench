package ysyx_26030103.mmu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.circt.stage.ChiselStage
import _root_.ysyx_26030103.config._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** RV64 CUSTOM 大页与深页表测试。
  *
  * 覆盖 2MiB/4MiB 基础页、5 级和 6 级（8 位索引）CUSTOM 页表、
  * 大页超级页/TLB/PTW/权限/A-D，以及 6 级 × 4KiB 页表（66 位 VA）
  * 的明确拒绝和 STANDARD Sv39/Sv48 回归。
  */
class ysyx_26030103_RV64CustomLargePageTest extends AnyFlatSpec with Matchers {
  private val LargePageBits = 21 // 2MiB
  private val LargeLevels = 4
  private val LargeVaBits = LargePageBits + LargeLevels * 9 // 57

  private def customProfile(
      pageSize: Int,
      levels: Int,
      tableSize: Int,
      satpMode: Int,
      vaBits: Int,
      tlb: Boolean = false,
      superPage: Boolean = false,
      adMode: String = "fault",
      ptwArch: String = "independent"
  ): ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile(
      enabled = true,
      xlen = 64,
      geometryMode = "custom",
      customSatpMode = satpMode,
      pageSizeBytes = pageSize,
      levels = levels,
      pteSizeBytes = 8,
      pageTableSizeBytes = tableSize,
      pteFormat = "custom",
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53),
      superpage = superPage,
      adMode = adMode,
      tlbEnabled = tlb,
      tlbAsidBits = 16,
      tlbSuperpage = tlb && superPage,
      ptwArch = ptwArch,
      ptwOutstanding = 2
    )

  private def largeProfile(
      tlb: Boolean = false,
      superPage: Boolean = false,
      adMode: String = "fault",
      ptwArch: String = "independent"
  ): ysyx_26030103_MMUProfile =
    customProfile(2 * 1024 * 1024, LargeLevels, 4096, 10, LargeVaBits,
      tlb, superPage, adMode, ptwArch)

  private def deep5Profile(
      tlb: Boolean = false,
      superPage: Boolean = false,
      ptwArch: String = "independent"
  ): ysyx_26030103_MMUProfile =
    customProfile(4096, 5, 4096, 11, 12 + 5 * 9, tlb, superPage,
      ptwArch = ptwArch)

  private def deep6Profile(): ysyx_26030103_MMUProfile =
    customProfile(4096, 6, 2048, 12, 12 + 6 * 8)

  private val ptePpnMask = (BigInt(1) << 44) - 1
  private def pte64(ppn: BigInt, flags: BigInt): BigInt =
    ((ppn & ptePpnMask) << 10) | flags

  private def asBool(value: Bool): Boolean = value.peek().litValue == 1
  private def asBig(value: UInt): BigInt = value.peek().litValue

  /** 以 32 位字为单位的稀疏页表内存；PTE 固定为 8B。 */
  private class PteMemory {
    val words = scala.collection.mutable.Map.empty[Long, BigInt]
    def writeWord(addr: Long, value: BigInt): Unit =
      words(addr & 0xffffffffffffL) = value & 0xffffffffL
    def readWord(addr: Long): BigInt =
      words.getOrElse(addr & 0xffffffffffffL, BigInt(0))
    def writePte(addr: Long, value: BigInt): Unit = {
      writeWord(addr, value & 0xffffffffL)
      writeWord(addr + 4, (value >> 32) & 0xffffffffL)
    }
    def readPte(addr: Long): BigInt =
      readWord(addr) | (readWord(addr + 4) << 32)
  }

  private class PteBus(val memory: PteMemory) {
    private var pending: Option[(Int, BigInt)] = None
    var reads = 0
    var writes = 0

    def tick(bus: ysyx_26030103_PTEBusIO, clock: Clock): Unit = {
      val reqFire = asBool(bus.req.valid) && asBool(bus.req.ready)
      val reqId = bus.req.bits.id.peek().litValue.toInt
      val reqAddr = asBig(bus.req.bits.addr).toLong
      val reqWrite = asBool(bus.req.bits.write)
      val reqData = asBig(bus.req.bits.data)

      bus.resp.valid.poke(pending.isDefined.B)
      bus.resp.bits.id.poke(pending.map(_._1).getOrElse(0).U(4.W))
      bus.resp.bits.data.poke(pending.map(_._2).getOrElse(BigInt(0)).U(32.W))
      bus.resp.bits.fault.poke(false.B)
      val respReady = asBool(bus.resp.ready)
      clock.step()

      if (pending.isDefined && respReady) { pending = None }
      if (reqFire) {
        if (reqWrite) {
          writes += 1
          memory.writeWord(reqAddr, reqData)
          pending = Some((reqId, BigInt(0)))
        } else {
          reads += 1
          pending = Some((reqId, memory.readWord(reqAddr)))
        }
      }
    }
  }

  private case class WalkResult(paddr: BigInt, fault: Boolean, cause: BigInt,
      fillValid: Boolean, fillPte: BigInt, fillLevel: Int)

  private def initPtw(dut: ysyx_26030103_PTW): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.req.valid.poke(false.B)
    dut.io.req.bits.id.poke(0.U)
    dut.io.req.bits.vaddr.poke(0.U)
    dut.io.req.bits.satp.poke(0.U)
    dut.io.req.bits.privilege.poke(0.U)
    dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
    dut.io.req.bits.sum.poke(false.B)
    dut.io.req.bits.mxr.poke(false.B)
    dut.io.bus.req.ready.poke(true.B)
    dut.io.bus.resp.valid.poke(false.B)
    dut.io.bus.resp.bits.id.poke(0.U)
    dut.io.bus.resp.bits.data.poke(0.U)
    dut.io.bus.resp.bits.fault.poke(false.B)
    dut.io.resp.ready.poke(true.B)
    dut.io.flush.poke(false.B)
  }

  private def runPtw(
      dut: ysyx_26030103_PTW,
      memory: PteMemory,
      vaddr: BigInt,
      satp: BigInt,
      access: UInt = ysyx_26030103_AccessType.Load,
      privilege: Int = 1,
      sum: Boolean = true
  ): (WalkResult, Int, Int) = {
    val bus = new PteBus(memory)
    initPtw(dut)
    dut.io.req.valid.poke(true.B)
    dut.io.req.bits.vaddr.poke(vaddr.U(64.W))
    dut.io.req.bits.satp.poke(satp.U(64.W))
    dut.io.req.bits.privilege.poke(privilege.U)
    dut.io.req.bits.access.poke(access)
    dut.io.req.bits.sum.poke(sum.B)
    var accepted = false
    var guard = 0
    while (!accepted && guard < 50) {
      accepted = asBool(dut.io.req.ready)
      bus.tick(dut.io.bus, dut.clock)
      guard += 1
    }
    dut.io.req.valid.poke(false.B)

    var result: Option[WalkResult] = None
    guard = 0
    while (result.isEmpty && guard < 300) {
      if (asBool(dut.io.resp.valid)) {
        result = Some(WalkResult(
          asBig(dut.io.resp.bits.paddr),
          asBool(dut.io.resp.bits.fault),
          asBig(dut.io.resp.bits.cause),
          asBool(dut.io.resp.bits.fillValid),
          asBig(dut.io.resp.bits.fillPte),
          dut.io.resp.bits.fillLevel.peek().litValue.toInt
        ))
      } else {
        bus.tick(dut.io.bus, dut.clock)
      }
      guard += 1
    }
    (result.get, bus.reads, bus.writes)
  }

  private def initMmu(dut: ysyx_26030103_MMU): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.req.valid.poke(false.B)
    dut.io.req.bits.id.poke(0.U)
    dut.io.req.bits.vaddr.poke(0.U)
    dut.io.req.bits.access.poke(ysyx_26030103_AccessType.Load)
    dut.io.req.bits.privilege.poke(1.U)
    dut.io.req.bits.sum.poke(true.B)
    dut.io.req.bits.mxr.poke(false.B)
    dut.io.req.bits.satp.poke(0.U)
    dut.io.bus.req.ready.poke(true.B)
    dut.io.bus.resp.valid.poke(false.B)
    dut.io.bus.resp.bits.id.poke(0.U)
    dut.io.bus.resp.bits.data.poke(0.U)
    dut.io.bus.resp.bits.fault.poke(false.B)
    dut.io.resp.ready.poke(true.B)
    dut.io.flush.poke(false.B)
  }

  private def runMmu(
      dut: ysyx_26030103_MMU,
      bus: PteBus,
      satp: BigInt,
      vaddr: BigInt,
      access: UInt = ysyx_26030103_AccessType.Load
  ): (Boolean, BigInt) = {
    dut.io.req.valid.poke(true.B)
    dut.io.req.bits.id.poke(1.U)
    dut.io.req.bits.vaddr.poke(vaddr.U(64.W))
    dut.io.req.bits.satp.poke(satp.U(64.W))
    dut.io.req.bits.access.poke(access)
    var accepted = false
    var guard = 0
    while (!accepted && guard < 50) {
      accepted = asBool(dut.io.req.ready)
      bus.tick(dut.io.bus, dut.clock)
      guard += 1
    }
    dut.io.req.valid.poke(false.B)
    var result: Option[(Boolean, BigInt)] = None
    guard = 0
    while (result.isEmpty && guard < 300) {
      if (asBool(dut.io.resp.valid)) {
        result = Some((asBool(dut.io.resp.bits.fault),
          asBig(dut.io.resp.bits.paddr)))
      } else {
        bus.tick(dut.io.bus, dut.clock)
      }
      guard += 1
    }
    result.get
  }

  private def satp(rootPpn: BigInt, mode: Int = 10, asid: Int = 0): BigInt =
    (BigInt(mode) << 60) | (BigInt(asid & 0xffff) << 44) |
      (rootPpn & ((BigInt(1) << 44) - 1))

  private val LargeRootPPN = BigInt("400", 16)
  private val LargeL1PPN = BigInt("401", 16)
  private val LargeL2PPN = BigInt("402", 16)
  private val LargeL3PPN = BigInt("403", 16)
  private val LargeLeafPPN = BigInt("404", 16)
  private def largeTable(ppn: BigInt): Long = (ppn << LargePageBits).toLong
  private def largeVa(off: Long = 0x1234L): BigInt =
    (BigInt(2) << 39) | (BigInt(3) << 30) | (BigInt(4) << 21) | off
  private def buildLargeBase(memory: PteMemory,
      leafFlags: BigInt = BigInt(0xcf)): Unit = {
    memory.writePte(largeTable(LargeRootPPN),
      pte64(LargeL1PPN, BigInt(1)))
    memory.writePte(largeTable(LargeL1PPN) + 2L * 8L,
      pte64(LargeL2PPN, BigInt(1)))
    memory.writePte(largeTable(LargeL2PPN) + 3L * 8L,
      pte64(LargeL3PPN, BigInt(1)))
    memory.writePte(largeTable(LargeL3PPN) + 4L * 8L,
      pte64(LargeLeafPPN, leafFlags))
  }

  private val Deep5RootPPN = BigInt("80001", 16)
  private val Deep5L1PPN = BigInt("80002", 16)
  private val Deep5L2PPN = BigInt("80003", 16)
  private val Deep5L3PPN = BigInt("80004", 16)
  private val Deep5L4PPN = BigInt("80005", 16)
  private val Deep5LeafPPN = BigInt("80006", 16)
  private def table(ppn: BigInt): Long = (ppn << 12).toLong
  private def deep5Va(off: Long = 0x234L): BigInt =
    (BigInt(1) << 48) | (BigInt(2) << 39) | (BigInt(3) << 30) |
      (BigInt(4) << 21) | (BigInt(5) << 12) | off
  private def buildDeep5(memory: PteMemory): Unit = {
    memory.writePte(table(Deep5RootPPN) + 1L * 8L,
      pte64(Deep5L1PPN, BigInt(1)))
    memory.writePte(table(Deep5L1PPN) + 2L * 8L,
      pte64(Deep5L2PPN, BigInt(1)))
    memory.writePte(table(Deep5L2PPN) + 3L * 8L,
      pte64(Deep5L3PPN, BigInt(1)))
    memory.writePte(table(Deep5L3PPN) + 4L * 8L,
      pte64(Deep5L4PPN, BigInt(1)))
    memory.writePte(table(Deep5L4PPN) + 5L * 8L,
      pte64(Deep5LeafPPN, BigInt(0xcf)))
  }

  private val Deep6RootPPN = BigInt("90001", 16)
  private val Deep6L1PPN = BigInt("90002", 16)
  private val Deep6L2PPN = BigInt("90003", 16)
  private val Deep6L3PPN = BigInt("90004", 16)
  private val Deep6L4PPN = BigInt("90005", 16)
  private val Deep6L5PPN = BigInt("90006", 16)
  private val Deep6LeafPPN = BigInt("90007", 16)
  private def deep6Va(off: Long = 0x345L): BigInt =
    (BigInt(1) << 52) | (BigInt(2) << 44) | (BigInt(3) << 36) |
      (BigInt(4) << 28) | (BigInt(5) << 20) | (BigInt(6) << 12) | off
  private def buildDeep6(memory: PteMemory): Unit = {
    memory.writePte(table(Deep6RootPPN) + 1L * 8L,
      pte64(Deep6L1PPN, BigInt(1)))
    memory.writePte(table(Deep6L1PPN) + 2L * 8L,
      pte64(Deep6L2PPN, BigInt(1)))
    memory.writePte(table(Deep6L2PPN) + 3L * 8L,
      pte64(Deep6L3PPN, BigInt(1)))
    memory.writePte(table(Deep6L3PPN) + 4L * 8L,
      pte64(Deep6L4PPN, BigInt(1)))
    memory.writePte(table(Deep6L4PPN) + 5L * 8L,
      pte64(Deep6L5PPN, BigInt(1)))
    memory.writePte(table(Deep6L5PPN) + 6L * 8L,
      pte64(Deep6LeafPPN, BigInt(0xcf)))
  }

  /** 通用页表链：ppns 依次是各级页表页，最后一项是叶子页。 */
  private def buildChain(memory: PteMemory, pageBits: Int, indexBits: Int,
      ppns: Seq[BigInt], indices: Seq[Int],
      leafFlags: BigInt = BigInt(0xcf)): Unit = {
    require(ppns.size == indices.size + 1)
    for (level <- indices.indices) {
      val next = ppns(level + 1)
      val flags = if (level == indices.size - 1) leafFlags else BigInt(1)
      memory.writePte((ppns(level) << pageBits).toLong +
        indices(level).toLong * 8L, pte64(next, flags))
    }
  }

  /** 与 buildChain 对应的 VA：第 0 级使用最高 VPN 字段。 */
  private def chainVa(pageBits: Int, indexBits: Int, indices: Seq[Int],
      off: Long): BigInt = {
    val levels = indices.size
    indices.zipWithIndex.map { case (index, level) =>
      BigInt(index) << (pageBits + (levels - 1 - level) * indexBits)
    }.foldLeft(BigInt(0))(_ + _) + off
  }

  "MMUProfile" should "接受 2MiB/4 级、4MiB/4 级与 1GiB/3 级 CUSTOM 大页" in {
    val p2m = largeProfile()
    p2m.pageBits should be(21)
    p2m.computedAddressBits should be(57)
    p2m.paddrPpnBits should be(43)
    p2m.satpPpnBits should be(43)
    p2m.wellFormed should be(true)

    val p4m = p2m.copy(pageSizeBytes = 4 * 1024 * 1024)
    p4m.pageBits should be(22)
    p4m.computedAddressBits should be(58)
    p4m.paddrPpnBits should be(42)
    p4m.wellFormed should be(true)

    // 页大小上限 1GiB 来自 pageSizeBytes 的有符号 Int；30+3*9=57 位 VA。
    val p1g = customProfile(1 << 30, 3, 4096, 10, 57)
    p1g.pageBits should be(30)
    p1g.paddrPpnBits should be(34)
    p1g.satpPpnBits should be(34)
    p1g.wellFormed should be(true)
  }

  it should "接受 2MiB/5 级、4MiB/6 级与 8 级深页表，并拒绝 6 级/4KiB 与超过 8 级" in {
    // 2MiB 页 + 5 级需要 8 位索引：21 + 5*8 = 61 位 VA。
    val large5 = customProfile(2 * 1024 * 1024, 5, 2048, 13, 61)
    large5.computedAddressBits should be(61)
    large5.wellFormed should be(true)

    // 4MiB 页 + 6 级 + 6 位索引：22 + 6*6 = 58 位 VA。
    val large6 = customProfile(4 * 1024 * 1024, 6, 512, 14, 58)
    large6.computedAddressBits should be(58)
    large6.wellFormed should be(true)

    // 3 位 fillLevel 的最大 8 级：2MiB + 8*5 位索引 = 61 位 VA。
    val deep8 = customProfile(2 * 1024 * 1024, 8, 256, 15, 61)
    deep8.computedAddressBits should be(61)
    deep8.wellFormed should be(true)

    val deep5 = deep5Profile()
    deep5.computedAddressBits should be(57)
    deep5.levels should be(5)
    deep5.wellFormed should be(true)

    val deep6 = deep6Profile()
    deep6.computedAddressBits should be(60)
    deep6.indexBitsPerLevel should be(8)
    deep6.wellFormed should be(true)

    // 6 级 × 9 位索引 + 12 位页内偏移 = 66 位 VA，超过 64 位。
    val sixWide = intercept[IllegalArgumentException] {
      deep5.copy(levels = 6)
    }
    sixWide.getMessage should include("66")

    // 2MiB 页 + 5 级 × 9 位索引同样是 66 位 VA；必须减小索引位宽。
    val largeFiveWide = intercept[IllegalArgumentException] {
      largeProfile().copy(levels = 5)
    }
    largeFiveWide.getMessage should include("66")

    val tooDeep = intercept[IllegalArgumentException] {
      deep5.copy(levels = 9)
    }
    tooDeep.getMessage should include("1..8")
  }

  "PTW" should "完成 2MiB/4 级 CUSTOM 基础页 walk" in {
    val memory = new PteMemory
    buildLargeBase(memory)
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 21, levels = 4, tableEntries = 512,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 57,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val (r, reads, _) = runPtw(dut, memory, largeVa(),
        satp(LargeRootPPN))
      r.fault should be(false)
      r.paddr should be((LargeLeafPPN << 21) | 0x1234L)
      r.fillValid should be(true)
      r.fillLevel should be(0)
      reads should be(8)
    }
  }

  it should "支持 2MiB 基础页上的超级页、对齐检查和 A/D 硬件写回" in {
    val memory = new PteMemory
    val superPpn = BigInt(1) << 27
    memory.writePte(largeTable(LargeRootPPN),
      pte64(LargeL1PPN, BigInt(1)))
    memory.writePte(largeTable(LargeL1PPN) + 2L * 8L,
      pte64(superPpn, BigInt(0xcf)))
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 21, levels = 4, tableEntries = 512,
      pteSizeBytes = 8, superpage = true, fillSuperpage = true,
      XLEN = 64, addressWidth = 64, vaBits = 57,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val v = largeVa()
      val (r, _, _) = runPtw(dut, memory, v, satp(LargeRootPPN))
      r.fault should be(false)
      r.paddr should be((BigInt(1) << 48) |
        (BigInt(3) << 30) | (BigInt(4) << 21) | 0x1234L)
      r.fillLevel should be(2)
      r.fillValid should be(true)

      val bad = new PteMemory
      bad.writePte(largeTable(LargeRootPPN),
        pte64(LargeL1PPN, BigInt(1)))
      bad.writePte(largeTable(LargeL1PPN) + 2L * 8L,
        pte64(superPpn + BigInt(1), BigInt(0xcf)))
      val (f, _, _) = runPtw(dut, bad, v, satp(LargeRootPPN))
      f.fault should be(true)
      f.cause should be(13)
    }

    val adMemory = new PteMemory
    buildLargeBase(adMemory, leafFlags = BigInt(0x1f)) // V R W X U，无 A/D
    simulate(new ysyx_26030103_PTW(
      "hardware", pageBits = 21, levels = 4, tableEntries = 512,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 57,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val (r, _, writes) = runPtw(dut, adMemory, largeVa(),
        satp(LargeRootPPN), ysyx_26030103_AccessType.Store)
      r.fault should be(false)
      writes should be(1)
      val updated = adMemory.readPte(
        largeTable(LargeL3PPN) + 4L * 8L)
      (updated & BigInt(0xc0)) should be(BigInt(0xc0))
      ((updated >> 10) & ptePpnMask) should be(LargeLeafPPN)
    }
  }

  it should "完成 5 级 CUSTOM walk 并拒绝非规范 VA" in {
    val memory = new PteMemory
    buildDeep5(memory)
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 12, levels = 5, tableEntries = 512,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 57,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val (r, reads, _) = runPtw(dut, memory, deep5Va(),
        satp(Deep5RootPPN, mode = 11))
      r.fault should be(false)
      r.paddr should be((Deep5LeafPPN << 12) | 0x234L)
      r.fillValid should be(true)
      r.fillLevel should be(0)
      reads should be(10)

      // vaBits=57，bit57 未按 bit56 符号扩展。
      val (bad, badReads, _) = runPtw(dut, memory,
        BigInt(1) << 57, satp(Deep5RootPPN, mode = 11))
      bad.fault should be(true)
      bad.cause should be(13)
      badReads should be(0)
    }
  }

  it should "完成 6 级/8 位索引 CUSTOM walk" in {
    val memory = new PteMemory
    buildDeep6(memory)
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 12, levels = 6, tableEntries = 256,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 60,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val (r, reads, _) = runPtw(dut, memory, deep6Va(),
        satp(Deep6RootPPN, mode = 12))
      r.fault should be(false)
      r.paddr should be((Deep6LeafPPN << 12) | 0x345L)
      r.fillValid should be(true)
      r.fillLevel should be(0)
      reads should be(12)
    }
  }

  it should "完成 2MiB/5 级、4MiB/6 级、8 级和 1GiB/3 级 CUSTOM walk" in {
    // 2MiB/5 级必须使用 8 位索引（2KiB 页表页），否则 66 位 VA 无法表示。
    val memory5 = new PteMemory
    val ppns5 = (0x500 to 0x505).map(i => BigInt(i))
    val idx5 = Seq(0, 1, 2, 3, 4)
    buildChain(memory5, 21, 8, ppns5, idx5)
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 21, levels = 5, tableEntries = 256,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 61,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val va = chainVa(21, 8, idx5, 0x456L)
      val (r, reads, _) = runPtw(dut, memory5, va,
        satp(ppns5.head, mode = 13))
      r.fault should be(false)
      r.paddr should be((ppns5.last << 21) | 0x456L)
      r.fillValid should be(true)
      r.fillLevel should be(0)
      reads should be(10)
    }

    // 4MiB/6 级 + 6 位索引（512B 页表页）→ 58 位 VA。
    val memory6 = new PteMemory
    val ppns6 = (0x600 to 0x606).map(i => BigInt(i))
    val idx6 = Seq(0, 1, 2, 3, 4, 5)
    buildChain(memory6, 22, 6, ppns6, idx6)
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 22, levels = 6, tableEntries = 64,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 58,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val va = chainVa(22, 6, idx6, 0x567L)
      val (r, reads, _) = runPtw(dut, memory6, va,
        satp(ppns6.head, mode = 14))
      r.fault should be(false)
      r.paddr should be((ppns6.last << 22) | 0x567L)
      r.fillValid should be(true)
      reads should be(12)
    }

    // 3 位 fillLevel 的上限 8 级：2MiB + 5 位索引（256B 页表页）。
    val memory8 = new PteMemory
    val ppns8 = (0x700 to 0x708).map(i => BigInt(i))
    val idx8 = Seq(0, 1, 2, 3, 4, 5, 6, 7)
    buildChain(memory8, 21, 5, ppns8, idx8)
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 21, levels = 8, tableEntries = 32,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 61,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val va = chainVa(21, 5, idx8, 0x678L)
      val (r, reads, _) = runPtw(dut, memory8, va,
        satp(ppns8.head, mode = 15))
      r.fault should be(false)
      r.paddr should be((ppns8.last << 21) | 0x678L)
      r.fillValid should be(true)
      reads should be(16)
    }

    // 页大小上限 1GiB：1GiB 页 + 3 级 + 9 位索引 → 57 位 VA。
    val memoryG = new PteMemory
    val ppnsG = (0x800 to 0x803).map(i => BigInt(i))
    val idxG = Seq(0, 1, 2)
    buildChain(memoryG, 30, 9, ppnsG, idxG)
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 30, levels = 3, tableEntries = 512,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 57,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val va = chainVa(30, 9, idxG, 0x789L)
      val (r, reads, _) = runPtw(dut, memoryG, va,
        satp(ppnsG.head, mode = 10))
      r.fault should be(false)
      r.paddr should be((ppnsG.last << 30) | 0x789L)
      r.fillValid should be(true)
      reads should be(6)
    }
  }


  it should "对 2MiB 页执行权限检查并拒绝越界 PPN" in {
    val memory = new PteMemory
    // V R W A，无 U/D：U 模式访问产生用户页错误，S 模式 store 因 D=0 失败。
    buildLargeBase(memory, leafFlags = BigInt(0x47))
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 21, levels = 4, tableEntries = 512,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 57,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val (user, _, _) = runPtw(dut, memory, largeVa(),
        satp(LargeRootPPN), ysyx_26030103_AccessType.Load,
        privilege = 0, sum = true)
      user.fault should be(true)
      user.cause should be(13)

      val (store, _, _) = runPtw(dut, memory, largeVa(),
        satp(LargeRootPPN), ysyx_26030103_AccessType.Store,
        privilege = 1, sum = true)
      store.fault should be(true)
      store.cause should be(15)
    }

    // 2MiB 页只有低 43 位 PPN 能装入 64 位物理地址；最高位非零必须
    // 产生页错误，不能截断成另一个物理页。
    val overflow = new PteMemory
    buildLargeBase(overflow,
      leafFlags = BigInt(0xcf) | (BigInt(1) << 53))
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 21, levels = 4, tableEntries = 512,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 57,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val (r, _, _) = runPtw(dut, overflow, largeVa(),
        satp(LargeRootPPN))
      r.fault should be(true)
      r.cause should be(13)

      // satp.PPN 的 bit43 无法装入 2MiB 页的 64 位根表地址，必须在
      // 访问页表前报页错误，而不是截断根表基址。
      val (badRoot, reads, _) = runPtw(dut, overflow, largeVa(),
        satp(LargeRootPPN | (BigInt(1) << 43)))
      badRoot.fault should be(true)
      badRoot.cause should be(13)
      reads should be(0)
    }
  }

  "MMU" should "在 2MiB 大页上完成 TLB miss refill 并复用映射" in {
    val memory = new PteMemory
    buildLargeBase(memory)
    simulate(new ysyx_26030103_MMU(largeProfile(tlb = true))) { dut =>
      val bus = new PteBus(memory)
      initMmu(dut)
      val satpValue = satp(LargeRootPPN, asid = 0x1234)
      val va = largeVa()
      runMmu(dut, bus, satpValue, va) should be(
        (false, (LargeLeafPPN << 21) | 0x1234L))
      val readsAfterMiss = bus.reads
      readsAfterMiss should be(8)
      runMmu(dut, bus, satpValue, va) should be(
        (false, (LargeLeafPPN << 21) | 0x1234L))
      // 同一 2MiB 页内的不同偏移必须命中同一个 TLB 表项。
      runMmu(dut, bus, satpValue, va + 0x100) should be(
        (false, (LargeLeafPPN << 21) | 0x1334L))
      bus.reads should be(readsAfterMiss)
    }
  }

  "MMU" should "在 2MiB/5 级/8 位索引几何上完成 TLB refill" in {
    val memory = new PteMemory
    val ppns = (0x500 to 0x505).map(i => BigInt(i))
    val indices = Seq(0, 1, 2, 3, 4)
    buildChain(memory, 21, 8, ppns, indices)
    val profile = customProfile(2 * 1024 * 1024, 5, 2048, 13, 61,
      tlb = true)
    simulate(new ysyx_26030103_MMU(profile)) { dut =>
      val bus = new PteBus(memory)
      initMmu(dut)
      val satpValue = satp(ppns.head, mode = 13)
      val va = chainVa(21, 8, indices, 0x456L)
      runMmu(dut, bus, satpValue, va) should be(
        (false, (ppns.last << 21) | 0x456L))
      val readsAfterMiss = bus.reads
      readsAfterMiss should be(10)
      runMmu(dut, bus, satpValue, va + 0x100) should be(
        (false, (ppns.last << 21) | 0x556L))
      bus.reads should be(readsAfterMiss)
    }
  }

  "MMU" should "在 2MiB 基础页上填充 512GiB 超级页 TLB 表项" in {
    val memory = new PteMemory
    val superPpn = BigInt(1) << 27
    memory.writePte(largeTable(LargeRootPPN),
      pte64(LargeL1PPN, BigInt(1)))
    memory.writePte(largeTable(LargeL1PPN) + 2L * 8L,
      pte64(superPpn, BigInt(0xcf)))
    simulate(new ysyx_26030103_MMU(
      largeProfile(tlb = true, superPage = true))) { dut =>
      val bus = new PteBus(memory)
      initMmu(dut)
      val satpValue = satp(LargeRootPPN)
      val va = largeVa()
      runMmu(dut, bus, satpValue, va) should be(
        (false, (BigInt(1) << 48) |
          (BigInt(3) << 30) | (BigInt(4) << 21) | 0x1234L))
      val readsAfterMiss = bus.reads
      readsAfterMiss should be(4)
      runMmu(dut, bus, satpValue, va + 0x100) should be(
        (false, (BigInt(1) << 48) |
          (BigInt(3) << 30) | (BigInt(4) << 21) | 0x1334L))
      bus.reads should be(readsAfterMiss)
    }
  }

  "STANDARD" should "保持 Sv39/Sv48 几何与三级 walk 回归" in {
    val sv39 = ysyx_26030103_MMUProfile(
      enabled = true,
      xlen = 64,
      levels = 3,
      pteSizeBytes = 8,
      pteFormat = "riscv64",
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )
    sv39.standardSv39 should be(true)
    sv39.satpMode should be(8)
    val sv48 = sv39.copy(levels = 4)
    sv48.standardSv48 should be(true)
    sv48.satpMode should be(9)

    val memory = new PteMemory
    val rootPpn = BigInt("80001", 16)
    val l1Ppn = BigInt("80002", 16)
    val l2Ppn = BigInt("80003", 16)
    val leafPpn = BigInt("80004", 16)
    memory.writePte(table(rootPpn) + 1L * 8L, pte64(l1Ppn, BigInt(1)))
    memory.writePte(table(l1Ppn) + 2L * 8L, pte64(l2Ppn, BigInt(1)))
    memory.writePte(table(l2Ppn) + 3L * 8L,
      pte64(leafPpn, BigInt(0xcf)))
    val va = (BigInt(1) << 30) | (BigInt(2) << 21) |
      (BigInt(3) << 12) | 0x234L
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 12, levels = 3, tableEntries = 512,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 39,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val (r, reads, _) = runPtw(dut, memory, va,
        satp(rootPpn, mode = 8))
      r.fault should be(false)
      r.paddr should be((leafPpn << 12) | 0x234L)
      r.fillLevel should be(0)
      reads should be(6)
    }
  }

  "NPC" should "elaboration 大页/深页表/6 级矩阵" in {
    ChiselStage.emitSystemVerilog(new ysyx_26030103_PTW(
      "fault", pageBits = 21, levels = 4, tableEntries = 512,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 57,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    ))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_PTW(
      "fault", pageBits = 12, levels = 5, tableEntries = 512,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 57,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    ))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_PTW(
      "fault", pageBits = 12, levels = 6, tableEntries = 256,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 60,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    ))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_PTW(
      "fault", pageBits = 21, levels = 5, tableEntries = 256,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 61,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    ))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_PTW(
      "fault", pageBits = 22, levels = 6, tableEntries = 64,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 58,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    ))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_PTW(
      "fault", pageBits = 21, levels = 8, tableEntries = 32,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 61,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    ))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_PTWPipeline(
      outstanding = 2, pageBits = 21, levels = 4, tableEntries = 512,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 57,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    ))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_TLB(
      entries = 4, ways = 1, asidBits = 16, globalEnable = true,
      adMode = "ignore", superpage = true,
      pageBits = 12, levels = 5, indexBits = 9,
      XLEN = 64, addressWidth = 64, vaBits = 57, pteWidth = 64,
      ptePpnHigh = 53
    ))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_MMU(
      largeProfile(tlb = true, superPage = true)))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_MMU(
      customProfile(2 * 1024 * 1024, 5, 2048, 13, 61, tlb = true)))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_MMU(
      customProfile(2 * 1024 * 1024, 8, 256, 15, 61, tlb = true)))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_MMU(
      deep5Profile(tlb = true, superPage = true)))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_MMU(
      largeProfile(tlb = true, ptwArch = "pipelined")))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_MMU(
      deep6Profile().copy(tlbEnabled = false)))
  }
}
