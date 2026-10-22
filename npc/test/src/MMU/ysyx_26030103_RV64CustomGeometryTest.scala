package ysyx_26030103.mmu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.circt.stage.ChiselStage
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.exu.ysyx_26030103_RV64CSRHarness
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** RV64 显式 CUSTOM 页几何测试。
  *
  * 覆盖 1KiB/2KiB 页、3/4 级页表、4B/8B PTE、私有 PTE 字段位置、
  * 非标准 satp MODE、CUSTOM 超级页、TLB refill、权限/A/D 以及
  * STANDARD Sv39/Sv48 与 RV32 自定义几何的回归。
  */
class ysyx_26030103_RV64CustomGeometryTest extends AnyFlatSpec with Matchers {
  private val CustomMode = 10

  private def custom1KProfile(
      tlb: Boolean = false,
      superPage: Boolean = false,
      adMode: String = "fault",
      pteConfig: ysyx_26030103_PteConfig =
        ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53),
      pteFormat: String = "custom"
  ): ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile(
      enabled = true,
      xlen = 64,
      geometryMode = "custom",
      customSatpMode = CustomMode,
      pageSizeBytes = 1024,
      levels = 3,
      pteSizeBytes = 8,
      pageTableSizeBytes = 16384,
      pteFormat = pteFormat,
      pteConfig = pteConfig,
      superpage = superPage,
      adMode = adMode,
      tlbEnabled = tlb,
      tlbAsidBits = 16,
      tlbSuperpage = tlb && superPage,
      ptwArch = "independent",
      ptwOutstanding = 2
    )

  private def custom2KProfile(tlb: Boolean = false): ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile(
      enabled = true,
      xlen = 64,
      geometryMode = "custom",
      customSatpMode = 11,
      pageSizeBytes = 2048,
      levels = 4,
      pteSizeBytes = 8,
      pageTableSizeBytes = 4096,
      pteFormat = "custom",
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53),
      superpage = true,
      tlbEnabled = tlb,
      tlbAsidBits = 16,
      tlbSuperpage = tlb,
      ptwArch = "independent",
      ptwOutstanding = 2
    )

  private val RootPPN = BigInt("80001", 16)
  private val L1PPN = BigInt("80002", 16)
  private val L2PPN = BigInt("80003", 16)
  private val LeafPPN = BigInt("80004", 16)
  // 1KiB 页：pageBits=10，indexBits=11。
  private val Root = (RootPPN << 10).toLong
  private val L1 = (L1PPN << 10).toLong
  private val L2 = (L2PPN << 10).toLong
  private val Leaf = (LeafPPN << 10).toLong
  private val CustomVa = BigInt("0000000080001234", 16)

  private def satpCustom(rootPpn: BigInt, mode: Int = CustomMode,
      asid: Int = 0): BigInt =
    (BigInt(mode) << 60) | (BigInt(asid & 0xffff) << 44) |
      (rootPpn & ((BigInt(1) << 44) - 1))

  private def pte64(ppn: BigInt, flags: BigInt): BigInt =
    ((ppn & ((BigInt(1) << 44) - 1)) << 10) | flags

  private def pte32(ppn: BigInt, flags: BigInt): BigInt =
    ((ppn & ((BigInt(1) << 22) - 1)) << 10) | flags

  /** 私有 PTE：标志位 1..6/8/9，PPN 位于 bit55:12。 */
  private val CustomPteConfig = ysyx_26030103_PteConfig(
    pteBits = 64,
    validBit = 1,
    readBit = 2,
    writeBit = 3,
    executeBit = 4,
    userBit = 5,
    globalBit = 6,
    aBit = 8,
    dBit = 9,
    ppnLow = 12,
    ppnHigh = 55
  )
  private def customLeafFlags(a: Boolean = true, d: Boolean = true): BigInt = {
    var flags = (BigInt(1) << 1) | (BigInt(1) << 2) | (BigInt(1) << 3) |
      (BigInt(1) << 4) | (BigInt(1) << 5)
    if (a) flags |= (BigInt(1) << 8)
    if (d) flags |= (BigInt(1) << 9)
    flags
  }
  private def pteCustom(ppn: BigInt, flags: BigInt): BigInt =
    ((ppn & ((BigInt(1) << 44) - 1)) << 12) | flags

  private def asBool(value: Bool): Boolean = value.peek().litValue == 1
  private def asBig(value: UInt): BigInt = value.peek().litValue

  /** 以 32 位字为单位的稀疏页表内存；PTE 宽度由测试显式选择。 */
  private class PteMemory(val pteSize: Int = 8) {
    val words = scala.collection.mutable.Map.empty[Long, BigInt]
    def writeWord(addr: Long, value: BigInt): Unit =
      words(addr & 0xffffffffffffL) = value & 0xffffffffL
    def readWord(addr: Long): BigInt =
      words.getOrElse(addr & 0xffffffffffffL, BigInt(0))
    def writePte(addr: Long, value: BigInt): Unit = {
      writeWord(addr, value & 0xffffffffL)
      if (pteSize == 8) writeWord(addr + 4, (value >> 32) & 0xffffffffL)
    }
    def readPte(addr: Long): BigInt =
      if (pteSize == 8) readWord(addr) | (readWord(addr + 4) << 32)
      else readWord(addr)
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
    while (result.isEmpty && guard < 200) {
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

  private def runPipeline(
      dut: ysyx_26030103_PTWPipeline,
      memory: PteMemory,
      vaddr: BigInt,
      satp: BigInt,
      access: UInt = ysyx_26030103_AccessType.Load,
      privilege: Int = 1,
      sum: Boolean = true
  ): (WalkResult, Int, Int) = {
    val bus = new PteBus(memory)
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
    dut.io.resp.foreach(_.ready.poke(true.B))
    dut.io.flush.poke(false.B)

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
      if (asBool(dut.io.resp(0).valid)) {
        result = Some(WalkResult(
          asBig(dut.io.resp(0).bits.paddr),
          asBool(dut.io.resp(0).bits.fault),
          asBig(dut.io.resp(0).bits.cause),
          asBool(dut.io.resp(0).bits.fillValid),
          asBig(dut.io.resp(0).bits.fillPte),
          dut.io.resp(0).bits.fillLevel.peek().litValue.toInt
        ))
      } else {
        bus.tick(dut.io.bus, dut.clock)
      }
      guard += 1
    }
    (result.get, bus.reads, bus.writes)
  }

  /** 1KiB/3 级基础页：VPN[0]=0，VPN[1]=0x400，VPN[2]=4。 */
  private def build1KTables(memory: PteMemory,
      leafFlags: BigInt = BigInt(0xcf)): Unit = {
    memory.writePte(Root, pte64(L1PPN, BigInt(1)))
    memory.writePte(L1 + 0x400L * 8L, pte64(L2PPN, BigInt(1)))
    memory.writePte(L2 + 4L * 8L, pte64(LeafPPN, leafFlags))
  }

  private def buildCustomTables(memory: PteMemory,
      leafFlags: BigInt = customLeafFlags()): Unit = {
    memory.writePte(Root, pteCustom(L1PPN, BigInt(1) << 1))
    memory.writePte(L1 + 0x400L * 8L, pteCustom(L2PPN, BigInt(1) << 1))
    memory.writePte(L2 + 4L * 8L, pteCustom(LeafPPN, leafFlags))
  }

  "MMUProfile" should "接受 RV64 CUSTOM 1KiB/3 级/2048 项几何" in {
    val p = custom1KProfile()
    p.customGeometry should be(true)
    p.nonStandard should be(true)
    p.computedAddressBits should be(43)
    p.tableEntries should be(2048)
    p.indexBitsPerLevel should be(11)
    p.satpMode should be(10)
    p.wellFormed should be(true)
    p.describe should include("non-standard")
  }

  it should "接受 RV64 CUSTOM 2KiB/4 级几何和非标准 satp MODE" in {
    val p = custom2KProfile()
    p.computedAddressBits should be(47)
    p.satpMode should be(11)
    p.wellFormed should be(true)
  }

  it should "在 STANDARD 模式下仍拒绝 RV64 非标准几何" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_MMUProfile(
        enabled = true,
        xlen = 64,
        pageSizeBytes = 1024,
        levels = 3,
        pteSizeBytes = 8,
        pageTableSizeBytes = 16384,
        pteFormat = "custom",
        pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
      )
    }
    error.getMessage should include("Sv39")
  }

  it should "拒绝把标准 satp 8/9 用于 CUSTOM" in {
    val error = intercept[IllegalArgumentException] {
      custom1KProfile().copy(customSatpMode = 8)
    }
    error.getMessage should include("satp MODE")
  }

  it should "拒绝超过 64 位地址空间或 PPN 无法容纳的 CUSTOM 几何" in {
    val tooWide = intercept[IllegalArgumentException] {
      custom1KProfile().copy(
        pageSizeBytes = 2 * 1024 * 1024,
        levels = 3,
        pageTableSizeBytes = 16384
      )
    }
    tooWide.getMessage should include("无法表达")

    val tooManyLevels = intercept[IllegalArgumentException] {
      custom2KProfile().copy(levels = 5)
    }
    tooManyLevels.getMessage should include("MMU_LEVELS")
  }

  it should "拒绝把私有 PTE 字段位置标成 riscv64" in {
    val error = intercept[IllegalArgumentException] {
      custom1KProfile(pteConfig = CustomPteConfig, pteFormat = "riscv64")
    }
    error.getMessage should include("PTE")
  }

  it should "拒绝在 RV32 上使用 CUSTOM 模式" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_MMUProfile(
        enabled = true,
        xlen = 32,
        geometryMode = "custom",
        pageSizeBytes = 1024,
        levels = 2,
        pageTableSizeBytes = 8192
      )
    }
    error.getMessage should include("RV64")
  }

  it should "保持 STANDARD Sv39/Sv48 与 RV32 自定义几何回归" in {
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
    sv39.standardSatpMode should be(true)
    val sv48 = sv39.copy(levels = 4)
    sv48.standardSv48 should be(true)
    sv48.satpMode should be(9)
    // RV32 既有自定义 32 位几何仍按 Sv32 模式 1 工作。
    val rv32 = ysyx_26030103_MMUProfile(
      enabled = true,
      xlen = 32,
      pageSizeBytes = 1024,
      levels = 2,
      pageTableSizeBytes = 8192
    )
    rv32.computedAddressBits should be(32)
    rv32.satpMode should be(1)
  }

  it should "fromEnv 解析 CUSTOM 模式与 satp MODE" in {
    val env = Map(
      "MMU_ENABLED" -> "1",
      "MMU_GEOMETRY_MODE" -> "custom",
      "MMU_CUSTOM_SATP_MODE" -> "12",
      "MMU_PAGE_SIZE_BYTES" -> "2048",
      "MMU_LEVELS" -> "4",
      "MMU_PTE_SIZE_BYTES" -> "8",
      "MMU_PAGE_TABLE_SIZE_BYTES" -> "4096",
      "MMU_PTE_FORMAT" -> "custom"
    )
    val p = ysyx_26030103_MMUProfile.fromEnv(env.get, xlen = 64)
    p.geometryMode should be("custom")
    p.satpMode should be(12)
    p.computedAddressBits should be(47)
    p.nonStandard should be(true)
  }

  "CSR" should "只接受 CUSTOM 的 satp MODE=10 并拒绝标准 8/9" in {
    simulate(new ysyx_26030103_RV64CSRHarness(custom1KProfile())) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.enable.poke(false.B)
      dut.io.isCsrrw.poke(false.B)
      dut.io.isCsrrs.poke(false.B)
      dut.io.isCsrrc.poke(false.B)
      dut.io.isEcall.poke(false.B)
      dut.io.isEbreak.poke(false.B)
      dut.io.isMret.poke(false.B)
      dut.io.isSret.poke(false.B)
      dut.io.isSfenceVma.poke(false.B)
      dut.io.csrAddr.poke(0.U)
      dut.io.rs1.poke(0.U)
      dut.io.rs1Data.poke(0.U)
      dut.io.pc.poke(0.U)
      dut.io.trapTval.poke(0.U)
      dut.io.trapValid.poke(false.B)
      dut.io.trapCause.poke(0.U)
      dut.io.memTrap.poke(false.B)

      def writeSatp(value: BigInt): BigInt = {
        dut.io.csrAddr.poke(0x180.U)
        dut.io.rs1.poke(1.U)
        dut.io.rs1Data.poke(value.U(64.W))
        dut.io.enable.poke(true.B)
        dut.io.isCsrrw.poke(true.B)
        dut.clock.step()
        dut.io.enable.poke(false.B)
        dut.io.isCsrrw.poke(false.B)
        dut.io.satp.peek().litValue
      }

      val customSatp = (BigInt(10) << 60) | (BigInt(0x1234) << 44) |
        BigInt("80001", 16)
      writeSatp(customSatp) should be(customSatp)
      // CUSTOM 几何下标准 Sv39 MODE=8 必须收敛到 Bare(0)。
      val sv39 = (BigInt(8) << 60) | (BigInt(0x1234) << 44) |
        BigInt("80001", 16)
      writeSatp(sv39) should be(sv39 & ~(BigInt(0xf) << 60))
    }
  }

  "PTW" should "完成 1KiB/3 级 CUSTOM walk" in {
    val memory = new PteMemory()
    build1KTables(memory)
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 10, levels = 3, tableEntries = 2048,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 43,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val (r, reads, _) = runPtw(dut, memory, CustomVa,
        satpCustom(RootPPN, asid = 0x1234))
      r.fault should be(false)
      r.paddr should be(Leaf + 0x234)
      r.fillValid should be(true)
      r.fillLevel should be(0)
      reads should be(6)
    }
  }

  it should "在 CUSTOM 几何下检查权限并给出正确 cause" in {
    val memory = new PteMemory()
    build1KTables(memory, leafFlags = BigInt(0x53)) // V R U A
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 10, levels = 3, tableEntries = 2048,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 43,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val satp = satpCustom(RootPPN)
      val loadNoSum = runPtw(dut, memory, CustomVa, satp,
        ysyx_26030103_AccessType.Load, privilege = 1, sum = false)._1
      loadNoSum.fault should be(true)
      loadNoSum.cause should be(13)

      val loadSum = runPtw(dut, memory, CustomVa, satp,
        ysyx_26030103_AccessType.Load, privilege = 1, sum = true)._1
      loadSum.fault should be(false)

      val store = runPtw(dut, memory, CustomVa, satp,
        ysyx_26030103_AccessType.Store, privilege = 1, sum = true)._1
      store.fault should be(true)
      store.cause should be(15)

      val fetch = runPtw(dut, memory, CustomVa, satp,
        ysyx_26030103_AccessType.Fetch, privilege = 1, sum = true)._1
      fetch.fault should be(true)
      fetch.cause should be(12)
    }
  }

  it should "按私有 PTE 字段位置完成 A/D 硬件写回" in {
    val memory = new PteMemory()
    // 叶子初始无 A/D，由 PTW 按私有字段位置写回。
    buildCustomTables(memory, customLeafFlags(a = false, d = false))
    simulate(new ysyx_26030103_PTW(
      "hardware", pageBits = 10, levels = 3, tableEntries = 2048,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 43,
      pteConfig = CustomPteConfig
    )) { dut =>
      val (r, _, writes) = runPtw(dut, memory, CustomVa,
        satpCustom(RootPPN), ysyx_26030103_AccessType.Store,
        privilege = 1, sum = true)
      r.fault should be(false)
      r.paddr should be(Leaf + 0x234)
      writes should be(1)
      val updated = memory.readPte(L2 + 4L * 8L)
      (updated & (BigInt(1) << 8)) should be(BigInt(1) << 8)
      (updated & (BigInt(1) << 9)) should be(BigInt(1) << 9)
      // 其余 PPN 字段必须保持不变。
      (updated >> 12) should be(LeafPPN)
    }
  }

  it should "在 pipelined PTW 下支持 CUSTOM 私有 PTE 与 A/D 写回" in {
    val memory = new PteMemory()
    buildCustomTables(memory, customLeafFlags(a = false, d = false))
    simulate(new ysyx_26030103_PTWPipeline(
      outstanding = 2, adMode = "hardware",
      pageBits = 10, levels = 3, tableEntries = 2048,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 43,
      pteConfig = CustomPteConfig
    )) { dut =>
      val (r, _, writes) = runPipeline(dut, memory, CustomVa,
        satpCustom(RootPPN), ysyx_26030103_AccessType.Store,
        privilege = 1, sum = true)
      r.fault should be(false)
      r.paddr should be(Leaf + 0x234)
      writes should be(1)
      val updated = memory.readPte(L2 + 4L * 8L)
      (updated & BigInt(0x300)) should be(BigInt(0x300))
    }
  }

  it should "支持 CUSTOM 1KiB 页的 2MiB 超级页并对齐检查" in {
    val memory = new PteMemory()
    val superPpn = BigInt("80000", 16) // 低 11 位为 0
    val superVa = (BigInt(1) << 32) | BigInt("1234567", 16)
    memory.writePte(Root + 8L, pte64(L1PPN, BigInt(1)))
    memory.writePte(L1 + 9L * 8L, pte64(superPpn, BigInt(0xcf)))
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 10, levels = 3, tableEntries = 2048,
      pteSizeBytes = 8, superpage = true, fillSuperpage = true,
      XLEN = 64, addressWidth = 64, vaBits = 43,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val (r, _, _) = runPtw(dut, memory, superVa, satpCustom(RootPPN))
      r.fault should be(false)
      // 2MiB 超级页偏移是 VA[20:0]，因此 0x1_1234567 -> 0x20034567。
      r.paddr should be(BigInt("20034567", 16))
      r.fillLevel should be(1)
      r.fillValid should be(true)

      val bad = new PteMemory()
      bad.writePte(Root + 8L, pte64(L1PPN, BigInt(1)))
      bad.writePte(L1 + 9L * 8L, pte64(BigInt("80010", 16), BigInt(0xcf)))
      val (f, _, _) = runPtw(dut, bad, superVa, satpCustom(RootPPN))
      f.fault should be(true)
      f.cause should be(13)
    }
  }

  it should "拒绝 CUSTOM 非规范虚拟地址且不访问页表" in {
    val memory = new PteMemory()
    build1KTables(memory)
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 10, levels = 3, tableEntries = 2048,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 43,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val badVa = BigInt(1) << 43 // 有效位为 42，bit43 未符号扩展
      val (r, reads, _) = runPtw(dut, memory, badVa, satpCustom(RootPPN))
      r.fault should be(true)
      r.cause should be(13)
      reads should be(0)
    }
  }

  it should "支持 CUSTOM 4B PTE walk" in {
    val memory = new PteMemory(pteSize = 4)
    val rootPpn = BigInt("20001", 16)
    val l1Ppn = BigInt("20002", 16)
    val l2Ppn = BigInt("20003", 16)
    val leafPpn = BigInt("20004", 16)
    val root = (rootPpn << 10).toLong
    val l1 = (l1Ppn << 10).toLong
    val l2 = (l2Ppn << 10).toLong
    memory.writePte(root, pte32(l1Ppn, BigInt(1)))
    memory.writePte(l1 + 0x400L * 4L, pte32(l2Ppn, BigInt(1)))
    memory.writePte(l2 + 4L * 4L, pte32(leafPpn, BigInt(0xcf)))
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 10, levels = 3, tableEntries = 2048,
      pteSizeBytes = 4, XLEN = 64, addressWidth = 64, vaBits = 43,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 32, ppnHigh = 31)
    )) { dut =>
      val (r, reads, _) = runPtw(dut, memory, CustomVa,
        satpCustom(rootPpn))
      r.fault should be(false)
      r.paddr should be((leafPpn << 10) + 0x234)
      reads should be(3)
      r.fillValid should be(true)
    }
  }

  "MMU" should "在 CUSTOM 1KiB 私有 PTE 下完成 TLB miss refill 并拒绝标准 MODE" in {
    val memory = new PteMemory()
    buildCustomTables(memory)
    val profile = custom1KProfile(tlb = true, pteConfig = CustomPteConfig)
    simulate(new ysyx_26030103_MMU(profile)) { dut =>
      val bus = new PteBus(memory)
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

      def access(useSatp: BigInt): (Boolean, BigInt) = {
        dut.io.req.valid.poke(true.B)
        dut.io.req.bits.id.poke(1.U)
        dut.io.req.bits.vaddr.poke(CustomVa.U(64.W))
        dut.io.req.bits.satp.poke(useSatp.U(64.W))
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
        while (result.isEmpty && guard < 200) {
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

      access(satpCustom(RootPPN, asid = 0x1234)) should be((false, Leaf + 0x234))
      val readsAfterMiss = bus.reads
      readsAfterMiss should be(6)
      access(satpCustom(RootPPN, asid = 0x1234)) should be((false, Leaf + 0x234))
      bus.reads should be(readsAfterMiss)
      // CUSTOM 几何下写入标准 Sv39 的 MODE=8 必须页错误，且不访问页表。
      val (fault, _) = access(satpCustom(RootPPN, mode = 8))
      fault should be(true)
      bus.reads should be(readsAfterMiss)
    }
  }

  "MMU" should "在 CUSTOM 超级页上完成 TLB fill 并命中" in {
    val memory = new PteMemory()
    val superPpn = BigInt("80000", 16)
    val superVa = (BigInt(1) << 32) | BigInt("1234567", 16)
    memory.writePte(Root + 8L, pte64(L1PPN, BigInt(1)))
    memory.writePte(L1 + 9L * 8L, pte64(superPpn, BigInt(0xcf)))
    val profile = custom1KProfile(tlb = true, superPage = true)
    simulate(new ysyx_26030103_MMU(profile)) { dut =>
      val bus = new PteBus(memory)
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

      def access(vaddr: BigInt): BigInt = {
        dut.io.req.valid.poke(true.B)
        dut.io.req.bits.id.poke(1.U)
        dut.io.req.bits.vaddr.poke(vaddr.U(64.W))
        dut.io.req.bits.satp.poke(satpCustom(RootPPN).U(64.W))
        var accepted = false
        var guard = 0
        while (!accepted && guard < 50) {
          accepted = asBool(dut.io.req.ready)
          bus.tick(dut.io.bus, dut.clock)
          guard += 1
        }
        dut.io.req.valid.poke(false.B)
        var result: Option[BigInt] = None
        guard = 0
        while (result.isEmpty && guard < 200) {
          if (asBool(dut.io.resp.valid)) {
            asBool(dut.io.resp.bits.fault) should be(false)
            result = Some(asBig(dut.io.resp.bits.paddr))
          } else {
            bus.tick(dut.io.bus, dut.clock)
          }
          guard += 1
        }
        result.get
      }

      access(superVa) should be(BigInt("20034567", 16))
      val readsAfterMiss = bus.reads
      readsAfterMiss should be(4)
      access(superVa + 0x100) should be(BigInt("20034667", 16))
      bus.reads should be(readsAfterMiss)
    }
  }

  "TLB" should "支持 CUSTOM 64 位 VA 根级超级页（shift=46）" in {
    simulate(new ysyx_26030103_TLB(
      entries = 4, ways = 1, asidBits = 16, globalEnable = true,
      adMode = "ignore", superpage = true,
      pageBits = 10, levels = 3, indexBits = 18,
      XLEN = 64, addressWidth = 64, vaBits = 64, pteWidth = 64,
      ptePpnHigh = 53
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.io.fillValid.poke(false.B)
      dut.io.fillVaddr.poke(0.U)
      dut.io.fillAsid.poke(0.U)
      dut.io.fillPte.poke(0.U(64.W))
      dut.io.fillLevel.poke(0.U)
      dut.io.lookup(0).vaddr.poke(0.U)
      dut.io.lookup(0).asid.poke(0.U)
      dut.io.lookup(0).privilege.poke(1.U)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Load)
      dut.io.lookup(0).sum.poke(true.B)
      dut.io.lookup(0).mxr.poke(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      val vaddr = BigInt("8000000012345678", 16)
      dut.io.fillValid.poke(true.B)
      dut.io.fillVaddr.poke(vaddr.U(64.W))
      dut.io.fillAsid.poke(0.U)
      dut.io.fillPte.poke(pte64(BigInt(0), BigInt(0xcf)).U(64.W))
      dut.io.fillLevel.poke(2.U)
      dut.clock.step()
      dut.io.fillValid.poke(false.B)

      dut.io.lookup(0).vaddr.poke(vaddr.U(64.W))
      asBool(dut.io.result(0).hit) should be(true)
      // 3 级/18 位索引/10 位页内偏移：根级叶子覆盖 2^46 字节。
      asBig(dut.io.result(0).paddr) should be(
        vaddr & ((BigInt(1) << 46) - 1))
    }
  }

  "NPC" should "elaboration RV64 CUSTOM 页几何矩阵" in {
    ChiselStage.emitSystemVerilog(new ysyx_26030103_PTW(
      "fault", pageBits = 10, levels = 3, tableEntries = 2048,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 43,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    ))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_PTW(
      "fault", pageBits = 11, levels = 4, tableEntries = 512,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 47,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    ))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_PTWPipeline(
      outstanding = 2, pageBits = 10, levels = 3, tableEntries = 2048,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 43,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    ))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_MMU(custom1KProfile(tlb = true)))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_MMU(custom2KProfile(tlb = true)))
    ChiselStage.emitSystemVerilog(new ysyx_26030103_MMU(
      custom1KProfile(tlb = true).copy(ptwArch = "pipelined")))
    ChiselStage.emitSystemVerilog(new _root_.ysyx_26030103.ysyx_26030103(
      ysyx_26030103_NPCConfig(
        XLEN = 64,
        AddressWidth = 64,
        ICacheEnable = false,
        DCacheEnable = false,
        MMU = custom1KProfile(tlb = true, superPage = true)
      )
    ))
  }
}
