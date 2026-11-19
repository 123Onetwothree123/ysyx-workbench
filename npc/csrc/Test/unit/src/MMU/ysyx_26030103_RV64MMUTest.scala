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

  private def PTE(PPN: BigInt, flags: Long): BigInt =
    ((PPN & ((BigInt(1) << 44) - 1)) << 10) | BigInt(flags)

  private def satpSv39(RootPPN: BigInt, ASID: Int = 0): BigInt =
    (BigInt(8) << 60) | (BigInt(ASID & 0xffff) << 44) | (RootPPN & ((BigInt(1) << 44) - 1))

  private def satpSv48(RootPPN: BigInt, ASID: Int = 0): BigInt =
    (BigInt(9) << 60) | (BigInt(ASID & 0xffff) << 44) | (RootPPN & ((BigInt(1) << 44) - 1))

  private def sv39Profile(
      TLB: Boolean = false,
      superPage: Boolean = false,
      adMode: String = "fault"
  ): ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile(
      enabled = true,
      xlen = 64,
      pageSizeBytes = 4096,
      levels = 3,
      PTESizeBytes = 8,
      pageTableSizeBytes = 4096,
      PTEFormat = "riscv64",
      PTEConfig = ysyx_26030103_PTEConfig(PTEBits = 64, PPNHigh = 53),
      superpage = superPage,
      adMode = adMode,
      TLBEnabled = TLB,
      TLBASIDBits = 16,
      TLBSuperpage = TLB && superPage,
      PTWArch = "independent",
      PTWOutstanding = 2
    )

  private def sv48Profile(TLB: Boolean = false): ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile(
      enabled = true,
      xlen = 64,
      pageSizeBytes = 4096,
      levels = 4,
      PTESizeBytes = 8,
      pageTableSizeBytes = 4096,
      PTEFormat = "riscv64",
      PTEConfig = ysyx_26030103_PTEConfig(PTEBits = 64, PPNHigh = 53),
      superpage = true,
      TLBEnabled = TLB,
      TLBASIDBits = 16,
      TLBSuperpage = TLB,
      PTWArch = "independent",
      PTWOutstanding = 2
    )

  private def asBool(value: Bool): Boolean = value.peek().litValue == 1
  private def asBig(value: UInt): BigInt = value.peek().litValue

  /** 以 32 位字为单位的稀疏页表内存。 */
  private class PTEMemory {
    val words = scala.collection.mutable.Map.empty[Long, BigInt]
    def writeWord(addr: Long, value: BigInt): Unit =
      words(addr & 0xffffffffffffL) = value & 0xffffffffL
    def readWord(addr: Long): BigInt =
      words.getOrElse(addr & 0xffffffffffffL, BigInt(0))
    def writePTE(addr: Long, value: BigInt): Unit = {
      writeWord(addr, value & 0xffffffffL)
      writeWord(addr + 4, (value >> 32) & 0xffffffffL)
    }
    def readPTE(addr: Long): BigInt =
      readWord(addr) | (readWord(addr + 4) << 32)
  }

  /** 页表总线模型：可记录读/写次数，响应延迟一拍。 */
  private class PTEBus(val memory: PTEMemory) {
    private var pending: Option[(Int, BigInt)] = None
    var reads = 0
    var writes = 0
    var lastWriteAddress = 0L
    var lastWriteData = BigInt(0)

    def tick(bus: ysyx_26030103_PTEBusIO, clock: Clock): Unit = {
      val RequestFire = asBool(bus.request.valid) && asBool(bus.request.ready)
      val RequestID = bus.request.bits.ID.peek().litValue.toInt
      val RequestAddress = asBig(bus.request.bits.address).toLong
      val RequestWrite = asBool(bus.request.bits.write)
      val RequestData = asBig(bus.request.bits.data)

      bus.response.valid.poke(pending.isDefined.B)
      bus.response.bits.ID.poke(pending.map(_._1).getOrElse(0).U(4.W))
      bus.response.bits.data.poke(pending.map(_._2).getOrElse(BigInt(0)).U(32.W))
      bus.response.bits.fault.poke(false.B)
      val ResponseReady = asBool(bus.response.ready)
      clock.step()

      if (pending.isDefined && ResponseReady) { pending = None }
      if (RequestFire) {
        if (RequestWrite) {
          writes += 1
          lastWriteAddress = RequestAddress
          lastWriteData = RequestData
          memory.writeWord(RequestAddress, RequestData)
          pending = Some((RequestID, BigInt(0)))
        } else {
          reads += 1
          pending = Some((RequestID, memory.readWord(RequestAddress)))
        }
      }
    }
  }

  private case class WalkResult(paddr: BigInt, fault: Boolean, cause: BigInt,
      fillValid: Boolean, FillPTE: BigInt, fillVirtualAddress: BigInt,
      FillASID: BigInt, fillLevel: Int)

  private def runPTW(
      dut: ysyx_26030103_PTW,
      memory: PTEMemory,
      vaddr: BigInt,
      satp: BigInt,
      access: UInt = ysyx_26030103_AccessType.Load,
      privilege: Int = 1,
      sum: Boolean = true,
      mxr: Boolean = false
  ): (WalkResult, Int, Int) = {
    val bus = new PTEBus(memory)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.request.valid.poke(false.B)
    dut.io.request.bits.ID.poke(0.U)
    dut.io.request.bits.vaddr.poke(0.U)
    dut.io.request.bits.satp.poke(0.U)
    dut.io.request.bits.privilege.poke(0.U)
    dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
    dut.io.request.bits.sum.poke(false.B)
    dut.io.request.bits.mxr.poke(false.B)
    dut.io.bus.request.ready.poke(true.B)
    dut.io.bus.response.valid.poke(false.B)
    dut.io.bus.response.bits.ID.poke(0.U)
    dut.io.bus.response.bits.data.poke(0.U)
    dut.io.bus.response.bits.fault.poke(false.B)
    dut.io.response.ready.poke(true.B)
    dut.io.flush.poke(false.B)

    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.ID.poke(0.U)
    dut.io.request.bits.vaddr.poke(vaddr.U(64.W))
    dut.io.request.bits.satp.poke(satp.U(64.W))
    dut.io.request.bits.privilege.poke(privilege.U)
    dut.io.request.bits.access.poke(access)
    dut.io.request.bits.sum.poke(sum.B)
    dut.io.request.bits.mxr.poke(mxr.B)
    var accepted = false
    var guard = 0
    while (!accepted && guard < 50) {
      accepted = asBool(dut.io.request.ready)
      bus.tick(dut.io.bus, dut.clock)
      guard += 1
    }
    dut.io.request.valid.poke(false.B)

    var result: Option[WalkResult] = None
    guard = 0
    while (result.isEmpty && guard < 200) {
      if (asBool(dut.io.response.valid)) {
        result = Some(WalkResult(
          asBig(dut.io.response.bits.paddr),
          asBool(dut.io.response.bits.fault),
          asBig(dut.io.response.bits.cause),
          asBool(dut.io.response.bits.fillValid),
          asBig(dut.io.response.bits.FillPTE),
          asBig(dut.io.response.bits.fillVirtualAddress),
          asBig(dut.io.response.bits.FillASID),
          dut.io.response.bits.fillLevel.peek().litValue.toInt
        ))
      } else {
        bus.tick(dut.io.bus, dut.clock)
      }
      guard += 1
    }
    (result.get, bus.reads, bus.writes)
  }

  private def buildSv39Tables(memory: PTEMemory, leafFlags: Long = 0xcfL,
      LeafPPN: BigInt = LeafPPN, rootEntryOffset: Int = 2): Unit = {
    memory.writePTE(Root + rootEntryOffset * 8L, PTE(BigInt("80002", 16), 0x1L))
    memory.writePTE(0x80002000L, PTE(BigInt("80003", 16), 0x1L))
    memory.writePTE(0x80003000L + 8L, PTE(LeafPPN, leafFlags))
  }

  "PTW" should "完成 Sv39 三级 walk 并回填完整 64 位 PTE" in {
    val memory = new PTEMemory
    buildSv39Tables(memory)
    simulate(new ysyx_26030103_PTW(
      "fault", pageBits = 12, levels = 3, tableEntries = 512,
      PTESizeBytes = 8, XLEN = 64, addressWidth = 64, vaBits = 39,
      PTEConfig = ysyx_26030103_PTEConfig(PTEBits = 64, PPNHigh = 53)
    )) { dut =>
      val vaddr = BigInt("0000000080001234", 16)
      val (r, reads, _) = runPTW(dut, memory, vaddr,
        satpSv39(RootPPN, 0x1234))
      r.fault should be(false)
      r.paddr should be(Leaf + 0x234)
      r.fillValid should be(true)
      r.fillLevel should be(0)
      r.fillVirtualAddress should be(vaddr)
      r.FillASID should be(BigInt(0x1234))
      r.FillPTE should be(PTE(LeafPPN, 0xcfL))
      // 8B PTE 的每一级都要读低/高两个 32 位字。
      reads should be(6)
      // 8B PTE 的低/高字必须来自同一个页表项。
      (r.FillPTE >> 32) should be(BigInt(0))
    }
  }

  "PTW" should "在 Sv39 下检查 U/SUM/R/W/X 权限并给出正确 cause" in {
    val memory = new PTEMemory
    // R-only 的 U 页：S 模式 load 需要 SUM，store/fetch 始终失败。
    buildSv39Tables(memory, leafFlags = 0x53L) // V R U A
    simulate(new ysyx_26030103_PTW(
      "fault", levels = 3, tableEntries = 512, PTESizeBytes = 8,
      XLEN = 64, addressWidth = 64, vaBits = 39,
      PTEConfig = ysyx_26030103_PTEConfig(PTEBits = 64, PPNHigh = 53)
    )) { dut =>
      val vaddr = BigInt("0000000080001234", 16)
      val satp = satpSv39(RootPPN)

      val loadNoSum = runPTW(dut, memory, vaddr, satp,
        ysyx_26030103_AccessType.Load, privilege = 1, sum = false)._1
      loadNoSum.fault should be(true)
      loadNoSum.cause should be(13)

      val loadSum = runPTW(dut, memory, vaddr, satp,
        ysyx_26030103_AccessType.Load, privilege = 1, sum = true)._1
      loadSum.fault should be(false)
      loadSum.paddr should be(Leaf + 0x234)

      val store = runPTW(dut, memory, vaddr, satp,
        ysyx_26030103_AccessType.Store, privilege = 1, sum = true)._1
      store.fault should be(true)
      store.cause should be(15)

      val fetch = runPTW(dut, memory, vaddr, satp,
        ysyx_26030103_AccessType.Fetch, privilege = 1, sum = true)._1
      fetch.fault should be(true)
      fetch.cause should be(12)

      // U 模式访问 U=1 的 R 页允许 load。
      val userLoad = runPTW(dut, memory, vaddr, satp,
        ysyx_26030103_AccessType.Load, privilege = 0, sum = false)._1
      userLoad.fault should be(false)
    }
  }

  "PTW" should "支持 Sv39 2MiB 超级页并拒绝未对齐 PPN" in {
    val memory = new PTEMemory
    memory.writePTE(Root + 8L, PTE(BigInt("80002", 16), 0x1L))
    memory.writePTE(0x80002008L, PTE(BigInt("80200", 16), 0xcfL))
    simulate(new ysyx_26030103_PTW(
      "fault", levels = 3, tableEntries = 512, PTESizeBytes = 8,
      superpage = true, fillSuperpage = true,
      XLEN = 64, addressWidth = 64, vaBits = 39,
      PTEConfig = ysyx_26030103_PTEConfig(PTEBits = 64, PPNHigh = 53)
    )) { dut =>
      val vaddr = BigInt("0000000040234567", 16)
      val (r, _, _) = runPTW(dut, memory, vaddr, satpSv39(RootPPN))
      r.fault should be(false)
      r.paddr should be(BigInt("80234567", 16))
      r.fillLevel should be(1)
      r.fillValid should be(true)

      // PPN 低 9 位非零 -> 超级页未对齐，必须 page fault。
      val bad = new PTEMemory
      bad.writePTE(Root + 8L, PTE(BigInt("80002", 16), 0x1L))
      bad.writePTE(0x80002008L, PTE(BigInt("80008", 16), 0xcfL))
      val (f, _, _) = runPTW(dut, bad, vaddr, satpSv39(RootPPN))
      f.fault should be(true)
      f.cause should be(13)
    }
  }

  "PTW" should "在 Sv39 下执行 A/D 硬件写回" in {
    val memory = new PTEMemory
    buildSv39Tables(memory, leafFlags = 0x1fL) // V R W X U，无 A/D
    simulate(new ysyx_26030103_PTW(
      "hardware", levels = 3, tableEntries = 512, PTESizeBytes = 8,
      XLEN = 64, addressWidth = 64, vaBits = 39,
      PTEConfig = ysyx_26030103_PTEConfig(PTEBits = 64, PPNHigh = 53)
    )) { dut =>
      val vaddr = BigInt("0000000080001234", 16)
      val (r, _, writes) = runPTW(dut, memory, vaddr, satpSv39(RootPPN),
        ysyx_26030103_AccessType.Store, privilege = 1, sum = true)
      r.fault should be(false)
      r.paddr should be(Leaf + 0x234)
      writes should be(1)
      val updated = memory.readPTE(0x80003008L)
      (updated & BigInt(0xc0)) should be(BigInt(0xc0))
      // 高 32 位 PPN 必须保持原值（写回只改低字）。
      (updated >> 32) should be(PTE(LeafPPN, 0L) >> 32)
    }
  }

  "PTW" should "拒绝 Sv39 64 位 PTE 的保留位" in {
    val memory = new PTEMemory
    buildSv39Tables(memory)
    memory.writePTE(0x80003008L,
      PTE(LeafPPN, 0xcfL) | (BigInt(1) << 54))
    simulate(new ysyx_26030103_PTW(
      "fault", levels = 3, tableEntries = 512, PTESizeBytes = 8,
      XLEN = 64, addressWidth = 64, vaBits = 39,
      PTEConfig = ysyx_26030103_PTEConfig(PTEBits = 64, PPNHigh = 53)
    )) { dut =>
      val (r, _, _) = runPTW(dut, memory,
        BigInt("0000000080001234", 16), satpSv39(RootPPN))
      r.fault should be(true)
      r.cause should be(13)
    }
  }

  "PTW" should "拒绝非规范 Sv39 虚拟地址且不访问页表" in {
    val memory = new PTEMemory
    buildSv39Tables(memory)
    simulate(new ysyx_26030103_PTW(
      "fault", levels = 3, tableEntries = 512, PTESizeBytes = 8,
      XLEN = 64, addressWidth = 64, vaBits = 39,
      PTEConfig = ysyx_26030103_PTEConfig(PTEBits = 64, PPNHigh = 53)
    )) { dut =>
      val badVirtualAddress = BigInt("0000008000000000", 16)
      val (r, reads, _) = runPTW(dut, memory, badVirtualAddress, satpSv39(RootPPN))
      r.fault should be(true)
      r.cause should be(13)
      r.paddr should be(BigInt(0))
      reads should be(0)
    }
  }

  "PTW" should "支持可配置 Sv48 四级 walk" in {
    val memory = new PTEMemory
    // VA 0x0000008000001234：VPN[3]=1, VPN[2..0]=0,0,1。
    memory.writePTE(Root + 8L, PTE(BigInt("80002", 16), 0x1L))
    memory.writePTE(0x80002000L, PTE(BigInt("80003", 16), 0x1L))
    memory.writePTE(0x80003000L, PTE(BigInt("80004", 16), 0x1L))
    memory.writePTE(0x80004008L, PTE(BigInt("80005", 16), 0xcfL))
    simulate(new ysyx_26030103_PTW(
      "fault", levels = 4, tableEntries = 512, PTESizeBytes = 8,
      XLEN = 64, addressWidth = 64, vaBits = 48,
      PTEConfig = ysyx_26030103_PTEConfig(PTEBits = 64, PPNHigh = 53)
    )) { dut =>
      val vaddr = BigInt("0000008000001234", 16)
      val (r, reads, _) = runPTW(dut, memory, vaddr, satpSv48(RootPPN))
      r.fault should be(false)
      r.paddr should be(BigInt("80005234", 16))
      r.fillLevel should be(0)
      reads should be(8)
    }
  }

  "TLB" should "缓存 Sv39 4KiB/2MiB 页并按 16 位 ASID 匹配" in {
    simulate(new ysyx_26030103_TLB(
      entries = 8, ways = 1, ASIDBits = 16, globalEnable = true,
      adMode = "fault", superpage = true,
      pageBits = 12, levels = 3, indexBits = 9,
      XLEN = 64, addressWidth = 64, vaBits = 39, PTEWidth = 64,
      PTEPPNHigh = 53
    )) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.io.fillValid.poke(false.B)
      dut.io.fillVirtualAddress.poke(0.U)
      dut.io.FillASID.poke(0.U)
      dut.io.FillPTE.poke(0.U(64.W))
      dut.io.fillLevel.poke(0.U)
      dut.io.lookup(0).vaddr.poke(0.U)
      dut.io.lookup(0).ASID.poke(0.U)
      dut.io.lookup(0).privilege.poke(1.U)
      dut.io.lookup(0).access.poke(ysyx_26030103_AccessType.Load)
      dut.io.lookup(0).sum.poke(true.B)
      dut.io.lookup(0).mxr.poke(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      // 基础页。
      dut.io.fillValid.poke(true.B)
      dut.io.fillVirtualAddress.poke(BigInt("0000000080001234", 16).U(64.W))
      dut.io.FillASID.poke(0x1234.U)
      dut.io.FillPTE.poke(PTE(LeafPPN, 0xcfL).U(64.W))
      dut.io.fillLevel.poke(0.U)
      dut.clock.step()
      dut.io.fillValid.poke(false.B)

      // 2MiB 超级页。
      dut.io.fillValid.poke(true.B)
      dut.io.fillVirtualAddress.poke(BigInt("0000000040234567", 16).U(64.W))
      dut.io.FillASID.poke(0x1234.U)
      dut.io.FillPTE.poke(PTE(BigInt("80200", 16), 0xcfL).U(64.W))
      dut.io.fillLevel.poke(1.U)
      dut.clock.step()
      dut.io.fillValid.poke(false.B)

      dut.io.lookup(0).ASID.poke(0x1234.U)
      dut.io.lookup(0).vaddr.poke(BigInt("0000000080001234", 16).U(64.W))
      asBool(dut.io.result(0).hit) should be(true)
      asBool(dut.io.result(0).fault) should be(false)
      asBig(dut.io.result(0).paddr) should be(Leaf + 0x234)

      dut.io.lookup(0).vaddr.poke(BigInt("00000000402fffff", 16).U(64.W))
      asBool(dut.io.result(0).hit) should be(true)
      asBig(dut.io.result(0).paddr) should be(BigInt("802fffff", 16))

      // ASID 不同且非 Global 时不能命中。
      dut.io.lookup(0).ASID.poke(0x1235.U)
      dut.io.lookup(0).vaddr.poke(BigInt("0000000080001234", 16).U(64.W))
      asBool(dut.io.result(0).hit) should be(false)
    }
  }

  "MMU" should "在 Sv39 下完成 TLB miss refill 且第二次访问命中 TLB" in {
    val memory = new PTEMemory
    buildSv39Tables(memory)
    val profile = sv39Profile(TLB = true)
    simulate(new ysyx_26030103_MMU(profile)) { dut =>
      val bus = new PTEBus(memory)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.ID.poke(0.U)
      dut.io.request.bits.vaddr.poke(0.U)
      dut.io.request.bits.access.poke(ysyx_26030103_AccessType.Load)
      dut.io.request.bits.privilege.poke(1.U)
      dut.io.request.bits.sum.poke(true.B)
      dut.io.request.bits.mxr.poke(false.B)
      dut.io.request.bits.satp.poke(0.U)
      dut.io.bus.request.ready.poke(true.B)
      dut.io.bus.response.valid.poke(false.B)
      dut.io.bus.response.bits.ID.poke(0.U)
      dut.io.bus.response.bits.data.poke(0.U)
      dut.io.bus.response.bits.fault.poke(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      def access(): BigInt = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.ID.poke(1.U)
        dut.io.request.bits.vaddr.poke(BigInt("0000000080001234", 16).U(64.W))
        dut.io.request.bits.satp.poke(satpSv39(RootPPN, 0x1234).U(64.W))
        var accepted = false
        var guard = 0
        while (!accepted && guard < 50) {
          accepted = asBool(dut.io.request.ready)
          bus.tick(dut.io.bus, dut.clock)
          guard += 1
        }
        dut.io.request.valid.poke(false.B)
        var result: Option[BigInt] = None
        guard = 0
        while (result.isEmpty && guard < 200) {
          if (asBool(dut.io.response.valid)) {
            asBool(dut.io.response.bits.fault) should be(false)
            result = Some(asBig(dut.io.response.bits.paddr))
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
        MMU = sv39Profile(TLB = true)
      )
    ))
    ChiselStage.emitSystemVerilog(new _root_.ysyx_26030103.ysyx_26030103(
      ysyx_26030103_NPCConfig(
        XLEN = 64,
        AddressWidth = 64,
        MMU = sv48Profile(TLB = true)
      )
    ))
  }
}
