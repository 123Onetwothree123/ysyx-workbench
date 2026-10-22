package ysyx_26030103.mmu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.circt.stage.ChiselStage
import _root_.ysyx_26030103.config._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** RV64 Sv39/Sv48 MMU 定向测试。
  *
  * 页表通过 32 位页表总线逐字读取：8B PTE 的低/高字分别位于 addr 和
  * addr+4。所有 PTE/PPN/地址都使用真实 64 位布局，覆盖三级 walk、
  * 页错误、超级页、A/D 和 TLB refill。
  */
class ysyx_26030103_RV64MMUTest extends AnyFlatSpec with Matchers {
  private val PageShift = 12
  private val RootPPN = BigInt("80001", 16)
  private val Root = (RootPPN << PageShift).toLong
  private val LeafPPN = BigInt("80004", 16)
  private val Leaf = (LeafPPN << PageShift).toLong

  private def pte(ppn: BigInt, flags: Long): BigInt =
    ((ppn & ((BigInt(1) << 44) - 1)) << 10) | BigInt(flags)

  private def satpSv39(rootPpn: BigInt, asid: Int = 0): BigInt =
    (BigInt(8) << 60) | (BigInt(asid & 0xffff) << 44) | (rootPpn & ((BigInt(1) << 44) - 1))

  private def satpSv48(rootPpn: BigInt, asid: Int = 0): BigInt =
    (BigInt(9) << 60) | (BigInt(asid & 0xffff) << 44) | (rootPpn & ((BigInt(1) << 44) - 1))

  private def sv39Profile(
      tlb: Boolean = false,
      superPage: Boolean = false,
      adMode: String = "fault"
  ): ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile(
      enabled = true,
      xlen = 64,
      pageSizeBytes = 4096,
      levels = 3,
      pteSizeBytes = 8,
      pageTableSizeBytes = 4096,
      pteFormat = "riscv64",
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53),
      superpage = superPage,
      adMode = adMode,
      tlbEnabled = tlb,
      tlbAsidBits = 16,
      tlbSuperpage = tlb && superPage,
      ptwArch = "independent",
      ptwOutstanding = 2
    )

  private def sv48Profile(tlb: Boolean = false): ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile(
      enabled = true,
      xlen = 64,
      pageSizeBytes = 4096,
      levels = 4,
      pteSizeBytes = 8,
      pageTableSizeBytes = 4096,
      pteFormat = "riscv64",
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53),
      superpage = true,
      tlbEnabled = tlb,
      tlbAsidBits = 16,
      tlbSuperpage = tlb,
      ptwArch = "independent",
      ptwOutstanding = 2
    )

  private def asBool(value: Bool): Boolean = value.peek().litValue == 1
  private def asBig(value: UInt): BigInt = value.peek().litValue

  /** 以 32 位字为单位的稀疏页表内存。 */
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

  /** 页表总线模型：可记录读/写次数，响应延迟一拍。 */
  private class PteBus(val memory: PteMemory) {
    private var pending: Option[(Int, BigInt)] = None
    var reads = 0
    var writes = 0
    var lastWriteAddr = 0L
    var lastWriteData = BigInt(0)

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
          lastWriteAddr = reqAddr
          lastWriteData = reqData
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
      fillValid: Boolean, fillPte: BigInt, fillVaddr: BigInt,
      fillAsid: BigInt, fillLevel: Int)

  private def runPtw(
      dut: ysyx_26030103_PTW,
      memory: PteMemory,
      vaddr: BigInt,
      satp: BigInt,
      access: UInt = ysyx_26030103_AccessType.Load,
      privilege: Int = 1,
      sum: Boolean = true,
      mxr: Boolean = false
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
    dut.io.req.bits.id.poke(0.U)
    dut.io.req.bits.vaddr.poke(vaddr.U(64.W))
    dut.io.req.bits.satp.poke(satp.U(64.W))
    dut.io.req.bits.privilege.poke(privilege.U)
    dut.io.req.bits.access.poke(access)
    dut.io.req.bits.sum.poke(sum.B)
    dut.io.req.bits.mxr.poke(mxr.B)
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
          asBig(dut.io.resp.bits.fillVaddr),
          asBig(dut.io.resp.bits.fillAsid),
          dut.io.resp.bits.fillLevel.peek().litValue.toInt
        ))
      } else {
        bus.tick(dut.io.bus, dut.clock)
      }
      guard += 1
    }
    (result.get, bus.reads, bus.writes)
  }

  private def buildSv39Tables(memory: PteMemory, leafFlags: Long = 0xcfL,
      leafPpn: BigInt = LeafPPN, rootEntryOffset: Int = 2): Unit = {
    memory.writePte(Root + rootEntryOffset * 8L, pte(BigInt("80002", 16), 0x1L))
    memory.writePte(0x80002000L, pte(BigInt("80003", 16), 0x1L))
    memory.writePte(0x80003000L + 8L, pte(leafPpn, leafFlags))
  }

  "PTW" should "完成 Sv39 三级 walk 并回填完整 64 位 PTE" in {
    val memory = new PteMemory
    buildSv39Tables(memory)
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 12, levels = 3, tableEntries = 512,
      pteSizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 39,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val vaddr = BigInt("0000000080001234", 16)
      val (r, reads, _) = runPtw(dut, memory, vaddr,
        satpSv39(RootPPN, 0x1234))
      r.fault should be(false)
      r.paddr should be(Leaf + 0x234)
      r.fillValid should be(true)
      r.fillLevel should be(0)
      r.fillVaddr should be(vaddr)
      r.fillAsid should be(BigInt(0x1234))
      r.fillPte should be(pte(LeafPPN, 0xcfL))
      // 8B PTE 的每一级都要读低/高两个 32 位字。
      reads should be(6)
      // 8B PTE 的低/高字必须来自同一个页表项。
      (r.fillPte >> 32) should be(BigInt(0))
    }
  }

  "PTW" should "在 Sv39 下检查 U/SUM/R/W/X 权限并给出正确 cause" in {
    val memory = new PteMemory
    // R-only 的 U 页：S 模式 load 需要 SUM，store/fetch 始终失败。
    buildSv39Tables(memory, leafFlags = 0x53L) // V R U A
    simulate(new ysyx_26030103_PTW(
      "fault", levels = 3, tableEntries = 512, pteSizeBytes = 8,
      XLEN = 64, addressWidth = 64, vaBits = 39,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val vaddr = BigInt("0000000080001234", 16)
      val satp = satpSv39(RootPPN)

      val loadNoSum = runPtw(dut, memory, vaddr, satp,
        ysyx_26030103_AccessType.Load, privilege = 1, sum = false)._1
      loadNoSum.fault should be(true)
      loadNoSum.cause should be(13)

      val loadSum = runPtw(dut, memory, vaddr, satp,
        ysyx_26030103_AccessType.Load, privilege = 1, sum = true)._1
      loadSum.fault should be(false)
      loadSum.paddr should be(Leaf + 0x234)

      val store = runPtw(dut, memory, vaddr, satp,
        ysyx_26030103_AccessType.Store, privilege = 1, sum = true)._1
      store.fault should be(true)
      store.cause should be(15)

      val fetch = runPtw(dut, memory, vaddr, satp,
        ysyx_26030103_AccessType.Fetch, privilege = 1, sum = true)._1
      fetch.fault should be(true)
      fetch.cause should be(12)

      // U 模式访问 U=1 的 R 页允许 load。
      val userLoad = runPtw(dut, memory, vaddr, satp,
        ysyx_26030103_AccessType.Load, privilege = 0, sum = false)._1
      userLoad.fault should be(false)
    }
  }

  "PTW" should "支持 Sv39 2MiB 超级页并拒绝未对齐 PPN" in {
    val memory = new PteMemory
    memory.writePte(Root + 8L, pte(BigInt("80002", 16), 0x1L))
    memory.writePte(0x80002008L, pte(BigInt("80200", 16), 0xcfL))
    simulate(new ysyx_26030103_PTW(
      "fault", levels = 3, tableEntries = 512, pteSizeBytes = 8,
      superpage = true, fillSuperpage = true,
      XLEN = 64, addressWidth = 64, vaBits = 39,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val vaddr = BigInt("0000000040234567", 16)
      val (r, _, _) = runPtw(dut, memory, vaddr, satpSv39(RootPPN))
      r.fault should be(false)
      r.paddr should be(BigInt("80234567", 16))
      r.fillLevel should be(1)
      r.fillValid should be(true)

      // PPN 低 9 位非零 -> 超级页未对齐，必须 page fault。
      val bad = new PteMemory
      bad.writePte(Root + 8L, pte(BigInt("80002", 16), 0x1L))
      bad.writePte(0x80002008L, pte(BigInt("80008", 16), 0xcfL))
      val (f, _, _) = runPtw(dut, bad, vaddr, satpSv39(RootPPN))
      f.fault should be(true)
      f.cause should be(13)
    }
  }

  "PTW" should "在 Sv39 下执行 A/D 硬件写回" in {
    val memory = new PteMemory
    buildSv39Tables(memory, leafFlags = 0x1fL) // V R W X U，无 A/D
    simulate(new ysyx_26030103_PTW(
      "hardware", levels = 3, tableEntries = 512, pteSizeBytes = 8,
      XLEN = 64, addressWidth = 64, vaBits = 39,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val vaddr = BigInt("0000000080001234", 16)
      val (r, _, writes) = runPtw(dut, memory, vaddr, satpSv39(RootPPN),
        ysyx_26030103_AccessType.Store, privilege = 1, sum = true)
      r.fault should be(false)
      r.paddr should be(Leaf + 0x234)
      writes should be(1)
      val updated = memory.readPte(0x80003008L)
      (updated & BigInt(0xc0)) should be(BigInt(0xc0))
      // 高 32 位 PPN 必须保持原值（写回只改低字）。
      (updated >> 32) should be(pte(LeafPPN, 0L) >> 32)
    }
  }

  "PTW" should "拒绝 Sv39 64 位 PTE 的保留位" in {
    val memory = new PteMemory
    buildSv39Tables(memory)
    memory.writePte(0x80003008L,
      pte(LeafPPN, 0xcfL) | (BigInt(1) << 54))
    simulate(new ysyx_26030103_PTW(
      "fault", levels = 3, tableEntries = 512, pteSizeBytes = 8,
      XLEN = 64, addressWidth = 64, vaBits = 39,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val (r, _, _) = runPtw(dut, memory,
        BigInt("0000000080001234", 16), satpSv39(RootPPN))
      r.fault should be(true)
      r.cause should be(13)
    }
  }

  "PTW" should "拒绝非规范 Sv39 虚拟地址且不访问页表" in {
    val memory = new PteMemory
    buildSv39Tables(memory)
    simulate(new ysyx_26030103_PTW(
      "fault", levels = 3, tableEntries = 512, pteSizeBytes = 8,
      XLEN = 64, addressWidth = 64, vaBits = 39,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val badVaddr = BigInt("0000008000000000", 16)
      val (r, reads, _) = runPtw(dut, memory, badVaddr, satpSv39(RootPPN))
      r.fault should be(true)
      r.cause should be(13)
      r.paddr should be(BigInt(0))
      reads should be(0)
    }
  }

  "PTW" should "支持可配置 Sv48 四级 walk" in {
    val memory = new PteMemory
    // VA 0x0000008000001234：VPN[3]=1, VPN[2..0]=0,0,1。
    memory.writePte(Root + 8L, pte(BigInt("80002", 16), 0x1L))
    memory.writePte(0x80002000L, pte(BigInt("80003", 16), 0x1L))
    memory.writePte(0x80003000L, pte(BigInt("80004", 16), 0x1L))
    memory.writePte(0x80004008L, pte(BigInt("80005", 16), 0xcfL))
    simulate(new ysyx_26030103_PTW(
      "fault", levels = 4, tableEntries = 512, pteSizeBytes = 8,
      XLEN = 64, addressWidth = 64, vaBits = 48,
      pteConfig = ysyx_26030103_PteConfig(pteBits = 64, ppnHigh = 53)
    )) { dut =>
      val vaddr = BigInt("0000008000001234", 16)
      val (r, reads, _) = runPtw(dut, memory, vaddr, satpSv48(RootPPN))
      r.fault should be(false)
      r.paddr should be(BigInt("80005234", 16))
      r.fillLevel should be(0)
      reads should be(8)
    }
  }

  "TLB" should "缓存 Sv39 4KiB/2MiB 页并按 16 位 ASID 匹配" in {
    simulate(new ysyx_26030103_TLB(
      entries = 8, ways = 1, asidBits = 16, globalEnable = true,
      adMode = "fault", superpage = true,
      pageBits = 12, levels = 3, indexBits = 9,
      XLEN = 64, addressWidth = 64, vaBits = 39, pteWidth = 64,
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

      // 基础页。
      dut.io.fillValid.poke(true.B)
      dut.io.fillVaddr.poke(BigInt("0000000080001234", 16).U(64.W))
      dut.io.fillAsid.poke(0x1234.U)
      dut.io.fillPte.poke(pte(LeafPPN, 0xcfL).U(64.W))
      dut.io.fillLevel.poke(0.U)
      dut.clock.step()
      dut.io.fillValid.poke(false.B)

      // 2MiB 超级页。
      dut.io.fillValid.poke(true.B)
      dut.io.fillVaddr.poke(BigInt("0000000040234567", 16).U(64.W))
      dut.io.fillAsid.poke(0x1234.U)
      dut.io.fillPte.poke(pte(BigInt("80200", 16), 0xcfL).U(64.W))
      dut.io.fillLevel.poke(1.U)
      dut.clock.step()
      dut.io.fillValid.poke(false.B)

      dut.io.lookup(0).asid.poke(0x1234.U)
      dut.io.lookup(0).vaddr.poke(BigInt("0000000080001234", 16).U(64.W))
      asBool(dut.io.result(0).hit) should be(true)
      asBool(dut.io.result(0).fault) should be(false)
      asBig(dut.io.result(0).paddr) should be(Leaf + 0x234)

      dut.io.lookup(0).vaddr.poke(BigInt("00000000402fffff", 16).U(64.W))
      asBool(dut.io.result(0).hit) should be(true)
      asBig(dut.io.result(0).paddr) should be(BigInt("802fffff", 16))

      // ASID 不同且非 Global 时不能命中。
      dut.io.lookup(0).asid.poke(0x1235.U)
      dut.io.lookup(0).vaddr.poke(BigInt("0000000080001234", 16).U(64.W))
      asBool(dut.io.result(0).hit) should be(false)
    }
  }

  "MMU" should "在 Sv39 下完成 TLB miss refill 且第二次访问命中 TLB" in {
    val memory = new PteMemory
    buildSv39Tables(memory)
    val profile = sv39Profile(tlb = true)
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

      def access(): BigInt = {
        dut.io.req.valid.poke(true.B)
        dut.io.req.bits.id.poke(1.U)
        dut.io.req.bits.vaddr.poke(BigInt("0000000080001234", 16).U(64.W))
        dut.io.req.bits.satp.poke(satpSv39(RootPPN, 0x1234).U(64.W))
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

      access() should be(Leaf + 0x234)
      val readsAfterMiss = bus.reads
      readsAfterMiss should be(6)
      access() should be(Leaf + 0x234)
      bus.reads should be(readsAfterMiss)
    }
  }

  "NPC" should "在 XLEN=64 下 elaboration 标准 Sv39/Sv48 MMU" in {
    ChiselStage.emitSystemVerilog(new _root_.ysyx_26030103.ysyx_26030103(
      ysyx_26030103_NPCConfig(
        XLEN = 64,
        AddressWidth = 64,
        MMU = sv39Profile(tlb = true)
      )
    ))
    ChiselStage.emitSystemVerilog(new _root_.ysyx_26030103.ysyx_26030103(
      ysyx_26030103_NPCConfig(
        XLEN = 64,
        AddressWidth = 64,
        MMU = sv48Profile(tlb = true)
      )
    ))
  }
}
