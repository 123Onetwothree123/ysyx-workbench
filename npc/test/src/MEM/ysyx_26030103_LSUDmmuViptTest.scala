package ysyx_26030103.mem

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_EXUMessage
import _root_.ysyx_26030103.mmu._

/** LSUMMU + LSU/DCache 的 VIPT 并行集成测试。
  *
  * 验证真实连线：load 先以 VA 进入 DCache，MMU 翻译完成后 PA 才通过
  * 独立端口参与 tag/refill；翻译故障则撤销请求并提交精确 page fault。
  */
class ysyx_26030103_LSUDmmuViptTest extends AnyFlatSpec with Matchers {
  private val AddrW = 32
  private val Va = 0x00001050L
  private val VaFault = 0x00002050L
  private val Pa = 0x80002000L

  private def wordOf(line: Long, beat: Int): Long =
    ((line + beat.toLong * 4L) ^ 0x6d6d0000L) & 0xffffffffL

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long = value.peek().litValue.toLong

  private def waitUntil(clock: Clock, cond: => Boolean, message: String): Unit = {
    var cycles = 0
    while (!cond && cycles < 500) {
      clock.step()
      cycles += 1
    }
    withClue(s"$message（等待 $cycles 拍）") { cond should be(true) }
  }

  private def pokeLoad(
      dut: ysyx_26030103_LSUDmmuHarness,
      vaddr: Long,
      pc: Long
  ): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(false.B)
    dut.io.in.bits.ALUResult.poke(vaddr.U(AddrW.W))
    dut.io.in.bits.VAddr.poke(vaddr.U(AddrW.W))
    dut.io.in.bits.WidthSelect.poke(2.U(2.W))
    dut.io.in.bits.pc.poke(pc.U(AddrW.W))
  }

  private def pokeStore(
      dut: ysyx_26030103_LSUDmmuHarness,
      vaddr: Long,
      data:  Long,
      pc:    Long
  ): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(true.B)
    dut.io.in.bits.ALUResult.poke(vaddr.U(AddrW.W))
    dut.io.in.bits.VAddr.poke(vaddr.U(AddrW.W))
    dut.io.in.bits.WidthSelect.poke(2.U(2.W))
    dut.io.in.bits.StoreData.poke(data.U(32.W))
    dut.io.in.bits.pc.poke(pc.U(AddrW.W))
  }

  private def pokeResp(
      dut: ysyx_26030103_LSUDmmuHarness,
      fault: Boolean,
      paddr: Long
  ): Unit = {
    dut.io.resp.bits.poke(0.U.asTypeOf(new ysyx_26030103_MMUResponse))
    dut.io.resp.bits.id.poke(1.U)
    dut.io.resp.bits.paddr.poke(paddr.U(32.W))
    dut.io.resp.bits.fault.poke(fault.B)
    dut.io.resp.bits.cause.poke((if (fault) 13 else 0).U(32.W))
    dut.io.resp.bits.tval.poke(0.U(32.W))
  }

  private def resetDut(
      dut: ysyx_26030103_LSUDmmuHarness,
      clock: Clock,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.out.ready.poke(false.B)
    dut.io.privilege.poke(1.U(2.W))
    dut.io.satp.poke("h80000000".U(32.W))
    dut.io.flush.poke(false.B)
    dut.io.resp.valid.poke(false.B)
    dut.io.resp.bits.poke(0.U.asTypeOf(new ysyx_26030103_MMUResponse))
    dut.io.req.ready.poke(false.B)
    dut.io.arready.poke(false.B)
    dut.io.rvalid.poke(false.B)
    dut.io.rdata.poke(0.U(32.W))
    dut.io.rresp.poke(0.U(2.W))
    dut.io.rlast.poke(false.B)
    dut.io.awready.poke(false.B)
    dut.io.wready.poke(false.B)
    dut.io.bvalid.poke(false.B)
    dut.io.bresp.poke(0.U(2.W))
    clock.step()
    dut.io.req.ready.poke(true.B)
  }

  "LSUMMU+LSU VIPT" should "VA 先进入 DCache，PA 返回后按非恒等映射回填" in {
    simulate(new ysyx_26030103_LSUDmmuHarness) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      pokeLoad(dut, Va, pc = 0x1000L)
      dut.io.in.valid.poke(true.B)
      waitUntil(clock, bool(dut.io.in.ready), "DMMU 未受理 load")
      clock.step()
      dut.io.in.valid.poke(false.B)

      // MMU 翻译请求先出现；此时 DCache 已经持有 VA 请求。
      waitUntil(clock, bool(dut.io.req.valid), "MMU 翻译请求未发出")
      long(dut.io.req.bits.vaddr) should be(Va)
      bool(dut.io.arvalid) should be(false)
      clock.step() // 请求握手后进入 xwait 才接收翻译响应
      pokeResp(dut, fault = false, paddr = Pa)
      dut.io.resp.valid.poke(true.B)
      clock.step()
      dut.io.resp.valid.poke(false.B)

      // PA 到达后才允许对非恒等 PA 发 refill。
      waitUntil(clock, bool(dut.io.arvalid), "DCache 未在 PA 到达后发 AR")
      long(dut.io.arADDR) should be(Pa)
      long(dut.io.arLEN) should be(3)
      dut.io.arready.poke(true.B)
      clock.step()
      dut.io.arready.poke(false.B)
      val beats = 4
      for (beat <- 0 until beats) {
        dut.io.rdata.poke(wordOf(Pa, beat).U(32.W))
        dut.io.rresp.poke(0.U(2.W))
        dut.io.rlast.poke((beat + 1 == beats).B)
        dut.io.rvalid.poke(true.B)
        clock.step()
      }
      dut.io.rvalid.poke(false.B)
      dut.io.rlast.poke(false.B)

      waitUntil(clock, bool(dut.io.out.valid), "LSU 未返回 load")
      long(dut.io.out.bits.LoadData) should be(wordOf(Pa, 0))
      long(dut.io.out.bits.ALUResult) should be(Pa)
      dut.io.out.ready.poke(true.B)
      clock.step()
      dut.io.out.ready.poke(false.B)
      waitUntil(clock, !bool(dut.io.busy), "DMMU 未在 LSU 排空后释放")
    }
  }

  it should "翻译故障撤销已进入 LSU 的 load 并提交精确 page fault" in {
    simulate(new ysyx_26030103_LSUDmmuHarness) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      pokeLoad(dut, VaFault, pc = 0x2000L)
      dut.io.in.valid.poke(true.B)
      waitUntil(clock, bool(dut.io.in.ready), "DMMU 未受理 load")
      clock.step()
      dut.io.in.valid.poke(false.B)

      waitUntil(clock, bool(dut.io.req.valid), "MMU 翻译请求未发出")
      clock.step() // 进入 xwait
      pokeResp(dut, fault = true, paddr = 0L)
      dut.io.resp.valid.poke(true.B)
      clock.step()
      dut.io.resp.valid.poke(false.B)

      // 故障请求不得产生 AR/load 响应；下一拍提交精确 fault。
      clock.step(3)
      bool(dut.io.arvalid) should be(false)
      bool(dut.io.out.valid) should be(false)
      bool(dut.io.faultCommit) should be(true)
      long(dut.io.faultCause) should be(13)
      long(dut.io.faultPC) should be(0x2000L)
      bool(dut.io.busy) should be(true)
      dut.io.flush.poke(true.B)
      clock.step()
      dut.io.flush.poke(false.B)
      clock.step()
      bool(dut.io.faultCommit) should be(false)
      bool(dut.io.busy) should be(false)
    }
  }

  it should "store 先以 VA 登记，PA 返回后才发 AW/W 并使用物理地址" in {
    simulate(new ysyx_26030103_LSUDmmuHarness) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      val data = 0x11223344L
      pokeStore(dut, Va, data, pc = 0x3000L)
      dut.io.in.valid.poke(true.B)
      waitUntil(clock, bool(dut.io.in.ready), "DMMU 未受理 store")
      clock.step()
      dut.io.in.valid.poke(false.B)

      // store 已进入 LSU 写缓冲，但 PA 未确认：不允许任何写通道动作。
      // req.ready 为高时翻译请求只持续一拍，因此先等 req.valid 再观察，
      // 不能先走固定拍数，否则会错过握手。
      waitUntil(clock, bool(dut.io.req.valid), "store 翻译请求未发出")
      long(dut.io.req.bits.vaddr) should be(Va)
      bool(dut.io.awvalid) should be(false)
      bool(dut.io.wvalid) should be(false)
      clock.step() // 翻译请求握手
      pokeResp(dut, fault = false, paddr = Pa)
      dut.io.resp.valid.poke(true.B)
      clock.step()
      dut.io.resp.valid.poke(false.B)

      // PA 到达后才提交，且 AWADDR 必须是物理地址。
      waitUntil(clock, bool(dut.io.awvalid), "PA 到达后 store 未发 AW")
      long(dut.io.awADDR) should be(Pa)
      bool(dut.io.wvalid) should be(true)
      long(dut.io.wdata) should be(data)
      long(dut.io.wstrb) should be(0xfL)
      dut.io.awready.poke(true.B)
      dut.io.wready.poke(true.B)
      clock.step()
      dut.io.awready.poke(false.B)
      dut.io.wready.poke(false.B)

      waitUntil(clock, bool(dut.io.bready), "store 未等待 B 响应")
      dut.io.bvalid.poke(true.B)
      dut.io.bresp.poke(0.U(2.W))
      clock.step()
      dut.io.bvalid.poke(false.B)

      waitUntil(clock, bool(dut.io.out.valid), "LSU 未返回 store")
      long(dut.io.out.bits.ALUResult) should be(Pa)
      long(dut.io.out.bits.StoreData) should be(data)
      dut.io.out.ready.poke(true.B)
      clock.step()
      dut.io.out.ready.poke(false.B)
      waitUntil(clock, !bool(dut.io.busy), "DMMU 未在 store 排空后释放")
    }
  }

  it should "store 页错误撤销写缓冲且绝不产生 AW/W" in {
    simulate(new ysyx_26030103_LSUDmmuHarness) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      pokeStore(dut, VaFault, 0x55667788L, pc = 0x4000L)
      dut.io.in.valid.poke(true.B)
      waitUntil(clock, bool(dut.io.in.ready), "DMMU 未受理 store")
      clock.step()
      dut.io.in.valid.poke(false.B)

      waitUntil(clock, bool(dut.io.req.valid), "store 翻译请求未发出")
      bool(dut.io.awvalid) should be(false)
      bool(dut.io.wvalid) should be(false)
      clock.step() // 进入 xwait
      pokeResp(dut, fault = true, paddr = 0L)
      dut.io.resp.valid.poke(true.B)
      clock.step()
      dut.io.resp.valid.poke(false.B)

      // 精确 page fault：无写事务、无 store 退休，faultCommit 一次。
      clock.step(3)
      bool(dut.io.awvalid) should be(false)
      bool(dut.io.wvalid) should be(false)
      bool(dut.io.out.valid) should be(false)
      bool(dut.io.faultCommit) should be(true)
      long(dut.io.faultCause) should be(13)
      long(dut.io.faultPC) should be(0x4000L)
      dut.io.flush.poke(true.B)
      clock.step()
      dut.io.flush.poke(false.B)
      clock.step()
      bool(dut.io.faultCommit) should be(false)
      bool(dut.io.busy) should be(false)
      bool(dut.io.awvalid) should be(false)
      bool(dut.io.wvalid) should be(false)
    }
  }
}

/** 把 LSUMMU 与 LSU/DCache 按顶层方式连起来，只暴露测试需要的信号。 */
class ysyx_26030103_LSUDmmuHarness extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_EXUMessage))
    val out = Decoupled(new ysyx_26030103_EXUMessage)
    val privilege = Input(UInt(2.W))
    val satp = Input(UInt(32.W))
    val flush = Input(Bool())
    val faultCommit = Output(Bool())
    val faultCause = Output(UInt(32.W))
    val faultPC = Output(UInt(32.W))
    val busy = Output(Bool())
    val req = Decoupled(new ysyx_26030103_MMURequest)
    val resp = Flipped(Decoupled(new ysyx_26030103_MMUResponse))
    val arvalid = Output(Bool())
    val arADDR = Output(UInt(32.W))
    val arLEN = Output(UInt(8.W))
    val arready = Input(Bool())
    val rvalid = Input(Bool())
    val rdata = Input(UInt(32.W))
    val rresp = Input(UInt(2.W))
    val rlast = Input(Bool())
    val rready = Output(Bool())
    // store 写通道：用于验证 PA 未确认/页错误时绝不出现 AW/W。
    val awvalid = Output(Bool())
    val awADDR = Output(UInt(32.W))
    val awready = Input(Bool())
    val wvalid = Output(Bool())
    val wdata = Output(UInt(32.W))
    val wstrb = Output(UInt(4.W))
    val wready = Input(Bool())
    val bready = Output(Bool())
    val bvalid = Input(Bool())
    val bresp = Input(UInt(2.W))
  })
  val dmmu = Module(new ysyx_26030103_LSUMMU(ParallelVipt = true))
  val lsu = Module(
    new ysyx_26030103_LSU(
      DCacheEnable = true,
      DCacheBlockSizeLog2 = 4,
      DCacheIndexBits = 2,
      DCacheVipt = true,
      ViptParallel = true
    )
  )
  dmmu.io.in.valid := io.in.valid
  dmmu.io.in.bits := io.in.bits
  io.in.ready := dmmu.io.in.ready
  _root_.ysyx_26030103.common.ysyx_26030103_StageConnect(
    dmmu.io.out,
    lsu.io.in,
    lsu.io.FlushEXMEM || dmmu.io.memKill
  )
  dmmu.io.privilege := io.privilege
  dmmu.io.satp := io.satp
  dmmu.io.sum := false.B
  dmmu.io.mxr := false.B
  dmmu.io.flush := io.flush
  dmmu.io.pmpFault := false.B
  io.req.valid := dmmu.io.req.valid
  io.req.bits := dmmu.io.req.bits
  dmmu.io.req.ready := io.req.ready
  dmmu.io.resp.valid := io.resp.valid
  dmmu.io.resp.bits := io.resp.bits
  io.resp.ready := dmmu.io.resp.ready
  io.faultCommit := dmmu.io.faultCommit
  io.faultCause := dmmu.io.faultCause
  io.faultPC := dmmu.io.faultPC
  io.busy := dmmu.io.busy

  lsu.io.ViptPAddr := dmmu.io.memPaddr
  lsu.io.ViptPAddrValid := dmmu.io.memPaddrValid
  lsu.io.ViptPAddrVaddr := dmmu.io.memPaddrVaddr
  lsu.io.ViptKill := dmmu.io.memKill
  dmmu.io.memActive := lsu.io.Busy
  dmmu.io.memTrap := lsu.io.MemTrapCommit
  lsu.io.DCacheFlush := false.B
  // 普通访存不受原子总线独占影响；该 harness 不覆盖 A 扩展。
  lsu.io.BusQuiet := true.B
  lsu.io.ReservationClear := false.B
  // 该 harness 走 MMU 路径，PMP 由 DMMU 翻译后检查。
  lsu.io.PmpFault := false.B
  io.out.valid := lsu.io.out.valid
  io.out.bits := lsu.io.out.bits
  lsu.io.out.ready := io.out.ready

  lsu.io.DataBus.AW.AWREADY := io.awready
  lsu.io.DataBus.W.WREADY := io.wready
  lsu.io.DataBus.B.BVALID := io.bvalid
  lsu.io.DataBus.B.BID := 0.U
  lsu.io.DataBus.B.BRESP := io.bresp
  lsu.io.DataBus.AR.ARREADY := io.arready
  lsu.io.DataBus.R.RVALID := io.rvalid
  lsu.io.DataBus.R.RID := 0.U
  lsu.io.DataBus.R.RDATA := io.rdata
  lsu.io.DataBus.R.RRESP := io.rresp
  lsu.io.DataBus.R.RLAST := io.rlast
  io.arvalid := lsu.io.DataBus.AR.ARVALID
  io.arADDR := lsu.io.DataBus.AR.ARADDR
  io.arLEN := lsu.io.DataBus.AR.ARLEN
  io.rready := lsu.io.DataBus.R.RREADY
  io.awvalid := lsu.io.DataBus.AW.AWVALID
  io.awADDR := lsu.io.DataBus.AW.AWADDR
  io.wvalid := lsu.io.DataBus.W.WVALID
  io.wdata := lsu.io.DataBus.W.WDATA
  io.wstrb := lsu.io.DataBus.W.WSTRB
  io.bready := lsu.io.DataBus.B.BREADY
}
