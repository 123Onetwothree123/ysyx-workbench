package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.mem.ysyx_26030103_LSU
import _root_.ysyx_26030103.config.ysyx_26030103_CacheStorage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** LSU + DCache 与一个顺序一致的 32 位 AXI 从设备模型做混合 load/store
  * 对比。模型只在 W 握手时按 WSTRB 修改内存，B 可延迟；读按 ARLEN/ARSIZE
  * /ARBURST 逐拍返回，覆盖 DCache 行回填、8B 窄传输和非缓存 MMIO。
  *
  * 本测试不依赖 RTL 内部信号，只看 LSU 退休的 LoadData 与最终 AXI 内存，
  * 因此出错即表示架构可见的数据/顺序错误或挂死。
  */
class ysyx_26030103_LSUMixedAxiAuditTest extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def big(value: UInt): BigInt = value.peek().litValue

  private val LR = BigInt(0x02)
  private val SC = BigInt(0x03)
  private val AMOADD = BigInt(0x00)
  private val AMOSWAP = BigInt(0x01)

  private case class MemOp(
      addr: Long,
      write: Boolean,
      width: Int,
      data: BigInt,
      signed: Boolean,
      atomicOp: Option[BigInt] = None
  )

  private case class OutEvent(
      data: BigInt,
      retire: Boolean,
      load: Boolean,
      pc: BigInt
  )

  /** 单读槽 + 单写事务的 AXI 内存从设备。 */
  private class AxiMemory(dut: ysyx_26030103_LSU, readLatency: Int = 2,
      bLatency: Int = 3) {
    private val bytes = mutable.Map.empty[Long, Int]

    private case class ReadTxn(
        base: Long,
        len: Int,
        size: Int,
        burst: Int,
        id: BigInt
    )
    private case class WriteTxn(
        base: Long,
        len: Int,
        size: Int,
        burst: Int,
        id: BigInt
    )

    private var readTxn: Option[ReadTxn] = None
    private var readDelay = 0
    private var readBeat = 0
    private var writeTxn: Option[WriteTxn] = None
    private val wBeats = mutable.ArrayBuffer.empty[(BigInt, Int, Boolean)]
    private var appliedBeats = 0
    private var bPending = false
    private var bDelay = 0
    private var bResp = 0
    private var readRespError: Option[(Long, Int, Int)] = None

    val outputs = mutable.ArrayBuffer.empty[OutEvent]
    var arCount = 0
    var awCount = 0

    def setReadError(addr: Long, beat: Int, resp: Int): Unit =
      readRespError = Some((addr & ~3L, beat, resp))
    def clearReadError(): Unit = readRespError = None

    private def wordAt(addr: Long): BigInt = {
      val base = addr & ~3L
      var value = BigInt(0)
      for (lane <- 0 until 4) {
        value |= BigInt(bytes.getOrElse(base + lane, 0)) << (8 * lane)
      }
      value
    }

    def readWord(addr: Long): BigInt = wordAt(addr)
    def writeWord(addr: Long, value: BigInt): Unit =
      writeBytes(addr, value, 0xf)
    def readByte(addr: Long): Int = bytes.getOrElse(addr, 0)
    def peekByte(addr: Long): Int = readByte(addr)

    private def writeBytes(addr: Long, data: BigInt, strb: Int): Unit = {
      for (lane <- 0 until 4 if ((strb >> lane) & 1) != 0) {
        bytes(addr + lane) = ((data >> (8 * lane)) & 0xff).toInt
      }
    }

    private def applyBeat(beat: Int): Unit = {
      if (beat < wBeats.size && beat >= appliedBeats) {
        writeTxn.foreach { txn =>
          val addr =
            txn.base + (if (txn.burst == 1) beat.toLong * (1L << txn.size)
                        else 0L)
          val (data, strb, _) = wBeats(beat)
          writeBytes(addr, data, strb)
        }
        appliedBeats = beat + 1
      }
    }

    private def maybeScheduleB(): Unit = {
      writeTxn.foreach { txn =>
        val expected = txn.len + 1
        val gotLast = wBeats.nonEmpty && wBeats.last._3
        if (!bPending && (wBeats.size >= expected || gotLast)) {
          bPending = true
          bDelay = bLatency
          bResp = if (wBeats.size >= expected && gotLast) 0 else 2
        }
      }
    }

    def tick(): Unit = {
      val bus = dut.io.DataBus
      val arReady = readTxn.isEmpty
      val rValid = readTxn.nonEmpty && readDelay == 0
      val awReady = writeTxn.isEmpty && !bPending
      val wReady = !bPending
      val bValid = bPending && bDelay == 0

      bus.AR.ARREADY.poke(arReady.B)
      bus.R.RVALID.poke(rValid.B)
      bus.AW.AWREADY.poke(awReady.B)
      bus.W.WREADY.poke(wReady.B)
      bus.B.BVALID.poke(bValid.B)
      bus.B.BID.poke(writeTxn.map(_.id.U).getOrElse(0.U))
      bus.B.BRESP.poke(bResp.U)
      bus.R.RID.poke(readTxn.map(_.id.U).getOrElse(0.U))

      var rData = BigInt(0)
      var rResp = 0
      var rLast = false
      readTxn.foreach { txn =>
        val addr =
          txn.base + (if (txn.burst == 1) readBeat.toLong * (1L << txn.size)
                      else 0L)
        val aligned = addr & ~3L
        rData = wordAt(aligned)
        rLast = readBeat == txn.len
        readRespError.foreach { case (errAddr, errBeat, resp) =>
          if (aligned == errAddr && readBeat == errBeat) rResp = resp
        }
        bus.R.RID.poke(txn.id.U)
        bus.R.RDATA.poke(rData.U(32.W))
        bus.R.RRESP.poke(rResp.U)
        bus.R.RLAST.poke(rLast.B)
      }

      val arFire = arReady && bool(bus.AR.ARVALID)
      val rFire = rValid && bool(bus.R.RREADY)
      val awFire = awReady && bool(bus.AW.AWVALID)
      val wFire = wReady && bool(bus.W.WVALID)
      val bFire = bValid && bool(bus.B.BREADY)
      // 与生产顶层一致：原子写完成事件同拍清空 DCache，供随后 load 看到
      // 新值；非原子事务 AtomicCommit 恒低，不影响其它用例。
      dut.io.DCacheFlush.poke(bool(dut.io.AtomicCommit).B)

      val arAddr = if (arFire) bus.AR.ARADDR.peek().litValue.toLong else 0L
      val arLen = if (arFire) bus.AR.ARLEN.peek().litValue.toInt else 0
      val arSize = if (arFire) bus.AR.ARSIZE.peek().litValue.toInt else 0
      val arBurst = if (arFire) bus.AR.ARBURST.peek().litValue.toInt else 0
      val arId = if (arFire) bus.AR.ARID.peek().litValue else BigInt(0)
      val awAddr = if (awFire) bus.AW.AWADDR.peek().litValue.toLong else 0L
      val awLen = if (awFire) bus.AW.AWLEN.peek().litValue.toInt else 0
      val awSize = if (awFire) bus.AW.AWSIZE.peek().litValue.toInt else 0
      val awBurst = if (awFire) bus.AW.AWBURST.peek().litValue.toInt else 0
      val awId = if (awFire) bus.AW.AWID.peek().litValue else BigInt(0)
      val wData = if (wFire) bus.W.WDATA.peek().litValue else BigInt(0)
      val wStrb = if (wFire) bus.W.WSTRB.peek().litValue.toInt else 0
      val wLast = if (wFire) bool(bus.W.WLAST) else false
      val bId = if (bFire) bus.B.BID.peek().litValue else BigInt(0)
      val bRespValue = if (bFire) bus.B.BRESP.peek().litValue.toInt else 0

      if (bool(dut.io.out.valid) && bool(dut.io.out.ready)) {
        outputs += OutEvent(
          big(dut.io.out.bits.LoadData),
          bool(dut.io.out.bits.Retire),
          bool(dut.io.out.bits.MemoryValid) && !bool(dut.io.out.bits.MemoryWrite),
          big(dut.io.out.bits.pc)
        )
      }

      dut.clock.step()

      if (arFire) {
        arCount += 1
        readTxn = Some(ReadTxn(arAddr, arLen, arSize, arBurst, arId))
        readDelay = readLatency
        readBeat = 0
      }
      if (rValid && rFire) {
        readTxn.foreach { txn =>
          if (readBeat == txn.len) readTxn = None
          else readBeat += 1
        }
      }
      if (readTxn.nonEmpty && readDelay > 0) readDelay -= 1

      if (awFire) {
        awCount += 1
        writeTxn = Some(WriteTxn(awAddr, awLen, awSize, awBurst, awId))
        appliedBeats = 0
        for (i <- 0 until wBeats.size) applyBeat(i)
      }
      if (wFire) {
        wBeats += ((wData, wStrb, wLast))
        if (writeTxn.nonEmpty) applyBeat(wBeats.size - 1)
      }
      maybeScheduleB()
      if (bPending && bDelay > 0) bDelay -= 1
      if (bFire) {
        bPending = false
        bDelay = 0
        writeTxn = None
        wBeats.clear()
        appliedBeats = 0
        bResp = 0
      }
    }
  }

  private def resetDut(
      dut: ysyx_26030103_LSU,
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
    dut.io.DCacheDmaSnoopValid.poke(false.B)
    dut.io.DCacheDmaSnoopStart.poke(0.U)
    dut.io.DCacheDmaSnoopEnd.poke(0.U)
    dut.io.ViptPAddr.poke(0.U)
    dut.io.ViptPAddrValid.poke(false.B)
    dut.io.ViptPAddrVaddr.poke(0.U)
    dut.io.ViptKill.poke(false.B)
    dut.io.PmpFault.poke(false.B)
  }

  private def pokeOp(dut: ysyx_26030103_LSU, op: MemOp, pc: Long): Unit = {
    val addrWidth = dut.io.in.bits.ALUResult.getWidth
    dut.io.in.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.in.bits)))
    dut.io.in.bits.Retire.poke(true.B)
    dut.io.in.bits.RegisterWrite.poke((!op.write || op.atomicOp.isDefined).B)
    dut.io.in.bits.WBSelect.poke(1.U(2.W))
    dut.io.in.bits.Rd.poke(5.U)
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(op.write.B)
    dut.io.in.bits.IsAtomic.poke(op.atomicOp.isDefined.B)
    dut.io.in.bits.AtomicOp.poke(op.atomicOp.getOrElse(BigInt(0)).U(5.W))
    dut.io.in.bits.ALUResult.poke(op.addr.U(addrWidth.W))
    dut.io.in.bits.VAddr.poke(op.addr.U(addrWidth.W))
    dut.io.in.bits.WidthSelect.poke(op.width.U(2.W))
    dut.io.in.bits.LoadSigned.poke(op.signed.B)
    dut.io.in.bits.StoreData.poke(op.data.U(64.W))
    dut.io.in.bits.pc.poke(pc.U(addrWidth.W))
    dut.io.in.valid.poke(true.B)
  }

  /** 发起一条访存直到被 LSU 受理；期间继续推进 AXI 从设备。 */
  private def issue(
      dut: ysyx_26030103_LSU,
      model: AxiMemory,
      op: MemOp,
      pc: Long,
      limit: Int = 200
  ): Unit = {
    pokeOp(dut, op, pc)
    var cycles = 0
    while (!bool(dut.io.in.ready) && cycles < limit) {
      model.tick(); cycles += 1
    }
    withClue(s"LSU 未在 $limit 拍内受理 0x${op.addr.toHexString}: ") {
      bool(dut.io.in.ready) should be(true)
    }
    // 受理拍也算一次 AXI 推进。
    model.tick()
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.in.bits)))
  }

  private def waitOutputs(
      dut: ysyx_26030103_LSU,
      model: AxiMemory,
      count: Int,
      limit: Int = 2000
  ): Seq[OutEvent] = {
    val start = model.outputs.size
    var cycles = 0
    while (model.outputs.size < start + count && cycles < limit) {
      model.tick(); cycles += 1
    }
    withClue(s"$count 条访存未在 $limit 拍内退休: ") {
      (model.outputs.size - start) should be >= count
    }
    model.outputs.slice(start, start + count).toSeq
  }

  private def bytesOf(value: BigInt, count: Int): Vector[Int] =
    (0 until count).map(i => ((value >> (8 * i)) & 0xff).toInt).toVector

  private def expectedLoad(model: AxiMemory, op: MemOp, xlen: Int): BigInt = {
    val size = 1 << op.width
    val raw = (0 until size).map(i => model.readByte(op.addr + i))
    def assemble: BigInt = {
      var v = BigInt(0)
      for (i <- raw.indices) v |= BigInt(raw(i)) << (8 * i)
      v
    }
    val value = op.width match {
      case 0 =>
        if (op.signed) BigInt(raw(0).toByte) else BigInt(raw(0))
      case 1 =>
        val u = BigInt(raw(0)) | (BigInt(raw(1)) << 8)
        if (op.signed) BigInt(u.toShort) else u
      case 2 =>
        val u = assemble
        if (op.signed) BigInt(u.toInt) else u
      case _ => assemble
    }
    value & ((BigInt(1) << xlen) - 1)
  }

  private def runRandom(
      xlen: Int,
      seed: Int,
      count: Int,
      includeWide: Boolean,
      storage: ysyx_26030103_CacheStorage = ysyx_26030103_CacheStorage.Reg,
      dcacheEnable: Boolean = true,
      blockLog2: Int = 4,
      indexBits: Int = 2
  ): Unit = {
    simulate(
      new ysyx_26030103_LSU(
        XLEN = xlen,
        DCacheEnable = dcacheEnable,
        DCacheStorage = storage,
        DCacheBlockSizeLog2 = blockLog2,
        DCacheIndexBits = indexBits
      )
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val model = new AxiMemory(dut)
      val rng = new scala.util.Random(seed)

      // 初始化两个 PMA 区域，供 cacheable/non-cacheable 访存使用。
      val cacheAddrBase = 0x80000000L
      val mmioAddrBase = 0x10000000L
      for (i <- 0 until 4096) {
        model.writeWord(
          cacheAddrBase + i.toLong * 4,
          BigInt((i.toLong * 0x9e3779b1L + 0x12345678L) & 0xffffffffL)
        )
      }
      for (i <- 0 until 128) {
        model.writeWord(
          mmioAddrBase + i.toLong * 4,
          BigInt((i.toLong * 0x85ebca6bL + 0x0badf00dL) & 0xffffffffL)
        )
      }

      def pickOp(): MemOp = {
        val mmio = rng.nextInt(5) == 0
        val write = rng.nextBoolean()
        val width =
          if (includeWide && rng.nextInt(6) == 0) 3
          else rng.nextInt(3)
        val base = if (mmio) mmioAddrBase else cacheAddrBase
        val span = if (mmio) 0x100L else 0x2000L
        val widthBytes = 1 << width
        val alignMask = (widthBytes - 1).toLong
        var addr = base + ((rng.nextInt(span.toInt).toLong) & ~alignMask)
        if (width == 3) addr = addr & ~7L
        val data = BigInt(64, rng)
        val signed = rng.nextBoolean()
        MemOp(addr, write, width, data, signed)
      }

      var pc = 0x80000000L
      for (i <- 0 until count) {
        val op = pickOp()
        val before = model.outputs.size
        issue(dut, model, op, pc)
        val outs = waitOutputs(dut, model, 1)
        val ev = outs.head
        withClue(
          s"seq=$i op=${if (op.write) "store" else "load"} " +
            s"addr=0x${op.addr.toHexString} width=${op.width} " +
            s"retire=${ev.retire}: ") {
          ev.retire should be(true)
        }
        if (!op.write) {
          val expected = expectedLoad(model, op, xlen)
          withClue(
            s"seq=$i load addr=0x${op.addr.toHexString} " +
              s"width=${op.width} signed=${op.signed} " +
              s"got=0x${ev.data.toString(16)} " +
              s"expected=0x${expected.toString(16)}: ") {
            ev.data should be(expected)
          }
        }
        model.outputs.remove(before, model.outputs.size - before)
        pc += 4
        // 每 16 笔强制制造一次背靠背：不等待上一条 store 的 B 就发下一条
        // store。这里用随机序列本身已经会因为 store 写缓冲而覆盖。
      }
    }
  }

  "LSU+DCache" should
    "RV32 cacheable/non-cacheable 随机 load/store 与 AXI 内存 golden 一致" in {
    runRandom(xlen = 32, seed = 0x13572468, count = 120,
      includeWide = false)
  }

  it should
    "RV64 cacheable/non-cacheable 含 8B 窄传输的随机 load/store 与 golden 一致" in {
    runRandom(xlen = 64, seed = 0x24681357, count = 100,
      includeWide = true)
  }

  it should
    "RV32 SRAM DCache 的随机 load/store 与 golden 一致（同步读响应路径）" in {
    runRandom(xlen = 32, seed = 0x0badcafe, count = 80,
      includeWide = false, storage = ysyx_26030103_CacheStorage.SRAM)
  }

  it should
    "RV64 no-bypass SRAM DCache 含 8B 的随机 load/store 与 golden 一致" in {
    runRandom(xlen = 64, seed = 0x0ddba11, count = 60,
      includeWide = true,
      storage = ysyx_26030103_CacheStorage.SRAMNoBypass)
  }

  it should
    "RV32 4B line 的随机 load/store 与 golden 一致（单 word 行）" in {
    runRandom(xlen = 32, seed = 0x4b4b4b, count = 60,
      includeWide = false, blockLog2 = 2, indexBits = 4)
  }

  it should
    "RV64 8B line 的随机 load/store 与 golden 一致（最小行宽 8B 访问）" in {
    runRandom(xlen = 64, seed = 0x8b8b8b, count = 60,
      includeWide = true, blockLog2 = 3, indexBits = 3)
  }

  it should
    "RV32 64B line 的随机 load/store 与 golden 一致（16 beat 回填）" in {
    runRandom(xlen = 32, seed = 0x64b64b, count = 60,
      includeWide = false, blockLog2 = 6, indexBits = 2)
  }

  it should
    "RV64 无 DCache 的随机 load/store（含 8B 直读）与 golden 一致" in {
    runRandom(xlen = 64, seed = 0x5150abcd, count = 60,
      includeWide = true, dcacheEnable = false)
  }

  it should "连续 store 在 B 延迟下保持顺序，随后 load 读到最新值" in {
    simulate(
      new ysyx_26030103_LSU(
        XLEN = 32,
        DCacheEnable = true,
        WBufDepth = 4,
        DCacheBlockSizeLog2 = 4,
        DCacheIndexBits = 2
      )
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val model = new AxiMemory(dut, readLatency = 3, bLatency = 7)
      val base = 0x80001000L
      for (i <- 0 until 32) model.writeWord(base + i.toLong * 4, BigInt(i))

      val ops = (0 until 4).map { i =>
        MemOp(base + i.toLong * 4, write = true, width = 2,
          data = BigInt(0xa5000000L + i), signed = false)
      }
      for ((op, i) <- ops.zipWithIndex) issue(dut, model, op, 0x80000000L + i)
      val events = waitOutputs(dut, model, 4)
      all(events.map(_.retire)) should be(true)
      // 等待写缓冲完全排空后核对内存。
      for (_ <- 0 until 30) model.tick()
      for ((op, i) <- ops.zipWithIndex) {
        model.readWord(op.addr) should be(BigInt(0xa5000000L + i))
      }

      val loadOps = (0 until 4).map { i =>
        MemOp(base + i.toLong * 4, write = false, width = 2,
          data = BigInt(0), signed = false)
      }
      // load 不能像 store 一样进入连续队列，必须逐条等待退休；否则它们
      // 会在前一条 load 完成前被 in.ready 反压，测试自己会漏采输出事件。
      for ((op, i) <- loadOps.zipWithIndex) {
        issue(dut, model, op, 0x80000100L + i)
        val ev = waitOutputs(dut, model, 1).head
        withClue(s"连续 store 后 load[$i] 数据错误: ") {
          ev.data should be(BigInt(0xa5000000L + i))
        }
      }
    }
  }

  it should
    "LR/SC/AMO 与普通 store 的 reservation 规则、返回值及 DCache 失效一致" in {
    simulate(
      new ysyx_26030103_LSU(
        XLEN = 64,
        DCacheEnable = true,
        DCacheBlockSizeLog2 = 4,
        DCacheIndexBits = 3
      )
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val model = new AxiMemory(dut, readLatency = 2, bLatency = 3)
      val addr = 0x80000200L
      val other = 0x80000240L
      model.writeWord(addr, BigInt(0x11223344L))
      model.writeWord(other, BigInt(0x55667788L))

      def runAtomic(op: BigInt, a: Long, write: Boolean, data: BigInt,
          width: Int = 2): BigInt = {
        issue(dut, model, MemOp(a, write, width, data, false, Some(op)),
          0x80000000L)
        val ev = waitOutputs(dut, model, 1).head
        withClue(s"原子 op=0x${op.toString(16)} 未退休: ") {
          ev.retire should be(true)
        }
        ev.data
      }

      // 先填充 DCache 行，确保后面的 load 可能命中旧行；若 AMO/SC 后未
      // 失效，就会读回旧值。
      issue(dut, model, MemOp(addr, false, 2, 0, false), 0x80000000L)
      waitOutputs(dut, model, 1).head.data should be(BigInt(0x11223344L))

      runAtomic(LR, addr, write = false, 0) should be(BigInt(0x11223344L))
      runAtomic(SC, addr, write = true, BigInt(0xcafebabeL)) should be(
        BigInt(0))
      model.readWord(addr) should be(BigInt(0xcafebabeL))
      issue(dut, model, MemOp(addr, false, 2, 0, false), 0x80000004L)
      withClue("SC 成功后 DCache 仍返回旧值: ") {
        waitOutputs(dut, model, 1).head.data should be(BigInt(0xcafebabeL))
      }

      // 中间插入任何普通 store 都必须清除 reservation，SC 返回 1 且
      // 不得修改内存。
      runAtomic(LR, other, write = false, 0) should be(BigInt(0x55667788L))
      issue(dut, model, MemOp(addr, true, 2, BigInt(0x0badf00dL), false),
        0x80000008L)
      waitOutputs(dut, model, 1).head.retire should be(true)
      runAtomic(SC, other, write = true, BigInt(0x77777777L)) should be(
        BigInt(1))
      model.readWord(other) should be(BigInt(0x55667788L))

      // AMOADD 返回旧值、写回旧值+rhs，并让后续 load 看到新值。
      val old = runAtomic(AMOADD, addr, write = true, BigInt(5))
      old should be(BigInt(0x0badf00dL))
      model.readWord(addr) should be(BigInt(0x0badf012L))
      issue(dut, model, MemOp(addr, false, 2, 0, false), 0x8000000cL)
      waitOutputs(dut, model, 1).head.data should be(BigInt(0x0badf012L))

      // 8 字节 AMO.SWAP.D 的旧值/新值与 DCache 失效。
      val dAddr = 0x80000280L
      model.writeWord(dAddr, BigInt("01234567", 16))
      model.writeWord(dAddr + 4, BigInt("89abcdef", 16))
      val oldD = runAtomic(AMOSWAP, dAddr, write = true,
        BigInt("0f1e2d3c4b5a6978", 16), width = 3)
      oldD should be(BigInt("89abcdef01234567", 16))
      model.readWord(dAddr) should be(BigInt("4b5a6978", 16))
      model.readWord(dAddr + 4) should be(BigInt("0f1e2d3c", 16))
      issue(dut, model, MemOp(dAddr, false, 3, 0, false), 0x80000010L)
      waitOutputs(dut, model, 1).head.data should be(
        BigInt("0f1e2d3c4b5a6978", 16))
    }
  }

  it should "store 命中行后同地址 load 读到新值，且非缓存 alias 不干扰" in {
    simulate(
      new ysyx_26030103_LSU(
        XLEN = 32,
        DCacheEnable = true,
        DCacheBlockSizeLog2 = 4,
        DCacheIndexBits = 3
      )
    ) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val model = new AxiMemory(dut, readLatency = 1, bLatency = 1)
      val addr = 0x80000424L
      model.writeWord(addr, BigInt(0x11223344L))

      // 先 load 填充缓存行，再 store 同地址，再 load 校验写直达镜像。
      issue(dut, model, MemOp(addr, false, 2, 0, false), 0x80000000L)
      waitOutputs(dut, model, 1).head.data should be(BigInt(0x11223344L))

      issue(dut, model, MemOp(addr, true, 2, BigInt(0xcafebabeL), false),
        0x80000004L)
      waitOutputs(dut, model, 1).head.retire should be(true)

      issue(dut, model, MemOp(addr, false, 2, 0, false), 0x80000008L)
      val value = waitOutputs(dut, model, 1).head.data
      withClue(s"store 后 load 返回 0x${value.toString(16)}: ") {
        value should be(BigInt(0xcafebabeL))
      }
      model.readWord(addr) should be(BigInt(0xcafebabeL))
    }
  }
}
