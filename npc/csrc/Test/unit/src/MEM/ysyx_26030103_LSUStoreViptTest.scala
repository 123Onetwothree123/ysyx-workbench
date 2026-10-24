package ysyx_26030103.mem

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_EXUMessage

/** LSU 写缓冲的 VIPT store 并行路径测试。
  *
  * 验证 store 在 PA 返回前只登记 VA 对应的写缓冲项，绝不发 AW/W；PA 带
  * 匹配 VA 标签到达后才用物理地址提交。另覆盖旧/错误 PA 标签拒绝、翻译
  * 故障整体撤销写缓冲，以及同地址 load 必须等 store 的 B 响应后才能看到
  * 更新后的 DCache 镜像。
  */
class ysyx_26030103_LSUStoreViptTest extends AnyFlatSpec with Matchers {
  private val AddrW = 32
  private val Va    = 0x00001050L
  private val Va2   = 0x00001060L
  private val Va3   = 0x00001070L
  private val Pa    = 0x80002000L
  private val Pa2   = 0x80003000L
  private val Pa3   = 0x80004000L

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long    = value.peek().litValue.toLong

  private def wordOf(line: Long, beat: Int): Long =
    ((line + beat.toLong * 4L) ^ 0x5a5a0000L) & 0xffffffffL

  private def waitUntil(clock: Clock, cond: => Boolean, message: String): Unit = {
    var cycles = 0
    while (!cond && cycles < 500) {
      clock.step()
      cycles += 1
    }
    withClue(s"$message（等待 $cycles 拍）") { cond should be(true) }
  }

  private def pokeLoad(dut: ysyx_26030103_LSU, vaddr: Long): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(false.B)
    dut.io.in.bits.ALUResult.poke(vaddr.U(AddrW.W))
    dut.io.in.bits.VAddr.poke(vaddr.U(AddrW.W))
    dut.io.in.bits.WidthSelect.poke(2.U(2.W))
    dut.io.in.bits.pc.poke(0x1000.U(AddrW.W))
  }

  private def pokeStore(
    dut:  ysyx_26030103_LSU,
    vaddr: Long,
    data: Long
  ): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(true.B)
    dut.io.in.bits.ALUResult.poke(vaddr.U(AddrW.W))
    dut.io.in.bits.VAddr.poke(vaddr.U(AddrW.W))
    dut.io.in.bits.WidthSelect.poke(2.U(2.W))
    dut.io.in.bits.StoreData.poke(data.U(32.W))
    dut.io.in.bits.pc.poke(0x2000.U(AddrW.W))
  }

  private def acceptInput(dut: ysyx_26030103_LSU, clock: Clock): Unit = {
    dut.io.in.valid.poke(true.B)
    waitUntil(clock, bool(dut.io.in.ready), "LSU 未受理请求")
    clock.step()
    dut.io.in.valid.poke(false.B)
  }

  /** 消费已完成项。out.ready 平时保持低，避免单拍 StageDone 被提前吞掉。 */
  private def consumeOutput(dut: ysyx_26030103_LSU, clock: Clock): Unit = {
    dut.io.out.ready.poke(true.B)
    clock.step()
    dut.io.out.ready.poke(false.B)
  }

  private def resetDut(
    dut:   ysyx_26030103_LSU,
    clock: Clock,
    reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.out.ready.poke(false.B)
    dut.io.DCacheFlush.poke(false.B)
    dut.io.BusQuiet.poke(true.B)
    dut.io.ReservationClear.poke(false.B)
    dut.io.ViptPAddr.poke(0.U(AddrW.W))
    dut.io.ViptPAddrValid.poke(false.B)
    dut.io.ViptPAddrVaddr.poke(0.U(AddrW.W))
    dut.io.ViptKill.poke(false.B)
    dut.io.PmpFault.poke(false.B)
    dut.io.DataBus.AW.AWREADY.poke(false.B)
    dut.io.DataBus.W.WREADY.poke(false.B)
    dut.io.DataBus.B.BVALID.poke(false.B)
    dut.io.DataBus.B.BID.poke(0.U(4.W))
    dut.io.DataBus.B.BRESP.poke(0.U(2.W))
    dut.io.DataBus.AR.ARREADY.poke(false.B)
    dut.io.DataBus.R.RVALID.poke(false.B)
    dut.io.DataBus.R.RID.poke(0.U(4.W))
    dut.io.DataBus.R.RDATA.poke(0.U(32.W))
    dut.io.DataBus.R.RRESP.poke(0.U(2.W))
    dut.io.DataBus.R.RLAST.poke(false.B)
  }

  private def newDut(): ysyx_26030103_LSU =
    new ysyx_26030103_LSU(
      DCacheEnable = true,
      DCacheBlockSizeLog2 = 4,
      DCacheIndexBits = 2,
      DCacheVipt = true,
      ViptParallel = true
    )

  /** 用一次完整 load 填充 DCache 行。 */
  private def fillLine(
    dut:   ysyx_26030103_LSU,
    clock: Clock,
    vaddr: Long,
    paddr: Long
  ): Unit = {
    dut.io.ViptPAddr.poke(paddr.U(AddrW.W))
    dut.io.ViptPAddrVaddr.poke(vaddr.U(AddrW.W))
    dut.io.ViptPAddrValid.poke(true.B)
    pokeLoad(dut, vaddr)
    acceptInput(dut, clock)
    waitUntil(clock, bool(dut.io.DataBus.AR.ARVALID), "load 未发 AR")
    long(dut.io.DataBus.AR.ARADDR) should be(paddr)
    dut.io.DataBus.AR.ARREADY.poke(true.B)
    clock.step()
    dut.io.DataBus.AR.ARREADY.poke(false.B)
    for (beat <- 0 until 4) {
      dut.io.DataBus.R.RDATA.poke(wordOf(paddr, beat).U(32.W))
      dut.io.DataBus.R.RRESP.poke(0.U(2.W))
      dut.io.DataBus.R.RLAST.poke((beat + 1 == 4).B)
      dut.io.DataBus.R.RVALID.poke(true.B)
      clock.step()
    }
    dut.io.DataBus.R.RVALID.poke(false.B)
    dut.io.DataBus.R.RLAST.poke(false.B)
    waitUntil(clock, bool(dut.io.out.valid), "load 未返回")
    long(dut.io.out.bits.LoadData) should be(wordOf(paddr, 0))
    consumeOutput(dut, clock)
    waitUntil(clock, !bool(dut.io.Busy), "load 后 LSU 未排空")
    dut.io.ViptPAddrValid.poke(false.B)
  }

  "LSU VIPT store" should "PA 到达前不发 AW/W，PA 到达后用物理地址提交" in {
    simulate(newDut()) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      fillLine(dut, clock, Va, Pa)

      // 受理 store：ALUResult 仍是 VA，只进入写缓冲。
      pokeStore(dut, Va, 0x11223344L)
      acceptInput(dut, clock)
      clock.step(4)
      bool(dut.io.DataBus.AW.AWVALID) should be(false)
      bool(dut.io.DataBus.W.WVALID) should be(false)

      // 错误 VA 标签的 PA 必须被拒绝，不能借此提前发总线。
      dut.io.ViptPAddr.poke(Pa2.U(AddrW.W))
      dut.io.ViptPAddrVaddr.poke(Va2.U(AddrW.W))
      dut.io.ViptPAddrValid.poke(true.B)
      clock.step(4)
      bool(dut.io.DataBus.AW.AWVALID) should be(false)
      bool(dut.io.DataBus.W.WVALID) should be(false)

      // 正确标签 + PA 才放行；AW 地址必须是 PA，W 载荷保持对齐后的数据。
      dut.io.ViptPAddr.poke(Pa.U(AddrW.W))
      dut.io.ViptPAddrVaddr.poke(Va.U(AddrW.W))
      waitUntil(clock, bool(dut.io.DataBus.AW.AWVALID), "store 未发 AW")
      long(dut.io.DataBus.AW.AWADDR) should be(Pa)
      bool(dut.io.DataBus.W.WVALID) should be(true)
      long(dut.io.DataBus.W.WDATA) should be(0x11223344L)
      long(dut.io.DataBus.W.WSTRB) should be(0xfL)
      dut.io.DataBus.AW.AWREADY.poke(true.B)
      dut.io.DataBus.W.WREADY.poke(true.B)
      clock.step()
      dut.io.DataBus.AW.AWREADY.poke(false.B)
      dut.io.DataBus.W.WREADY.poke(false.B)

      // 同地址 load 必须被写缓冲顺序挡住，不能越过尚未收到 B 的 store。
      pokeLoad(dut, Va)
      dut.io.in.valid.poke(true.B)
      clock.step(3)
      bool(dut.io.in.ready) should be(false)
      dut.io.in.valid.poke(false.B)

      // B 成功后才退休，DCache 镜像也在该沿更新。
      waitUntil(clock, bool(dut.io.DataBus.B.BREADY), "store 未等待 B")
      dut.io.DataBus.B.BVALID.poke(true.B)
      dut.io.DataBus.B.BRESP.poke(0.U(2.W))
      clock.step()
      dut.io.DataBus.B.BVALID.poke(false.B)
      waitUntil(clock, bool(dut.io.out.valid), "store 未退休")
      long(dut.io.out.bits.ALUResult) should be(Pa)
      consumeOutput(dut, clock)
      waitUntil(clock, !bool(dut.io.Busy), "store 后 LSU 未排空")

      // 后续同地址 load 应直接命中 store 更新后的 DCache，不发 AR。
      pokeLoad(dut, Va)
      acceptInput(dut, clock)
      waitUntil(clock, bool(dut.io.out.valid), "store 后 load 未返回")
      bool(dut.io.DataBus.AR.ARVALID) should be(false)
      long(dut.io.out.bits.LoadData) should be(0x11223344L)
      consumeOutput(dut, clock)
    }
  }

  it should "旧 VA 标签的迟到 PA 不能用于新 store" in {
    simulate(newDut()) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)

      // 上一笔翻译结果仍留在端口上：标签属于 Va2，而当前 store 使用 Va3。
      dut.io.ViptPAddr.poke(Pa2.U(AddrW.W))
      dut.io.ViptPAddrVaddr.poke(Va2.U(AddrW.W))
      dut.io.ViptPAddrValid.poke(true.B)

      pokeStore(dut, Va3, 0x0badf00dL)
      acceptInput(dut, clock)
      clock.step(5)
      bool(dut.io.DataBus.AW.AWVALID) should be(false)
      bool(dut.io.DataBus.W.WVALID) should be(false)

      // 只有携带 Va3 标签的 PA3 才能解析这笔 store。
      dut.io.ViptPAddr.poke(Pa3.U(AddrW.W))
      dut.io.ViptPAddrVaddr.poke(Va3.U(AddrW.W))
      waitUntil(clock, bool(dut.io.DataBus.AW.AWVALID), "正确 PA 到达后 store 未发 AW")
      long(dut.io.DataBus.AW.AWADDR) should be(Pa3)
      dut.io.DataBus.AW.AWREADY.poke(true.B)
      dut.io.DataBus.W.WREADY.poke(true.B)
      clock.step()
      dut.io.DataBus.AW.AWREADY.poke(false.B)
      dut.io.DataBus.W.WREADY.poke(false.B)
      waitUntil(clock, bool(dut.io.DataBus.B.BREADY), "store 未等待 B")
      dut.io.DataBus.B.BVALID.poke(true.B)
      clock.step()
      dut.io.DataBus.B.BVALID.poke(false.B)
      waitUntil(clock, bool(dut.io.out.valid), "store 未退休")
      long(dut.io.out.bits.ALUResult) should be(Pa3)
      consumeOutput(dut, clock)
    }
  }

  it should "翻译故障 kill 撤销当前及年轻 store，且不产生总线写" in {
    simulate(newDut()) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)

      pokeStore(dut, Va, 0x1234abcdL)
      acceptInput(dut, clock)
      // 队首 store 等 PA 时仍允许更年轻的连续 store 进入缓冲。
      pokeStore(dut, Va2, 0x5678ef01L)
      acceptInput(dut, clock)
      clock.step(4)
      bool(dut.io.DataBus.AW.AWVALID) should be(false)
      bool(dut.io.DataBus.W.WVALID) should be(false)

      dut.io.ViptKill.poke(true.B)
      clock.step()
      dut.io.ViptKill.poke(false.B)
      clock.step(3)

      // 两笔 store 都不应出现在总线上，流水级和写缓冲必须全部释放。
      bool(dut.io.DataBus.AW.AWVALID) should be(false)
      bool(dut.io.DataBus.W.WVALID) should be(false)
      bool(dut.io.out.valid) should be(false)
      bool(dut.io.Busy) should be(false)

      // kill 后仍可正常受理新 load。
      dut.io.ViptPAddr.poke(Pa.U(AddrW.W))
      dut.io.ViptPAddrVaddr.poke(Va.U(AddrW.W))
      dut.io.ViptPAddrValid.poke(true.B)
      pokeLoad(dut, Va)
      acceptInput(dut, clock)
      waitUntil(clock, bool(dut.io.DataBus.AR.ARVALID), "kill 后 load 未发 AR")
      long(dut.io.DataBus.AR.ARADDR) should be(Pa)
    }
  }

  it should "PIPT 默认路径不需要 ViptPAddrValid 也能提交 store" in {
    simulate(new ysyx_26030103_LSU()) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      // 默认配置 DCache 关闭，地址直接就是物理地址。
      pokeStore(dut, 0x80005000L, 0x00c0ffeeL)
      acceptInput(dut, clock)
      waitUntil(clock, bool(dut.io.DataBus.AW.AWVALID), "PIPT store 未发 AW")
      long(dut.io.DataBus.AW.AWADDR) should be(0x80005000L)
      long(dut.io.DataBus.W.WDATA) should be(0x00c0ffeeL)
    }
  }
}
