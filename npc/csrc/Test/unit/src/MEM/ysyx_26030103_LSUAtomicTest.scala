package ysyx_26030103.mem

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** LSU A 扩展定向测试：LR/SC 预约、全部 AMO 运算、.W/.D 宽度以及
  * 非对齐/PMP 故障无总线副作用。原子通路直接驱动 AXI AR/R 与 AW/W/B，
  * 与顶层真实总线时序一致。
  */
class ysyx_26030103_LSUAtomicTest extends AnyFlatSpec with Matchers {
  // funct5 编码（与 ysyx_26030103_AtomicOp 一致）。
  private val LR = BigInt(0x02)
  private val SC = BigInt(0x03)
  private val AMOADD = BigInt(0x00)
  private val AMOSWAP = BigInt(0x01)
  private val AMOXOR = BigInt(0x04)
  private val AMOOR = BigInt(0x08)
  private val AMOAND = BigInt(0x0c)
  private val AMOMIN = BigInt(0x10)
  private val AMOMAX = BigInt(0x14)
  private val AMOMINU = BigInt(0x18)
  private val AMOMAXU = BigInt(0x1c)

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long = value.peek().litValue.toLong
  private def big(value: UInt): BigInt = value.peek().litValue

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
    dut.io.ViptPAddr.poke(0.U)
    dut.io.ViptPAddrValid.poke(false.B)
    dut.io.ViptPAddrVaddr.poke(0.U)
    dut.io.ViptKill.poke(false.B)
    dut.io.PmpFault.poke(false.B)
    dut.io.DataBus.AW.AWREADY.poke(false.B)
    dut.io.DataBus.W.WREADY.poke(false.B)
    dut.io.DataBus.B.BVALID.poke(false.B)
    dut.io.DataBus.B.BID.poke(0.U)
    dut.io.DataBus.B.BRESP.poke(0.U)
    dut.io.DataBus.AR.ARREADY.poke(false.B)
    dut.io.DataBus.R.RVALID.poke(false.B)
    dut.io.DataBus.R.RID.poke(0.U)
    dut.io.DataBus.R.RDATA.poke(0.U)
    dut.io.DataBus.R.RRESP.poke(0.U)
    dut.io.DataBus.R.RLAST.poke(false.B)
  }

  private def pokeAtomic(
      dut: ysyx_26030103_LSU,
      op: BigInt,
      address: Long,
      rhs: BigInt,
      widthSelect: Int,
      memoryWrite: Boolean
  ): Unit = {
    val addrWidth = dut.io.in.bits.ALUResult.getWidth
    dut.io.in.bits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.in.bits)))
    dut.io.in.bits.Retire.poke(true.B)
    dut.io.in.bits.RegisterWrite.poke(true.B)
    dut.io.in.bits.WBSelect.poke(1.U(2.W))
    dut.io.in.bits.Rd.poke(5.U)
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(if (memoryWrite) true.B else false.B)
    dut.io.in.bits.IsAtomic.poke(true.B)
    dut.io.in.bits.AtomicOp.poke(op.U(5.W))
    dut.io.in.bits.ALUResult.poke(address.U(addrWidth.W))
    dut.io.in.bits.VAddr.poke(address.U(addrWidth.W))
    dut.io.in.bits.WidthSelect.poke(widthSelect.U(2.W))
    dut.io.in.bits.StoreData.poke(rhs.U(dut.io.in.bits.StoreData.getWidth.W))
    dut.io.in.bits.pc.poke(0x80000000L.U(addrWidth.W))
  }

  private def waitFire(
      dut: ysyx_26030103_LSU,
      clock: Clock,
      limit: Int = 20
  ): Unit = {
    var cycles = 0
    while (!(bool(dut.io.in.valid) && bool(dut.io.in.ready)) &&
        cycles < limit) {
      clock.step(); cycles += 1
    }
    withClue("input did not fire") { cycles should be < limit }
    // 受理拍结束后立即撤销 valid，避免同一条原子指令在下游完成时被
    // 再次接收（测试里下一次 poke 之前流水可能已经空闲）。
    clock.step()
    dut.io.in.valid.poke(false.B)
  }

  /** 完成一笔原子读；wide 时按两拍返回。 */
  private def doAtomicRead(
      dut: ysyx_26030103_LSU,
      clock: Clock,
      address: Long,
      wide: Boolean,
      low: BigInt,
      high: BigInt = BigInt(0)
  ): Unit = {
    var cycles = 0
    while (!bool(dut.io.DataBus.AR.ARVALID) && cycles < 30) {
      clock.step(); cycles += 1
    }
    bool(dut.io.DataBus.AR.ARVALID) should be(true)
    long(dut.io.DataBus.AR.ARADDR) should be(address)
    long(dut.io.DataBus.AR.ARLEN) should be(if (wide) 1L else 0L)
    dut.io.DataBus.AR.ARREADY.poke(true.B)
    clock.step()
    dut.io.DataBus.AR.ARREADY.poke(false.B)

    dut.io.DataBus.R.RVALID.poke(true.B)
    dut.io.DataBus.R.RRESP.poke(0.U)
    dut.io.DataBus.R.RDATA.poke(low.U(32.W))
    dut.io.DataBus.R.RLAST.poke(!wide)
    clock.step()
    if (wide) {
      dut.io.DataBus.R.RDATA.poke(high.U(32.W))
      dut.io.DataBus.R.RLAST.poke(true.B)
      clock.step()
    }
    dut.io.DataBus.R.RVALID.poke(false.B)
    dut.io.DataBus.R.RLAST.poke(false.B)
  }

  /** 完成原子写并检查 AW/W 数据。返回写出的完整值。 */
  private def finishAtomicWrite(
      dut: ysyx_26030103_LSU,
      clock: Clock,
      address: Long,
      wide: Boolean
  ): BigInt = {
    dut.io.DataBus.AW.AWREADY.poke(true.B)
    dut.io.DataBus.W.WREADY.poke(true.B)
    var awSeen = false
    var first = BigInt(0)
    var second = BigInt(0)
    var beats = 0
    var cycles = 0
    while (beats < (if (wide) 2 else 1) && cycles < 40) {
      if (bool(dut.io.DataBus.AW.AWVALID)) {
        awSeen = true
        long(dut.io.DataBus.AW.AWADDR) should be(address)
        long(dut.io.DataBus.AW.AWLEN) should be(if (wide) 1L else 0L)
      }
      if (bool(dut.io.DataBus.W.WVALID)) {
        if (beats == 0) first = big(dut.io.DataBus.W.WDATA)
        else second = big(dut.io.DataBus.W.WDATA)
        if (wide) {
          bool(dut.io.DataBus.W.WLAST) should be(beats == 1)
        } else {
          bool(dut.io.DataBus.W.WLAST) should be(true)
        }
        beats += 1
      }
      clock.step(); cycles += 1
    }
    awSeen should be(true)
    beats should be(if (wide) 2 else 1)
    dut.io.DataBus.AW.AWREADY.poke(false.B)
    dut.io.DataBus.W.WREADY.poke(false.B)
    dut.io.DataBus.B.BVALID.poke(true.B)
    dut.io.DataBus.B.BID.poke(0.U)
    dut.io.DataBus.B.BRESP.poke(0.U)
    clock.step()
    dut.io.DataBus.B.BVALID.poke(false.B)
    if (wide) (second << 32) | first else first
  }

  private def waitOut(
      dut: ysyx_26030103_LSU,
      clock: Clock,
      limit: Int = 30
  ): BigInt = {
    var cycles = 0
    var data = BigInt(0)
    var done = false
    while (!done && cycles < limit) {
      if (bool(dut.io.out.valid)) {
        done = true
        data = big(dut.io.out.bits.LoadData)
        bool(dut.io.out.bits.Retire) should be(true)
      }
      clock.step(); cycles += 1
    }
    withClue("output did not retire") { done should be(true) }
    data
  }

  private def expectNoBus(dut: ysyx_26030103_LSU): Unit = {
    bool(dut.io.DataBus.AR.ARVALID) should be(false)
    bool(dut.io.DataBus.AW.AWVALID) should be(false)
    bool(dut.io.DataBus.W.WVALID) should be(false)
  }

  /** SC 失败路径：等待退休，同时每一拍都确认没有 AR/AW/W。 */
  private def waitScFail(
      dut: ysyx_26030103_LSU,
      clock: Clock,
      limit: Int = 20
  ): BigInt = {
    var cycles = 0
    var result: Option[BigInt] = None
    while (result.isEmpty && cycles < limit) {
      expectNoBus(dut)
      if (bool(dut.io.out.valid)) {
        bool(dut.io.out.bits.Retire) should be(true)
        result = Some(big(dut.io.out.bits.LoadData))
      }
      clock.step(); cycles += 1
    }
    withClue("SC failure did not retire") { result.isDefined should be(true) }
    result.get
  }

  "RV32 LSU A extension" should "LR 后同地址 SC 成功并写回内存" in {
    simulate(new ysyx_26030103_LSU(XLEN = 32)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val addr = 0x80004000L
      pokeAtomic(dut, LR, addr, 0, 2, memoryWrite = false)
      dut.io.in.valid.poke(true.B)
      waitFire(dut, clock)
      doAtomicRead(dut, clock, addr, wide = false, low = BigInt("89abcdef", 16))
      waitOut(dut, clock) should be(BigInt("89abcdef", 16))
      dut.io.in.valid.poke(false.B)

      pokeAtomic(dut, SC, addr, BigInt("13579bdf", 16), 2, memoryWrite = true)
      dut.io.in.valid.poke(true.B)
      waitFire(dut, clock)
      finishAtomicWrite(dut, clock, addr, wide = false) should be(
        BigInt("13579bdf", 16))
      waitOut(dut, clock) should be(BigInt(0))
      dut.io.in.valid.poke(false.B)
    }
  }

  it should "SC 地址不匹配时失败且不访问内存" in {
    simulate(new ysyx_26030103_LSU(XLEN = 32)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val addr = 0x80004100L
      pokeAtomic(dut, LR, addr, 0, 2, memoryWrite = false)
      dut.io.in.valid.poke(true.B)
      waitFire(dut, clock)
      doAtomicRead(dut, clock, addr, wide = false, low = 0x11223344L)
      waitOut(dut, clock)
      dut.io.in.valid.poke(false.B)

      pokeAtomic(dut, SC, addr + 4, BigInt("55667788", 16), 2,
        memoryWrite = true)
      dut.io.in.valid.poke(true.B)
      waitFire(dut, clock)
      waitScFail(dut, clock) should be(BigInt(1))
      dut.io.in.valid.poke(false.B)
    }
  }

  it should "CSR/异常提交后 reservation 失效" in {
    simulate(new ysyx_26030103_LSU(XLEN = 32)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val addr = 0x80004200L
      pokeAtomic(dut, LR, addr, 0, 2, memoryWrite = false)
      dut.io.in.valid.poke(true.B)
      waitFire(dut, clock)
      doAtomicRead(dut, clock, addr, wide = false, low = 0x0badf00dL)
      waitOut(dut, clock)
      dut.io.in.valid.poke(false.B)

      dut.io.ReservationClear.poke(true.B)
      clock.step()
      dut.io.ReservationClear.poke(false.B)

      pokeAtomic(dut, SC, addr, 1, 2, memoryWrite = true)
      dut.io.in.valid.poke(true.B)
      waitFire(dut, clock)
      waitScFail(dut, clock) should be(BigInt(1))
      dut.io.in.valid.poke(false.B)
    }
  }

  it should "覆盖 RV32 全部 AMO 运算及 MIN/MAX 有符号边界" in {
    val cases = Seq(
      (AMOADD, BigInt(0x00000010L), BigInt(0x00000005L),
        BigInt(0x00000015L), BigInt(0x00000010L)),
      (AMOSWAP, BigInt(0x12345678L), BigInt(0x0abcdef0L),
        BigInt(0x0abcdef0L), BigInt(0x12345678L)),
      (AMOXOR, BigInt(0xffff0000L), BigInt(0x0ff00ff0L),
        BigInt(0xf00f0ff0L), BigInt(0xffff0000L)),
      (AMOAND, BigInt(0xffff0000L), BigInt(0x0ff00ff0L),
        BigInt(0x0ff00000L), BigInt(0xffff0000L)),
      (AMOOR, BigInt(0xffff0000L), BigInt(0x0000ffffL),
        BigInt(0xffffffffL), BigInt(0xffff0000L)),
      (AMOMIN, BigInt(0x80000000L), BigInt(0x7fffffffL),
        BigInt(0x80000000L), BigInt(0x80000000L)),
      (AMOMAX, BigInt(0x80000000L), BigInt(0x7fffffffL),
        BigInt(0x7fffffffL), BigInt(0x80000000L)),
      (AMOMINU, BigInt(0x80000000L), BigInt(0x7fffffffL),
        BigInt(0x7fffffffL), BigInt(0x80000000L)),
      (AMOMAXU, BigInt(0x80000000L), BigInt(0x7fffffffL),
        BigInt(0x80000000L), BigInt(0x80000000L))
    )
    for ((op, oldValue, rhs, newValue, result) <- cases) {
      simulate(new ysyx_26030103_LSU(XLEN = 32)) { dut =>
        val clock = dut.clock
        resetDut(dut, clock, dut.reset)
        val addr = 0x80004300L
        pokeAtomic(dut, op, addr, rhs, 2, memoryWrite = true)
        dut.io.in.valid.poke(true.B)
        waitFire(dut, clock)
        doAtomicRead(dut, clock, addr, wide = false,
          low = oldValue & 0xffffffffL)
        finishAtomicWrite(dut, clock, addr, wide = false) should be(newValue)
        waitOut(dut, clock) should be(result)
        dut.io.in.valid.poke(false.B)
      }
    }
  }

  it should "非对齐 LR/AMO 在发总线前产生 cause=4/6" in {
    for ((op, write, cause) <- Seq((LR, false, 4L), (AMOADD, true, 6L))) {
      simulate(new ysyx_26030103_LSU(XLEN = 32)) { dut =>
        val clock = dut.clock
        resetDut(dut, clock, dut.reset)
        pokeAtomic(dut, op, 0x80004402L, 1, 2, memoryWrite = write)
        dut.io.in.valid.poke(true.B)
        var trapped = false
        for (_ <- 0 until 12) {
          expectNoBus(dut)
          if (bool(dut.io.MemTrapCommit)) {
            trapped = true
            long(dut.io.MemTrapCause) should be(cause)
          }
          clock.step()
        }
        trapped should be(true)
        expectNoBus(dut)
      }
    }
  }

  it should "PMP 拒绝 AMO 时 cause=7 且不发出总线请求" in {
    simulate(new ysyx_26030103_LSU(XLEN = 32)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      pokeAtomic(dut, AMOADD, 0x80004500L, 1, 2, memoryWrite = true)
      dut.io.PmpFault.poke(true.B)
      dut.io.in.valid.poke(true.B)
      var trapped = false
      for (_ <- 0 until 12) {
        expectNoBus(dut)
        if (bool(dut.io.MemTrapCommit)) {
          trapped = true
          long(dut.io.MemTrapCause) should be(7L)
        }
        clock.step()
      }
      trapped should be(true)
      expectNoBus(dut)
    }
  }

  "RV64 LSU A extension" should "完成 AMO.D 8 字节读改写" in {
    simulate(new ysyx_26030103_LSU(XLEN = 64)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val addr = 0x80005000L
      val oldValue = BigInt("0123456789abcdef", 16)
      val rhs = BigInt("0000000011111111", 16)
      pokeAtomic(dut, AMOADD, addr, rhs, 3, memoryWrite = true)
      dut.io.in.valid.poke(true.B)
      waitFire(dut, clock)
      doAtomicRead(dut, clock, addr, wide = true,
        low = oldValue & 0xffffffffL, high = (oldValue >> 32) & 0xffffffffL)
      finishAtomicWrite(dut, clock, addr, wide = true) should be(oldValue + rhs)
      waitOut(dut, clock) should be(oldValue)
      dut.io.in.valid.poke(false.B)
    }
  }

  it should "对 AMO.W 结果按 bit31 符号扩展到 RV64" in {
    simulate(new ysyx_26030103_LSU(XLEN = 64)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val addr = 0x80005008L
      pokeAtomic(dut, AMOADD, addr, 2, 2, memoryWrite = true)
      dut.io.in.valid.poke(true.B)
      waitFire(dut, clock)
      doAtomicRead(dut, clock, addr, wide = false, low = 0x7fffffffL)
      finishAtomicWrite(dut, clock, addr, wide = false) should be(
        BigInt(0x80000001L))
      // rd 得到旧值 0x7fffffff（正数，符号扩展后仍是 0x7fffffff）。
      waitOut(dut, clock) should be(BigInt(0x7fffffffL))
      dut.io.in.valid.poke(false.B)
    }
  }

  it should "AMO.D MIN/MAX 有符号与无符号边界" in {
    simulate(new ysyx_26030103_LSU(XLEN = 64)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val addr = 0x80005800L
      val oldValue = BigInt("8000000000000000", 16)
      val rhs = BigInt("7fffffffffffffff", 16)
      val cases = Seq(
        (AMOMIN, oldValue),
        (AMOMAX, rhs),
        (AMOMINU, rhs),
        (AMOMAXU, oldValue)
      )
      for ((op, expectedNew) <- cases) {
        pokeAtomic(dut, op, addr, rhs, 3, memoryWrite = true)
        dut.io.in.valid.poke(true.B)
        waitFire(dut, clock)
        doAtomicRead(dut, clock, addr, wide = true,
          low = oldValue & 0xffffffffL,
          high = (oldValue >> 32) & 0xffffffffL)
        finishAtomicWrite(dut, clock, addr, wide = true) should be(expectedNew)
        waitOut(dut, clock) should be(oldValue)
      }
      dut.io.in.valid.poke(false.B)
    }
  }

  it should "LR.D/SC.D 使用 8 字节预约地址" in {
    simulate(new ysyx_26030103_LSU(XLEN = 64)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val addr = 0x80006000L
      pokeAtomic(dut, LR, addr, 0, 3, memoryWrite = false)
      dut.io.in.valid.poke(true.B)
      waitFire(dut, clock)
      doAtomicRead(dut, clock, addr, wide = true, low = 0x11223344L,
        high = 0x55667788L)
      waitOut(dut, clock) should be(BigInt("5566778811223344", 16))
      dut.io.in.valid.poke(false.B)

      pokeAtomic(dut, SC, addr, BigInt("99aabbccddeeff00", 16), 3,
        memoryWrite = true)
      dut.io.in.valid.poke(true.B)
      waitFire(dut, clock)
      finishAtomicWrite(dut, clock, addr, wide = true) should be(
        BigInt("99aabbccddeeff00", 16))
      waitOut(dut, clock) should be(BigInt(0))
      dut.io.in.valid.poke(false.B)
    }
  }

  it should "VIPT 并行下原子事务等待带标签 PA，翻译故障不产生总线副作用" in {
    simulate(new ysyx_26030103_LSU(
      XLEN = 32, DCacheVipt = true, ViptParallel = true)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val addr = 0x80007000L
      pokeAtomic(dut, AMOADD, addr, 1, 2, memoryWrite = true)
      dut.io.in.valid.poke(true.B)
      waitFire(dut, clock)
      // PA 尚未返回：必须保持 AtomicBusy，且绝不能提前发 AR。
      for (_ <- 0 until 6) {
        expectNoBus(dut)
        bool(dut.io.AtomicBusy) should be(true)
        clock.step()
      }
      // 翻译故障：撤销整笔原子事务。
      dut.io.ViptKill.poke(true.B)
      clock.step()
      dut.io.ViptKill.poke(false.B)
      for (_ <- 0 until 6) {
        expectNoBus(dut)
        bool(dut.io.out.valid) should be(false)
        clock.step()
      }
      dut.io.in.valid.poke(false.B)
    }
  }

  it should "总线不安静时原子事务不发 AR，避免 DMA 插入读改写" in {
    simulate(new ysyx_26030103_LSU(XLEN = 32)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      dut.io.BusQuiet.poke(false.B)
      pokeAtomic(dut, AMOSWAP, 0x80007100L, 0x55, 2, memoryWrite = true)
      dut.io.in.valid.poke(true.B)
      waitFire(dut, clock)
      for (_ <- 0 until 6) {
        expectNoBus(dut)
        bool(dut.io.AtomicBusy) should be(true)
        clock.step()
      }
      dut.io.BusQuiet.poke(true.B)
      var cycles = 0
      while (!bool(dut.io.DataBus.AR.ARVALID) && cycles < 12) {
        clock.step(); cycles += 1
      }
      bool(dut.io.DataBus.AR.ARVALID) should be(true)
      dut.io.in.valid.poke(false.B)
    }
  }

  it should "非对齐 AMO.D 产生 cause=6 且不访问内存" in {
    simulate(new ysyx_26030103_LSU(XLEN = 64)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      pokeAtomic(dut, AMOADD, 0x80006004L, 1, 3, memoryWrite = true)
      dut.io.in.valid.poke(true.B)
      var trapped = false
      for (_ <- 0 until 12) {
        expectNoBus(dut)
        if (bool(dut.io.MemTrapCommit)) {
          trapped = true
          long(dut.io.MemTrapCause) should be(6L)
        }
        clock.step()
      }
      trapped should be(true)
      expectNoBus(dut)
    }
  }
}
