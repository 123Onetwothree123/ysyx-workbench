package ysyx_26030103.mem

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.mmu.ysyx_26030103_LSUMMU
import _root_.ysyx_26030103.config.ysyx_26030103_NPCConfig
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion

/** LSU 非对齐访存定向测试。
  *
  * 背景：LSU 按 MISALIGNED_GRANULE_BYTES 指定的粒度切分非对齐
  * LB/LH/LW/LD 与 SB/SH/SW/SD；跨段/跨 16B cache line/跨 4KB 页都应正确。
  * granule 大于总线宽度时，段内再拆成多个总线宽度请求；段数按
  * ceil((addr % granule + size) / granule) 计算，不写死 4/8/16。
  * 非对齐 AMO/LR/SC 仍按 cause 4/6 陷入且不发总线请求。
  *
  * 测试自带一个最小 AXI5 从机内存模型（AxiMem），只实现本仓库 LSU 会
  * 产生的读写事务：支持任意 ARLEN/AWLEN、ARSIZE/AWSIZE、WSTRB，按字节
  * 保存内存，并可按页注入 DECERR。这样测试不绑定“实现把非对齐访问拆成
  * 几次总线事务”的具体做法，只检查最终读出/写回的字节。
  *
  * 跨页的第二页 PA 由 LSUMMU 通过既有 VIPTPAddress* 端口补发（PIPT 也
  * 使用该端口），所以 LSU 级用例直接驱动该端口模拟“两页 PA 不连续”。
  * 页故障 cause=13/15 只能由 LSUMMU 产生，另外用 LSUMMU 单元用例覆盖；
  * LSU 级用例验证第二页 PA 未到之前不写第一页。
  *
  * 文件末尾用 ysyx_26030103_NPCConfig 的 MISALIGNED_* 字段直接构造
  * 策略变体，不依赖改 .config：support=false 保持 4/6；NC_SPLIT 决定
  * non-cacheable 是否拆分；MMIO_FAULT 决定 MMIO 报 5/7 还是 4/6。
  */
class ysyx_26030103_LSUMisalignedTest extends AnyFlatSpec with Matchers {
  // --------------------------------------------------------------------
  // 通用小工具
  // --------------------------------------------------------------------
  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long    = value.peek().litValue.toLong
  private def big(value: UInt): BigInt   = value.peek().litValue

  private def signExtended(value: BigInt, bits: Int, xlen: Int): BigInt = {
    val mask = (BigInt(1) << bits) - 1
    val full = (BigInt(1) << xlen) - 1
    val raw  = value & mask
    if (((raw >> (bits - 1)) & 1) == 1) raw | (full ^ mask) else raw
  }

  /** [addr, addr+bytes) 覆盖多少个 1<<granule 段；与实现公式一致。 */
  private def segmentCount(addr: Long, bytes: Int, granule: Int): Int =
    ((addr % granule).toInt + bytes - 1) / granule + 1

  private def waitUntil(
      clock: Clock,
      cond: => Boolean,
      message: String,
      limit: Int = 500
  ): Unit = {
    var cycles = 0
    while (!cond && cycles < limit) {
      clock.step()
      cycles += 1
    }
    withClue(s"$message（等待 $cycles 拍）") { cond should be(true) }
  }

  // --------------------------------------------------------------------
  // 非对齐策略配置（对应 Kconfig 的 MISALIGNED_*）
  //
  // 地址分类与实现一致：PMA 中 Cacheable=true 的区域 = cacheable；
  // mapped 且 !Cacheable = non-cacheable/MMIO（实现里是同一类）。
  // MMIO_FAULT=true 优先报 5/7；否则再由 NC_SPLIT 决定 4/6 还是拆分。
  // --------------------------------------------------------------------
  private val TestPMA: Seq[ysyx_26030103_PMARegion] = Seq(
    // cacheable RAM，覆盖 0x8000_0000..0x87FF_FFFF
    ysyx_26030103_PMARegion(0x80000000L, 0x08000000L, Cacheable = true,
      Executable = true),
    // 跨页用例的第二页 PA（不连续但仍在 cacheable 窗口内）
    ysyx_26030103_PMARegion(0x90000000L, 0x04000000L, Cacheable = true,
      Executable = true),
    // 窗口内的 non-cacheable normal memory
    ysyx_26030103_PMARegion(0x98000000L, 0x01000000L, Cacheable = false),
    // 窗口外的 MMIO 设备窗口
    ysyx_26030103_PMARegion(0x10000000L, 0x00001000L, Cacheable = false)
  )

  private val CacheableWindowBase = 0x80000000L
  private val CacheableWindowMask = 0xe0000000L // 0x8000_0000..0x9FFF_FFFF
  private val CacheableAddr      = 0x80001000L
  private val NonCacheableAddr   = 0x98001000L
  private val MmioAddr           = 0x10000000L

  private def policy(
      support: Boolean = true,
      GranuleBytes: Int = 8,
      ncSplit: Boolean = false,
      mmioFault: Boolean = true
  ): ysyx_26030103_NPCConfig =
    ysyx_26030103_NPCConfig(
      MisalignedSupport = support,
      MisalignedGranuleBytes = GranuleBytes,
      MisalignedNCSplit = ncSplit,
      MisalignedMMIOFault = mmioFault,
      // granule 可大于总线宽度（段内再拆成多个总线请求）；cache line
      // 默认 16B，因此这里显式允许 granule=4/8/16 三组。
      InternalBusWidthFollowCpu = false,
      InternalBusWidth = 64,
      DCacheBlockSizeLog2 = 4,
      ResetAddress = 0x80000000L,
      CacheableBase = CacheableWindowBase,
      CacheableMask = CacheableWindowMask,
      PMARegions = TestPMA
    )

  /** 用 NPCConfig 的 MISALIGNED_* 字段直接构造 LSU 策略变体。 */
  private def newLsu(
      XLEN: Int = 32,
      AddressWidth: Int = 32,
      BusDataWidth: Int = 32,
      UseD: Boolean = false,
      UseMMU: Boolean = false,
      cfg: ysyx_26030103_NPCConfig = policy()
  ): ysyx_26030103_LSU =
    new ysyx_26030103_LSU(
      XLEN = XLEN,
      UseD = UseD,
      UseMMU = UseMMU,
      AddressWidth = AddressWidth,
      BusDataWidth = BusDataWidth,
      DCacheableBase = cfg.CacheableBase,
      DCacheableMask = cfg.CacheableMask,
      DCachePMARegions = cfg.PMARegions,
      MisalignedSupport = cfg.MisalignedSupport,
      MisalignedGranuleBytes = cfg.MisalignedGranuleBytes,
      MisalignedNCSplit = cfg.MisalignedNCSplit,
      MisalignedMMIOFault = cfg.MisalignedMMIOFault
    )

  // --------------------------------------------------------------------
  // 最小 AXI5 从机内存模型
  // --------------------------------------------------------------------
  private case class ReadTxn(addr: Long, len: Int, size: Int)

  private final class WriteTxn(val addr: Long, val len: Int, val size: Int) {
    var beat:  Int     = 0
    var fault: Boolean = false
  }

  private final class AxiMem(dut: ysyx_26030103_LSU, clock: Clock) {
    private val busBytes = dut.io.DataBus.R.RDATA.getWidth / 8
    private val busBits  = busBytes * 8
    private val bytes    = scala.collection.mutable.HashMap.empty[Long, Int]
    private val reads    = scala.collection.mutable.ArrayBuffer.empty[ReadTxn]
    private val writes   = scala.collection.mutable.ArrayBuffer.empty[WriteTxn]
    private val bResp    = scala.collection.mutable.ArrayBuffer.empty[Int]
    private val faults   = scala.collection.mutable.HashSet.empty[Long]
    private var readBeat = 0

    var arCount = 0
    var awCount = 0
    var wCount  = 0
    var rCount  = 0

    def put(addr: Long, value: BigInt, n: Int): Unit = {
      var i = 0
      while (i < n) {
        bytes(addr + i) = ((value >> (8 * i)) & 0xff).toInt
        i += 1
      }
    }

    def putBytes(addr: Long, pattern: Seq[Int]): Unit = {
      pattern.zipWithIndex.foreach { case (b, i) => bytes(addr + i) = b & 0xff }
    }

    def addFaultPage(pageBase: Long): Unit = faults += (pageBase & ~0xfffL)
    def removeFaultPage(pageBase: Long): Unit = faults -= (pageBase & ~0xfffL)

    def byteAt(addr: Long): Int = bytes.getOrElse(addr, 0)

    def valueAt(addr: Long, n: Int): BigInt = {
      var value = BigInt(0)
      var i     = 0
      while (i < n) {
        value |= BigInt(byteAt(addr + i)) << (8 * i)
        i += 1
      }
      value
    }

    def snapshot(addr: Long, n: Int): Seq[Int] =
      (0 until n).map(i => byteAt(addr + i))

    private def faultAt(addr: Long): Boolean =
      faults.contains(addr & ~0xfffL)

    def start(): Unit = drive()

    def tick(): Unit = {
      sample()
      clock.step()
      drive()
    }

    private def sample(): Unit = {
      // AW 必须先于同拍 W 处理（LSU 会同时拉起 AWVALID/WVALID）。
      // 读响应先于新 AR 处理，避免“上一笔 RLAST 与本笔 AR 同拍”时
      // 把刚登记的新事务误当成旧事务弹出。
      if (bool(dut.io.DataBus.AW.AWVALID) && bool(dut.io.DataBus.AW.AWREADY)) {
        writes += new WriteTxn(
          long(dut.io.DataBus.AW.AWADDR),
          long(dut.io.DataBus.AW.AWLEN).toInt,
          long(dut.io.DataBus.AW.AWSIZE).toInt
        )
        awCount += 1
      }
      if (bool(dut.io.DataBus.W.WVALID) && bool(dut.io.DataBus.W.WREADY)) {
        require(writes.nonEmpty, "AXI W 拍没有对应的 AW")
        val txn       = writes.head
        val sizeBytes = 1 << txn.size
        val sizeMask  = (sizeBytes - 1).toLong
        val beatAddr  = (txn.addr & ~sizeMask) + txn.beat.toLong * sizeBytes
        val busBase   = beatAddr & ~(busBytes - 1).toLong
        val data      = big(dut.io.DataBus.W.WDATA)
        val strb      = big(dut.io.DataBus.W.WSTRB).toLong
        var i         = 0
        while (i < busBytes) {
          if (((strb >> i) & 1L) == 1L) {
            bytes(busBase + i) = ((data >> (8 * i)) & 0xff).toInt
          }
          i += 1
        }
        if (faultAt(beatAddr) || faultAt(beatAddr + sizeBytes - 1)) {
          txn.fault = true
        }
        wCount += 1
        if (bool(dut.io.DataBus.W.WLAST)) {
          bResp += (if (txn.fault) 3 else 0) // DECERR 映射为 access fault
          writes.remove(0)
        } else {
          txn.beat += 1
        }
      }
      if (bool(dut.io.DataBus.R.RVALID) && bool(dut.io.DataBus.R.RREADY)) {
        require(reads.nonEmpty, "AXI R 拍没有对应的 AR")
        rCount += 1
        val txn = reads.head
        if (bool(dut.io.DataBus.R.RLAST)) {
          require(readBeat == txn.len, "RLAST 与 ARLEN 不一致")
          reads.remove(0)
          readBeat = 0
        } else {
          readBeat += 1
        }
      }
      if (bool(dut.io.DataBus.B.BVALID) && bool(dut.io.DataBus.B.BREADY)) {
        require(bResp.nonEmpty, "AXI B 没有对应的写事务")
        bResp.remove(0)
      }
      // 新读请求最后登记，确保上面不会误弹刚刚登记的项。
      if (bool(dut.io.DataBus.AR.ARVALID) && bool(dut.io.DataBus.AR.ARREADY)) {
        reads += ReadTxn(
          long(dut.io.DataBus.AR.ARADDR),
          long(dut.io.DataBus.AR.ARLEN).toInt,
          long(dut.io.DataBus.AR.ARSIZE).toInt
        )
        arCount += 1
      }
    }

    private def drive(): Unit = {
      dut.io.DataBus.AR.ARREADY.poke(true.B)
      dut.io.DataBus.AW.AWREADY.poke(true.B)
      dut.io.DataBus.W.WREADY.poke(true.B)
      dut.io.DataBus.B.BID.poke(0.U(4.W))

      if (reads.nonEmpty) {
        val txn       = reads.head
        val sizeBytes = 1 << txn.size
        val sizeMask  = (sizeBytes - 1).toLong
        val beatAddr =
          (txn.addr & ~sizeMask) + readBeat.toLong * sizeBytes
        val lane = (beatAddr % busBytes).toInt
        val n    = math.min(sizeBytes, busBytes)
        val data = valueAt(beatAddr, n) << (8 * lane)
        dut.io.DataBus.R.RDATA.poke(data.U(busBits.W))
        dut.io.DataBus.R.RRESP.poke((if (faultAt(beatAddr)) 3 else 0).U(2.W))
        dut.io.DataBus.R.RLAST.poke(
          if (readBeat == txn.len) true.B else false.B)
        dut.io.DataBus.R.RVALID.poke(true.B)
      } else {
        dut.io.DataBus.R.RDATA.poke(0.U(busBits.W))
        dut.io.DataBus.R.RRESP.poke(0.U(2.W))
        dut.io.DataBus.R.RLAST.poke(false.B)
        dut.io.DataBus.R.RVALID.poke(false.B)
      }

      if (bResp.nonEmpty) {
        dut.io.DataBus.B.BRESP.poke(bResp.head.U(2.W))
        dut.io.DataBus.B.BVALID.poke(true.B)
      } else {
        dut.io.DataBus.B.BRESP.poke(0.U(2.W))
        dut.io.DataBus.B.BVALID.poke(false.B)
      }
    }
  }

  // --------------------------------------------------------------------
  // LSU 驱动
  // --------------------------------------------------------------------
  private def resetDut(
      dut:   ysyx_26030103_LSU,
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
    dut.io.VIPTPAddress.poke(0.U(dut.io.VIPTPAddress.getWidth.W))
    dut.io.VIPTPAddressValid.poke(false.B)
    dut.io.VIPTPAddressVirtualAddress.poke(
      0.U(dut.io.VIPTPAddressVirtualAddress.getWidth.W))
    dut.io.VIPTKill.poke(false.B)
    dut.io.PMPFault.poke(false.B)
    dut.io.DataBus.AW.AWREADY.poke(false.B)
    dut.io.DataBus.W.WREADY.poke(false.B)
    dut.io.DataBus.B.BVALID.poke(false.B)
    dut.io.DataBus.B.BID.poke(0.U(4.W))
    dut.io.DataBus.B.BRESP.poke(0.U(2.W))
    dut.io.DataBus.AR.ARREADY.poke(false.B)
    dut.io.DataBus.R.RVALID.poke(false.B)
    dut.io.DataBus.R.RID.poke(0.U(4.W))
    dut.io.DataBus.R.RDATA.poke(0.U(dut.io.DataBus.R.RDATA.getWidth.W))
    dut.io.DataBus.R.RRESP.poke(0.U(2.W))
    dut.io.DataBus.R.RLAST.poke(false.B)
  }

  private def pokeMem(
      dut:        ysyx_26030103_LSU,
      addr:       Long,
      vaddr:      Long,
      write:      Boolean,
      width:      Int,
      signed:     Boolean,
      data:       BigInt,
      pc:         Long,
      atomic:     Boolean = false,
      atomicOp:   BigInt = BigInt(0)
  ): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.in.bits)))
    dut.io.in.bits.Retire.poke(true.B)
    dut.io.in.bits.RegisterWrite.poke((!write || atomic).B)
    dut.io.in.bits.WBSelect.poke(1.U(2.W))
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(write.B)
    dut.io.in.bits.ALUResult.poke(addr.U(dut.io.in.bits.ALUResult.getWidth.W))
    dut.io.in.bits.VAddress.poke(vaddr.U(dut.io.in.bits.VAddress.getWidth.W))
    dut.io.in.bits.WidthSelect.poke(width.U(2.W))
    dut.io.in.bits.LoadSigned.poke(signed.B)
    dut.io.in.bits.StoreData.poke(data.U(dut.io.in.bits.StoreData.getWidth.W))
    dut.io.in.bits.IsAtomic.poke(atomic.B)
    dut.io.in.bits.AtomicOp.poke(atomicOp.U(5.W))
    dut.io.in.bits.PC.poke(pc.U(dut.io.in.bits.PC.getWidth.W))
  }

  /** 提交一条访存并等到 io.in.fire；随后撤销 valid，避免重复受理。 */
  private def issue(
      dut:   ysyx_26030103_LSU,
      axi:   AxiMem,
      addr:  Long,
      vaddr: Long,
      write: Boolean,
      width: Int,
      signed: Boolean,
      data:  BigInt,
      pc:    Long,
      atomic:   Boolean = false,
      atomicOp: BigInt = BigInt(0)
  ): Unit = {
    pokeMem(dut, addr, vaddr, write, width, signed, data, pc, atomic, atomicOp)
    dut.io.in.valid.poke(true.B)
    var cycles = 0
    while (!(bool(dut.io.in.valid) && bool(dut.io.in.ready)) && cycles < 200) {
      axi.tick()
      cycles += 1
    }
    withClue(s"LSU 未受理访存请求（已等 $cycles 拍）") {
      cycles should be < 200
    }
    axi.tick() // 完成 in.fire 边沿
    dut.io.in.valid.poke(false.B)
  }

  private def runLoad(
      dut:    ysyx_26030103_LSU,
      axi:    AxiMem,
      addr:   Long,
      vaddr:  Long,
      width:  Int,
      signed: Boolean,
      pc:     Long
  ): BigInt = {
    issue(dut, axi, addr, vaddr, write = false, width, signed, BigInt(0), pc)
    var cycles = 0
    var result: Option[BigInt] = None
    while (result.isEmpty && cycles < 400) {
      if (bool(dut.io.MemTrapCommit)) {
        fail(s"非对齐 load 不应 trap: cause=${long(dut.io.MemTrapCause)} " +
          s"tval=0x${big(dut.io.MemTrapTval).toString(16)}")
      } else if (bool(dut.io.out.valid)) {
        bool(dut.io.out.bits.Retire) should be(true)
        result = Some(big(dut.io.out.bits.LoadData))
      } else {
        axi.tick()
        cycles += 1
      }
    }
    withClue(s"load 未在 $cycles 拍内完成") { result.isDefined should be(true) }
    axi.tick()
    result.get
  }

  private def runStore(
      dut:   ysyx_26030103_LSU,
      axi:   AxiMem,
      addr:  Long,
      vaddr: Long,
      width: Int,
      data:  BigInt,
      pc:    Long
  ): Unit = {
    issue(dut, axi, addr, vaddr, write = true, width,
      signed = false, data, pc)
    var cycles = 0
    var done   = false
    while (!done && cycles < 400) {
      if (bool(dut.io.MemTrapCommit)) {
        fail(s"非对齐 store 不应 trap: cause=${long(dut.io.MemTrapCause)} " +
          s"tval=0x${big(dut.io.MemTrapTval).toString(16)}")
      } else if (bool(dut.io.out.valid)) {
        bool(dut.io.out.bits.Retire) should be(true)
        done = true
      } else {
        axi.tick()
        cycles += 1
      }
    }
    withClue(s"store 未在 $cycles 拍内完成") { done should be(true) }
    axi.tick()
  }

  private def expectNoBus(dut: ysyx_26030103_LSU): Unit = {
    bool(dut.io.DataBus.AR.ARVALID) should be(false)
    bool(dut.io.DataBus.AW.AWVALID) should be(false)
    bool(dut.io.DataBus.W.WVALID) should be(false)
  }

  /** 普通 load/store 被策略拒绝时：cause/tval 正确、不退休、不发总线。 */
  private def expectMisalignedTrap(
      dut:   ysyx_26030103_LSU,
      axi:   AxiMem,
      addr:  Long,
      vaddr: Long,
      write: Boolean,
      width: Int,
      cause: Long,
      pc:    Long
  ): Unit = {
    pokeMem(dut, addr, vaddr, write, width, signed = false, BigInt(0x5a5a),
      pc)
    dut.io.in.valid.poke(true.B)
    var trapped = false
    var retired = false
    var cycles  = 0
    while (!trapped && !retired && cycles < 100) {
      expectNoBus(dut)
      if (bool(dut.io.MemTrapCommit)) {
        trapped = true
        long(dut.io.MemTrapCause) should be(cause)
        big(dut.io.MemTrapTval) should be(BigInt(vaddr))
        bool(dut.io.out.bits.Retire) should be(false)
      } else if (bool(dut.io.out.valid)) {
        retired = true
      }
      axi.tick()
      cycles += 1
    }
    withClue(s"策略拒绝的访存未在 $cycles 拍内 trap") {
      trapped should be(true)
      retired should be(false)
    }
    expectNoBus(dut)
    dut.io.in.valid.poke(false.B)
  }

  /** 非对齐 LR/SC/AMO 的公共断言。 */
  private def expectMisalignedAtomicTrap(
      dut:    ysyx_26030103_LSU,
      axi:    AxiMem,
      addr:   Long,
      vaddr:  Long,
      width:  Int,
      write:  Boolean,
      op:     BigInt,
      cause:  Long,
      pc:     Long
  ): Unit = {
    pokeMem(dut, addr, vaddr, write, width, signed = false, BigInt(0x1234),
      pc, atomic = true, atomicOp = op)
    dut.io.in.valid.poke(true.B)
    var trapped = false
    var cycles  = 0
    while (!trapped && cycles < 40) {
      expectNoBus(dut)
      if (bool(dut.io.MemTrapCommit)) {
        trapped = true
        long(dut.io.MemTrapCause) should be(cause)
        big(dut.io.MemTrapTval) should be(BigInt(vaddr))
        bool(dut.io.out.bits.Retire) should be(false)
      }
      // trap 也要走完一拍，避免下一条用例看到残留的 MemTrapCommit。
      axi.tick()
      cycles += 1
    }
    withClue(s"非对齐原子指令未在 $cycles 拍内 trap") { trapped should be(true) }
    expectNoBus(dut)
    dut.io.in.valid.poke(false.B)
  }

  // 确定性的测试字节流，覆盖符号位和跨字拼接。
  private val Pattern: Seq[Int] =
    Seq(0x80, 0x7f, 0xff, 0x01, 0x00, 0xfe, 0xab, 0x34,
      0xcd, 0x12, 0x56, 0x9a, 0x78, 0xbc, 0xef, 0x11,
      0x5a, 0xa5, 0x3c, 0xc3, 0x0f, 0xf0, 0x96, 0x69,
      0x24, 0xdb, 0x81, 0x7e, 0x42, 0xbd, 0x18, 0xe7)

  // --------------------------------------------------------------------
  // 1. 非对齐 load：offset 1..7，符号/零扩展
  // --------------------------------------------------------------------
  "LSU misaligned load" should "支持 LB/LBU 在 offset 1..7 并正确扩展" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 32, AddressWidth = 32, BusDataWidth = 32)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi  = new AxiMem(dut, clock)
      axi.start()
      val base = 0x80001000L
      axi.putBytes(base, Pattern)

      for (off <- 1 to 7) {
        val addr = base + off
        val pc   = 0x80000000L + off * 8
        runLoad(dut, axi, addr, addr, width = 0, signed = true, pc) should be(
          signExtended(BigInt(Pattern(off)), 8, 32))
        runLoad(dut, axi, addr, addr, width = 0, signed = false, pc + 4) should be(
          BigInt(Pattern(off)))
      }
    }
  }

  it should "支持 LH/LHU 在 offset 1..7 并正确扩展" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 32, AddressWidth = 32, BusDataWidth = 32)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi  = new AxiMem(dut, clock)
      axi.start()
      val base = 0x80001100L
      axi.putBytes(base, Pattern)

      for (off <- 1 to 7) {
        val addr  = base + off
        val pc    = 0x80000100L + off * 8
        val value = axi.valueAt(addr, 2)
        runLoad(dut, axi, addr, addr, width = 1, signed = true, pc) should be(
          signExtended(value, 16, 32))
        runLoad(dut, axi, addr, addr, width = 1, signed = false, pc + 4) should be(
          value)
      }
    }
  }

  it should "支持 LW/LWU 在 offset 1..7，包含段边界" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 32, AddressWidth = 32, BusDataWidth = 32)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi  = new AxiMem(dut, clock)
      axi.start()
      val base = 0x80001200L
      axi.putBytes(base, Pattern)

      for (off <- 1 to 7) {
        val addr  = base + off
        val pc    = 0x80000200L + off * 8
        val value = axi.valueAt(addr, 4)
        runLoad(dut, axi, addr, addr, width = 2, signed = true, pc) should be(
          signExtended(value, 32, 32))
        runLoad(dut, axi, addr, addr, width = 2, signed = false, pc + 4) should be(
          value)
      }
    }
  }

  it should "支持 LD 在 offset 1..7（多段拼接）" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 64, AddressWidth = 64, BusDataWidth = 32)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi  = new AxiMem(dut, clock)
      axi.start()
      val base = 0x80002000L
      axi.putBytes(base, Pattern)

      for (off <- 1 to 7) {
        val addr = base + off
        val pc   = 0x80000300L + off * 8
        runLoad(dut, axi, addr, addr, width = 3, signed = false, pc) should be(
          axi.valueAt(addr, 8))
      }
    }
  }

  // --------------------------------------------------------------------
  // 2. 非对齐 store：offset 1..7，逐字节校验内存
  // --------------------------------------------------------------------
  it should "支持 SB 在 offset 1..7 且不破坏相邻字节" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 32, AddressWidth = 32, BusDataWidth = 32)
    ) { dut =>
      val clock  = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi    = new AxiMem(dut, clock)
      axi.start()
      val sentinel = 0xa5
      val region   = 0x80003000L
      val data     = BigInt(0x8f)
      for (off <- 1 to 7) {
        axi.putBytes(region + off * 0x40, Seq.fill(32)(sentinel))
      }

      for (off <- 1 to 7) {
        val reg  = region + off * 0x40
        val addr = reg + off
        runStore(dut, axi, addr, addr, width = 0, data,
          0x80000400L + off * 8)
        val expected = Array.fill(32)(sentinel)
        expected(off) = (data & 0xff).toInt
        withClue(s"SB offset=$off 写回字节错误: ") {
          axi.snapshot(reg, 32) should be(expected.toSeq)
        }
      }
    }
  }

  it should "支持 SH 在 offset 1..7 且不破坏相邻字节" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 32, AddressWidth = 32, BusDataWidth = 32)
    ) { dut =>
      val clock    = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi      = new AxiMem(dut, clock)
      axi.start()
      val sentinel = 0x5a
      val region   = 0x80003100L
      val data     = BigInt(0xc1d2)
      for (off <- 1 to 7) {
        axi.putBytes(region + off * 0x40, Seq.fill(32)(sentinel))
      }

      for (off <- 1 to 7) {
        val reg  = region + off * 0x40
        val addr = reg + off
        runStore(dut, axi, addr, addr, width = 1, data,
          0x80000500L + off * 8)
        val expected = Array.fill(32)(sentinel)
        expected(off)     = (data & 0xff).toInt
        expected(off + 1) = ((data >> 8) & 0xff).toInt
        withClue(s"SH offset=$off 写回字节错误: ") {
          axi.snapshot(reg, 32) should be(expected.toSeq)
        }
      }
    }
  }

  it should "支持 SW 在 offset 1..7 且不破坏相邻字节" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 32, AddressWidth = 32, BusDataWidth = 32)
    ) { dut =>
      val clock    = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi      = new AxiMem(dut, clock)
      axi.start()
      val sentinel = 0x3c
      val region   = 0x80003200L
      val data     = BigInt(0x89abcdefL)
      for (off <- 1 to 7) {
        axi.putBytes(region + off * 0x40, Seq.fill(32)(sentinel))
      }

      for (off <- 1 to 7) {
        val reg  = region + off * 0x40
        val addr = reg + off
        runStore(dut, axi, addr, addr, width = 2, data,
          0x80000600L + off * 8)
        val expected = Array.fill(32)(sentinel)
        for (i <- 0 until 4) {
          expected(off + i) = ((data >> (8 * i)) & 0xff).toInt
        }
        withClue(s"SW offset=$off 写回字节错误: ") {
          axi.snapshot(reg, 32) should be(expected.toSeq)
        }
      }
    }
  }

  it should "支持 SD 在 offset 1..7（多段拼接）" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 64, AddressWidth = 64, BusDataWidth = 32)
    ) { dut =>
      val clock    = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi      = new AxiMem(dut, clock)
      axi.start()
      val sentinel = 0x69
      val region   = 0x80003300L
      val data     = BigInt("0123456789abcdef", 16)
      for (off <- 1 to 7) {
        axi.putBytes(region + off * 0x40, Seq.fill(32)(sentinel))
      }

      for (off <- 1 to 7) {
        val reg  = region + off * 0x40
        val addr = reg + off
        runStore(dut, axi, addr, addr, width = 3, data,
          0x80000700L + off * 8)
        val expected = Array.fill(32)(sentinel)
        for (i <- 0 until 8) {
          expected(off + i) = ((data >> (8 * i)) & 0xff).toInt
        }
        withClue(s"SD offset=$off 写回字节错误: ") {
          axi.snapshot(reg, 32) should be(expected.toSeq)
        }
      }
    }
  }

  // --------------------------------------------------------------------
  // 3. 跨 granule 段 / 跨 16B line / 跨 4KB 页
  // --------------------------------------------------------------------
  it should "8 字节访问跨 granule 段边界时 load/store 正确" in {
    val cfg     = policy()
    val granule = cfg.MisalignedGranuleBytes
    simulate(
      newLsu(XLEN = 64, AddressWidth = 64, BusDataWidth = 64, cfg = cfg)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi   = new AxiMem(dut, clock)
      axi.start()
      val loadBase  = 0x80004000L
      val storeBase = 0x80004100L
      axi.putBytes(loadBase, Pattern)
      axi.putBytes(storeBase, Seq.fill(32)(0x11))

      // 从段内开始，8 字节访问必然跨越 1<<granule 段边界。
      val loadAddr = loadBase + granule / 2
      runLoad(dut, axi, loadAddr, loadAddr, width = 3, signed = false,
        0x80000800L) should be(axi.valueAt(loadAddr, 8))

      val storeAddr = storeBase + granule / 2
      val storeData = BigInt("fedcba9876543210", 16)
      runStore(dut, axi, storeAddr, storeAddr, width = 3, storeData,
        0x80000804L)
      val startIdx = (storeAddr - storeBase).toInt
      val expected = Array.fill(32)(0x11)
      for (i <- 0 until 8) {
        expected(startIdx + i) = ((storeData >> (8 * i)) & 0xff).toInt
      }
      axi.snapshot(storeBase, 32) should be(expected.toSeq)
    }
  }

  it should "8 字节访问跨 16B cache line 边界时 load/store 正确" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 64, AddressWidth = 64, BusDataWidth = 32)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi   = new AxiMem(dut, clock)
      axi.start()
      // 默认 DCacheBlockSizeLog2=4，即 16B line。这里 DCache 关闭，只验证
      // LSU 在 line 边界的地址切分/拼装，不覆盖 cache refill 行为。
      val loadBase  = 0x80005000L
      val storeBase = 0x80005100L
      axi.putBytes(loadBase, Pattern)
      axi.putBytes(storeBase, Seq.fill(64)(0x22))

      // 0x8000500c 起 8 字节覆盖 0x8000500c..0x80005013，跨 0x80005010 line。
      val loadAddr = loadBase + 0x0c
      runLoad(dut, axi, loadAddr, loadAddr, width = 3, signed = false,
        0x80000900L) should be(axi.valueAt(loadAddr, 8))

      val storeAddr = storeBase + 0x0c
      val storeData = BigInt("0123456789abcdef", 16)
      runStore(dut, axi, storeAddr, storeAddr, width = 3, storeData,
        0x80000904L)
      val expected = Array.fill(64)(0x22)
      for (i <- 0 until 8) {
        expected(0x0c + i) = ((storeData >> (8 * i)) & 0xff).toInt
      }
      axi.snapshot(storeBase, 64) should be(expected.toSeq)
    }
  }

  it should "8 字节访问跨 4KB 页（PA 连续）时 load/store 正确" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 64, AddressWidth = 64, BusDataWidth = 32)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi   = new AxiMem(dut, clock)
      axi.start()
      // 0x80006ffc 起 8 字节跨越 0x80006000/0x80007000 页边界。
      val pageWin     = 0x80006fe0L
      val winPattern  = (0 until 64).map(i => (i * 37 + 0x5b) & 0xff)
      axi.putBytes(pageWin, winPattern)
      val loadAddr = 0x80006ffcL
      runLoad(dut, axi, loadAddr, loadAddr, width = 3, signed = false,
        0x80000a00L) should be(axi.valueAt(loadAddr, 8))

      val storeAddr = 0x80006ffcL
      val storeData = BigInt("a5a55a5a0f0ff0f0", 16)
      runStore(dut, axi, storeAddr, storeAddr, width = 3, storeData,
        0x80000a04L)
      val expected = winPattern.toArray
      val storeIdx = (storeAddr - pageWin).toInt
      for (i <- 0 until 8) {
        expected(storeIdx + i) = ((storeData >> (8 * i)) & 0xff).toInt
      }
      axi.snapshot(pageWin, 64) should be(expected.toSeq)
    }
  }

  // --------------------------------------------------------------------
  // 4. 跨页故障
  //
  // 当前 LSU 只收到起始地址的 PA，无法在写第一页前预知第二页页故障，
  // 也没有页故障输入端口。这里只能注入“第二页总线 DECERR”，验证：
  //   - 故障按 access fault 提交（load=5 / store=7）；
  //   - MemTrapTval 是原始 VA，而不是第二页 PA；
  //   - 总线错误不会让指令退休。
  // 真正的 cause=13/15 与“首页不写”见文件末尾 pending 用例。
  // --------------------------------------------------------------------
  it should "跨页 load 的第二页总线错误按 cause=5 提交且 tval 是原始 VA" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 32, AddressWidth = 32, BusDataWidth = 32,
        UseMMU = true)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi   = new AxiMem(dut, clock)
      axi.start()
      val pa    = 0x80000ffeL // LW 跨 0x80001000 页边界
      val va    = 0x40000ffeL // 模拟 VA≠PA，第二页不可访问。
      axi.putBytes(0x80000fe0L, Pattern)
      axi.addFaultPage(0x80001000L)
      // 第二页翻译成功，但指向不可访问的物理页。
      setPage1(dut, va = 0x80001000L, pa = 0x80001000L)

      pokeMem(dut, pa, va, write = false, width = 2, signed = false,
        BigInt(0), 0x80000b00L)
      dut.io.in.valid.poke(true.B)
      var trapped = false
      var retired = false
      var cycles  = 0
      while (!trapped && !retired && cycles < 200) {
        if (bool(dut.io.MemTrapCommit)) {
          trapped = true
          long(dut.io.MemTrapCause) should be(5L)
          big(dut.io.MemTrapTval) should be(BigInt(va))
          bool(dut.io.out.bits.Retire) should be(false)
          bool(dut.io.out.bits.RegisterWrite) should be(false)
        } else if (bool(dut.io.out.valid)) {
          retired = true
        } else {
          axi.tick()
          cycles += 1
        }
      }
      withClue(s"跨页 load 故障未在 $cycles 拍内提交") {
        trapped should be(true)
        retired should be(false)
      }
      dut.io.in.valid.poke(false.B)
    }
  }

  it should "跨页 store 的第二页总线错误按 cause=7 提交且 tval 是原始 VA" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 32, AddressWidth = 32, BusDataWidth = 32,
        UseMMU = true)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi   = new AxiMem(dut, clock)
      axi.start()
      val pa    = 0x80001ffeL // SW 跨 0x80002000 页边界
      val va    = 0x40001ffeL
      axi.putBytes(0x80001fe0L, Seq.fill(64)(0x44))
      axi.addFaultPage(0x80002000L)
      setPage1(dut, va = 0x80002000L, pa = 0x80002000L)

      pokeMem(dut, pa, va, write = true, width = 2, signed = false,
        BigInt(0xdeadbeefL), 0x80000b04L)
      dut.io.in.valid.poke(true.B)
      var trapped = false
      var retired = false
      var cycles  = 0
      while (!trapped && !retired && cycles < 200) {
        if (bool(dut.io.MemTrapCommit)) {
          trapped = true
          long(dut.io.MemTrapCause) should be(7L)
          big(dut.io.MemTrapTval) should be(BigInt(va))
          bool(dut.io.out.bits.Retire) should be(false)
        } else if (bool(dut.io.out.valid)) {
          retired = true
        } else {
          axi.tick()
          cycles += 1
        }
      }
      withClue(s"跨页 store 故障未在 $cycles 拍内提交") {
        trapped should be(true)
        retired should be(false)
      }
      dut.io.in.valid.poke(false.B)
    }
  }

  // --------------------------------------------------------------------
  // 5. 非对齐 AMO/LR/SC 仍然 trap 且不发总线请求
  // --------------------------------------------------------------------
  it should "非对齐 LR/SC/AMO.W 仍按 cause=4/6 陷入且不发总线请求" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 32, AddressWidth = 32, BusDataWidth = 32)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi = new AxiMem(dut, clock)
      axi.start()

      // funct5 编码：LR=0x02、SC=0x03、AMOADD=0x00。
      val lrOp     = BigInt(0x02)
      val scOp     = BigInt(0x03)
      val amoaddOp = BigInt(0x00)

      expectMisalignedAtomicTrap(dut, axi, addr = 0x80008001L,
        vaddr = 0x40008001L, width = 2, write = false, lrOp, cause = 4L,
        pc = 0x80000c00L)
      expectMisalignedAtomicTrap(dut, axi, addr = 0x80008002L,
        vaddr = 0x40008002L, width = 2, write = true, scOp, cause = 6L,
        pc = 0x80000c04L)
      expectMisalignedAtomicTrap(dut, axi, addr = 0x80008003L,
        vaddr = 0x40008003L, width = 2, write = true, amoaddOp, cause = 6L,
        pc = 0x80000c08L)
      axi.arCount should be(0)
      axi.awCount should be(0)
      axi.wCount should be(0)
    }
  }

  it should "非对齐 LR.D/SC.D/AMOADD.D 仍按 cause=4/6 陷入且不发总线请求" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 64, AddressWidth = 64, BusDataWidth = 32)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi = new AxiMem(dut, clock)
      axi.start()

      val lrOp     = BigInt(0x02)
      val scOp     = BigInt(0x03)
      val amoaddOp = BigInt(0x00)

      expectMisalignedAtomicTrap(dut, axi, addr = 0x80008104L,
        vaddr = 0x40008104L, width = 3, write = false, lrOp, cause = 4L,
        pc = 0x80000d00L)
      expectMisalignedAtomicTrap(dut, axi, addr = 0x8000810cL,
        vaddr = 0x4000810cL, width = 3, write = true, scOp, cause = 6L,
        pc = 0x80000d04L)
      expectMisalignedAtomicTrap(dut, axi, addr = 0x80008106L,
        vaddr = 0x40008106L, width = 3, write = true, amoaddOp, cause = 6L,
        pc = 0x80000d08L)
      axi.arCount should be(0)
      axi.awCount should be(0)
      axi.wCount should be(0)
    }
  }

  // --------------------------------------------------------------------
  // 6. 对齐访问回归
  // --------------------------------------------------------------------
  it should "对齐 LB/LH/LW/LD 的扩展与结果保持不变" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 32, AddressWidth = 32, BusDataWidth = 32)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi  = new AxiMem(dut, clock)
      axi.start()
      val base = 0x80009000L
      axi.putBytes(base, Pattern)

      runLoad(dut, axi, base + 0, base + 0, width = 0, signed = true,
        0x80000e00L) should be(signExtended(BigInt(0x80), 8, 32))
      runLoad(dut, axi, base + 0, base + 0, width = 0, signed = false,
        0x80000e04L) should be(BigInt(0x80))
      runLoad(dut, axi, base + 4, base + 4, width = 1, signed = true,
        0x80000e08L) should be(signExtended(BigInt(0xfe00), 16, 32))
      runLoad(dut, axi, base + 8, base + 8, width = 2, signed = true,
        0x80000e0cL) should be(BigInt(0x9a5612cdL))
      runLoad(dut, axi, base + 12, base + 12, width = 2, signed = false,
        0x80000e10L) should be(BigInt(0x11efbc78L))
    }
  }

  it should "对齐 SB/SH/SW/SD 的写回位置保持不变" in {
    simulate(
      new ysyx_26030103_LSU(XLEN = 64, AddressWidth = 64, BusDataWidth = 32)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi   = new AxiMem(dut, clock)
      axi.start()
      val base     = 0x8000a000L
      val sentinel = 0x77
      axi.putBytes(base, Seq.fill(64)(sentinel))

      runStore(dut, axi, base + 0, base + 0, width = 0, BigInt(0x12),
        0x80000f00L)
      runStore(dut, axi, base + 2, base + 2, width = 1, BigInt(0x3456),
        0x80000f04L)
      runStore(dut, axi, base + 4, base + 4, width = 2,
        BigInt(0x789abcdeL), 0x80000f08L)
      runStore(dut, axi, base + 8, base + 8, width = 3,
        BigInt("0123456789abcdef", 16), 0x80000f0cL)

      val expected = Array.fill(64)(sentinel)
      expected(0) = 0x12
      expected(2) = 0x56
      expected(3) = 0x34
      for (i <- 0 until 4) {
        expected(4 + i) = ((BigInt(0x789abcdeL) >> (8 * i)) & 0xff).toInt
      }
      val wide = BigInt("0123456789abcdef", 16)
      for (i <- 0 until 8) {
        expected(8 + i) = ((wide >> (8 * i)) & 0xff).toInt
      }
      axi.snapshot(base, 64) should be(expected.toSeq)
    }
  }

  // --------------------------------------------------------------------
  // 7. 跨页 PA 不连续：通过既有 VIPTP 端口补发第二页 PA
  // --------------------------------------------------------------------
  /** LSUMMU 用 VIPTPAddress* 端口补发第二页翻译结果（PIPT 也会使用）。 */
  private def setPage1(dut: ysyx_26030103_LSU, va: Long, pa: Long): Unit = {
    dut.io.VIPTPAddress.poke(pa.U(dut.io.VIPTPAddress.getWidth.W))
    dut.io.VIPTPAddressVirtualAddress.poke(
      va.U(dut.io.VIPTPAddressVirtualAddress.getWidth.W))
    dut.io.VIPTPAddressValid.poke(true.B)
  }

  it should "跨页两页 PA 不连续时 load 仍能正确拼接" in {
    simulate(
      newLsu(XLEN = 64, AddressWidth = 64, BusDataWidth = 32,
        UseMMU = true)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi = new AxiMem(dut, clock)
      axi.start()
      val page0     = 0x80006000L
      val page1Pa   = 0x90003000L // 与 page0 完全不连续
      val page0Win  = page0 + 0xfe0L
      val p0Pattern = (0 until 32).map(i => (i * 11 + 0x31) & 0xff)
      val p1Pattern = (0 until 16).map(i => (i * 7 + 0xc4) & 0xff)
      axi.putBytes(page0Win, p0Pattern)
      axi.putBytes(page1Pa, p1Pattern)

      // 0x80006ffe 起 8 字节：前 2 字节在 page0 末字，后 6 字节在 page1。
      val loadAddr = page0 + 0xffeL
      setPage1(dut, va = page0 + 0x1000L, pa = page1Pa)
      val expected =
        axi.valueAt(loadAddr, 2) | (axi.valueAt(page1Pa, 6) << 16)
      runLoad(dut, axi, loadAddr, loadAddr, width = 3, signed = false,
        0x80001000L) should be(expected)
    }
  }

  it should "跨页两页 PA 不连续时 store 两页都写对" in {
    simulate(
      newLsu(XLEN = 64, AddressWidth = 64, BusDataWidth = 32,
        UseMMU = true)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi = new AxiMem(dut, clock)
      axi.start()
      val page0     = 0x80006000L
      val page1Pa   = 0x90003000L
      val page0Win  = page0 + 0xfe0L
      val p0Pattern = (0 until 32).map(i => (i * 13 + 0x21) & 0xff)
      val p1Pattern = (0 until 16).map(i => (i * 5 + 0xd2) & 0xff)
      axi.putBytes(page0Win, p0Pattern)
      axi.putBytes(page1Pa, p1Pattern)

      val storeAddr = page0 + 0xffeL
      val data      = BigInt("a5a55a5a0f0ff0f0", 16)
      setPage1(dut, va = page0 + 0x1000L, pa = page1Pa)
      runStore(dut, axi, storeAddr, storeAddr, width = 3, data,
        0x80001004L)

      axi.snapshot(storeAddr, 2) should be(
        (0 until 2).map(i => ((data >> (8 * i)) & 0xff).toInt))
      axi.snapshot(page1Pa, 6) should be(
        (0 until 6).map(i => ((data >> (8 * (i + 2))) & 0xff).toInt))
      // 两页边界外的字节不得被覆盖。
      axi.snapshot(page0Win, 0x1e) should be(p0Pattern.take(0x1e))
      axi.snapshot(page1Pa + 6, 2) should be(p1Pattern.slice(6, 8))
    }
  }

  it should "跨页 store 在第二页 PA 到达前不写第一页" in {
    simulate(
      newLsu(XLEN = 64, AddressWidth = 64, BusDataWidth = 32,
        UseMMU = true)
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi = new AxiMem(dut, clock)
      axi.start()
      val page0    = 0x80006000L
      val page1Pa  = 0x90003000L
      val page0Win = page0 + 0xfe0L
      val pattern  = (0 until 32).map(i => (i * 17 + 0x63) & 0xff)
      axi.putBytes(page0Win, pattern)
      axi.putBytes(page1Pa, Seq.fill(16)(0x55))
      val storeAddr = page0 + 0xffeL
      val data      = BigInt("a5a55a5a0f0ff0f0", 16)

      // 故意不提供第二页 PA：store 必须停在写缓冲，第一页也不能写。
      pokeMem(dut, storeAddr, storeAddr, write = true, width = 3,
        signed = false, data, 0x80001100L)
      dut.io.in.valid.poke(true.B)
      var cycles = 0
      while (!(bool(dut.io.in.valid) && bool(dut.io.in.ready)) &&
          cycles < 100) {
        axi.tick()
        cycles += 1
      }
      axi.tick() // in.fire
      dut.io.in.valid.poke(false.B)

      for (_ <- 0 until 30) {
        expectNoBus(dut)
        bool(dut.io.out.valid) should be(false)
        axi.tick()
      }
      axi.awCount should be(0)
      axi.wCount should be(0)
      axi.snapshot(page0Win, 0x20) should be(pattern)

      // 补齐第二页 PA 后必须完成，并同时写对两页。
      setPage1(dut, va = page0 + 0x1000L, pa = page1Pa)
      var done = false
      cycles = 0
      while (!done && cycles < 200) {
        if (bool(dut.io.out.valid)) {
          bool(dut.io.out.bits.Retire) should be(true)
          done = true
        } else {
          axi.tick()
          cycles += 1
        }
      }
      withClue(s"补齐第二页 PA 后 store 未完成（${cycles} 拍）") {
        done should be(true)
      }
      axi.snapshot(storeAddr, 2) should be(
        (0 until 2).map(i => ((data >> (8 * i)) & 0xff).toInt))
      axi.snapshot(page1Pa, 6) should be(
        (0 until 6).map(i => ((data >> (8 * (i + 2))) & 0xff).toInt))
    }
  }

  // --------------------------------------------------------------------
  // 8. 跨页页故障：由 LSUMMU 翻译第二页产生 cause=13/15
  // --------------------------------------------------------------------
  private def resetDmmu(
      dut:   ysyx_26030103_LSUMMU,
      clock: Clock,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.in.bits)))
    dut.io.out.ready.poke(true.B)
    dut.io.privilege.poke(1.U(2.W))
    dut.io.satp.poke("h80000000".U(32.W))
    dut.io.sum.poke(false.B)
    dut.io.mxr.poke(false.B)
    dut.io.flush.poke(false.B)
    dut.io.PMPFault.poke(false.B)
    dut.io.memActive.poke(false.B)
    dut.io.memTrap.poke(false.B)
    dut.io.response.valid.poke(false.B)
    dut.io.response.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.response.bits)))
    dut.io.request.ready.poke(true.B)
  }

  private def dmmuRespond(
      dut:   ysyx_26030103_LSUMMU,
      clock: Clock,
      fault: Boolean,
      paddr: Long,
      va:    Long,
      cause: Long
  ): Unit = {
    dut.io.response.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.response.bits)))
    dut.io.response.bits.ID.poke(1.U)
    dut.io.response.bits.paddr.poke(paddr.U(32.W))
    dut.io.response.bits.fault.poke(fault.B)
    dut.io.response.bits.cause.poke(cause.U(32.W))
    dut.io.response.bits.tval.poke(va.U(32.W))
    dut.io.response.valid.poke(true.B)
    clock.step()
    dut.io.response.valid.poke(false.B)
  }

  /** 跨页访存第二页翻译返回 fault；检查 cause/tval/PC。 */
  private def runDmmuSecondPageFault(
      dut:   ysyx_26030103_LSUMMU,
      clock: Clock,
      write: Boolean,
      cause: Long
  ): Unit = {
    val va      = 0x1000ffeL
    val page1Va = 0x1001000L
    resetDmmu(dut, clock, dut.reset)

    dut.io.in.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.in.bits)))
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(write.B)
    dut.io.in.bits.ALUResult.poke(va.U(32.W))
    dut.io.in.bits.VAddress.poke(va.U(32.W))
    dut.io.in.bits.WidthSelect.poke(2.U(2.W))
    dut.io.in.bits.StoreData.poke(0x11223344L.U(64.W))
    dut.io.in.bits.PC.poke(0x80000000L.U(32.W))
    dut.io.in.valid.poke(true.B)
    waitUntil(clock, bool(dut.io.in.valid) && bool(dut.io.in.ready),
      "LSUMMU 未受理访存")
    clock.step() // in.fire
    dut.io.in.valid.poke(false.B)

    // 基页翻译成功，交给 LSU 后继续翻译第二页。
    waitUntil(clock, bool(dut.io.request.valid), "未发出基页翻译请求")
    long(dut.io.request.bits.vaddr) should be(va)
    clock.step()
    dmmuRespond(dut, clock, fault = false, paddr = 0x80000ffeL, va = va,
      cause = 0L)

    waitUntil(clock, bool(dut.io.request.valid), "未发出第二页翻译请求")
    long(dut.io.request.bits.vaddr) should be(page1Va)
    clock.step()
    dmmuRespond(dut, clock, fault = true, paddr = 0L, va = page1Va,
      cause = cause)

    waitUntil(clock, bool(dut.io.faultCommit), "LSUMMU 未提交页故障")
    long(dut.io.faultCause) should be(cause)
    big(dut.io.faultTval) should be(BigInt(va))
    long(dut.io.FaultPC) should be(0x80000000L)
    dut.io.flush.poke(true.B)
    clock.step()
    dut.io.flush.poke(false.B)
  }

  it should "跨页 load 第二页页故障由 LSUMMU 报 cause=13 且 tval=VA" in {
    simulate(
      new ysyx_26030103_LSUMMU(
        XLEN = 32, AddressWidth = 32, ParallelVIPT = false)
    ) { dut =>
      runDmmuSecondPageFault(dut, dut.clock, write = false, cause = 13L)
    }
  }

  it should "跨页 store 第二页页故障由 LSUMMU 报 cause=15 且 tval=VA" in {
    simulate(
      new ysyx_26030103_LSUMMU(
        XLEN = 32, AddressWidth = 32, ParallelVIPT = false)
    ) { dut =>
      runDmmuSecondPageFault(dut, dut.clock, write = true, cause = 15L)
    }
  }

  // --------------------------------------------------------------------
  // 9. Kconfig 策略参数化（MISALIGNED_*）
  // --------------------------------------------------------------------
  it should "MisalignedSupport=false 时非对齐 load/store 保持 cause 4/6" in {
    simulate(
      newLsu(XLEN = 64, AddressWidth = 64, BusDataWidth = 32,
        cfg = policy(support = false))
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi = new AxiMem(dut, clock)
      axi.start()
      axi.putBytes(CacheableAddr, Pattern)

      // 单字节访问天然对齐，不是“非对齐”场景。
      for (width <- 1 to 3) {
        val addr = CacheableAddr + 1
        val pc   = 0x80002000L + width * 8
        expectMisalignedTrap(dut, axi, addr, addr, write = false, width,
          cause = 4L, pc)
        expectMisalignedTrap(dut, axi, addr + 0x40, addr + 0x40,
          write = true, width, cause = 6L, pc + 4)
      }
      // 对照组：策略拒绝的访问绝不能产生总线事务。
      axi.arCount should be(0)
      axi.awCount should be(0)
      axi.wCount should be(0)
    }
  }

  it should "cacheable 非对齐访问按 GranuleBytes=4/8/16 拆分成功" in {
    for (GranuleBytes <- Seq(4, 8, 16)) {
      simulate(
        newLsu(XLEN = 64, AddressWidth = 64, BusDataWidth = 64,
          cfg = policy(GranuleBytes = GranuleBytes))
      ) { dut =>
        val clock = dut.clock
        resetDut(dut, clock, dut.reset)
        val axi       = new AxiMem(dut, clock)
        axi.start()
        val granule   = GranuleBytes
        val loadBase  = 0x80002000L
        val storeBase = 0x80003000L
        axi.putBytes(loadBase, Pattern)
        axi.putBytes(storeBase, Seq.fill(64)(0x5a))

        // granule 大于访问宽度且不跨段：段数必须是 1。
        segmentCount(loadBase, 4, granule) should be(1)

        // 起点落在段内：段数由公式推出，不假设最多 3 段。
        for (width <- 0 to 3) {
          val size  = 1 << width
          val addr  = loadBase + granule - 1
          val count = segmentCount(addr, size, granule)
          if (size > 1) count should be >= 2
          val pc = 0x80004000L + width * 8
          runLoad(dut, axi, addr, addr, width, signed = false, pc) should be(
            axi.valueAt(addr, size))
        }
        val storeAddr = storeBase + granule - 1
        val storeData = BigInt("0123456789abcdef", 16)
        runStore(dut, axi, storeAddr, storeAddr, width = 3, storeData,
          0x80005000L)
        for (i <- 0 until 8) {
          axi.byteAt(storeAddr + i) should be(
            ((storeData >> (8 * i)) & 0xff).toInt)
        }

        // granule > 总线宽度（8B）：8B 访问完全落在一个 granule 段内，
        // 但段内必须拆成多个 8B 总线请求并正确拼接。
        if (granule > 8) {
          val within = loadBase + 4
          segmentCount(within, 8, granule) should be(1)
          val rBefore = axi.rCount
          runLoad(dut, axi, within, within, width = 3, signed = false,
            0x80006000L) should be(axi.valueAt(within, 8))
          withClue("granule>总线宽度时段内读请求数不足: ") {
            (axi.rCount - rBefore) should be >= 2
          }

          val withinStore = storeBase + 4
          val wBefore     = axi.wCount
          runStore(dut, axi, withinStore, withinStore, width = 3,
            storeData, 0x80006004L)
          withClue("granule>总线宽度时段内写请求数不足: ") {
            (axi.wCount - wBefore) should be >= 2
          }
          for (i <- 0 until 8) {
            axi.byteAt(withinStore + i) should be(
              ((storeData >> (8 * i)) & 0xff).toInt)
          }
        }
      }
    }
  }

  it should "non-cacheable 非对齐按 MisalignedNCSplit 拆分或 cause 4/6" in {
    // 实现把“mapped 且 !Cacheable”统一视为 MMIO；MMIO_FAULT=true 时会先报
    // 5/7，因此 NC_SPLIT 的 4/6 分支需要 MMIO_FAULT=false 才可见。
    simulate(newLsu(cfg = policy(ncSplit = false, mmioFault = false))) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi = new AxiMem(dut, clock)
      axi.start()
      val addr = NonCacheableAddr + 1
      expectMisalignedTrap(dut, axi, addr, addr, write = false, width = 2,
        cause = 4L, pc = 0x80006000L)
      expectMisalignedTrap(dut, axi, addr + 4, addr + 4, write = true,
        width = 2, cause = 6L, pc = 0x80006004L)
      axi.arCount should be(0)
      axi.awCount should be(0)
    }

    // NC_SPLIT=true：non-cacheable 也按 granule 拆分。
    simulate(newLsu(cfg = policy(ncSplit = true, mmioFault = false))) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val axi = new AxiMem(dut, clock)
      axi.start()
      axi.putBytes(NonCacheableAddr, Pattern)
      val addr = NonCacheableAddr + 1
      runLoad(dut, axi, addr, addr, width = 2, signed = false,
        0x80006100L) should be(axi.valueAt(addr, 4))

      val storeAddr = NonCacheableAddr + 0x40
      val data      = BigInt(0x89abcdefL)
      runStore(dut, axi, storeAddr + 1, storeAddr + 1, width = 2, data,
        0x80006104L)
      for (i <- 0 until 4) {
        axi.byteAt(storeAddr + 1 + i) should be(
          ((data >> (8 * i)) & 0xff).toInt)
      }
    }
  }

  it should "MMIO 非对齐按 MisalignedMMIOFault 报 5/7 或 4/6" in {
    for ((fault, loadCause, storeCause) <- Seq(
        (true, 5L, 7L),
        (false, 4L, 6L))) {
      simulate(newLsu(cfg = policy(mmioFault = fault))) { dut =>
        val clock = dut.clock
        resetDut(dut, clock, dut.reset)
        val axi  = new AxiMem(dut, clock)
        axi.start()
        val addr = MmioAddr + 1
        expectMisalignedTrap(dut, axi, addr, addr, write = false, width = 2,
          cause = loadCause, pc = 0x80007000L)
        expectMisalignedTrap(dut, axi, addr + 4, addr + 4, write = true,
          width = 2, cause = storeCause, pc = 0x80007004L)
        // MMIO 两种策略下都不得发出总线事务。
        axi.arCount should be(0)
        axi.awCount should be(0)
      }
    }
  }
}
