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
class ysyx_26030103_LSUDmmuVIPTTest extends AnyFlatSpec with Matchers {
  private val AddressW = 32
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
      PC: Long
  ): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(false.B)
    dut.io.in.bits.ALUResult.poke(vaddr.U(AddressW.W))
    dut.io.in.bits.VAddress.poke(vaddr.U(AddressW.W))
    dut.io.in.bits.WidthSelect.poke(2.U(2.W))
    dut.io.in.bits.PC.poke(PC.U(AddressW.W))
  }

  private def pokeStore(
      dut: ysyx_26030103_LSUDmmuHarness,
      vaddr: Long,
      data:  Long,
      PC:    Long
  ): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(true.B)
    dut.io.in.bits.ALUResult.poke(vaddr.U(AddressW.W))
    dut.io.in.bits.VAddress.poke(vaddr.U(AddressW.W))
    dut.io.in.bits.WidthSelect.poke(2.U(2.W))
    dut.io.in.bits.StoreData.poke(data.U(32.W))
    dut.io.in.bits.PC.poke(PC.U(AddressW.W))
  }

  private def pokeResponse(
      dut: ysyx_26030103_LSUDmmuHarness,
      fault: Boolean,
      paddr: Long,
      vaddr: Long
  ): Unit = {
    dut.io.response.bits.poke(0.U.asTypeOf(new ysyx_26030103_MMUResponse))
    dut.io.response.bits.ID.poke(1.U)
    dut.io.response.bits.paddr.poke(paddr.U(32.W))
    dut.io.response.bits.fault.poke(fault.B)
    dut.io.response.bits.cause.poke((if (fault) 13 else 0).U(32.W))
    dut.io.response.bits.tval.poke(vaddr.U(32.W))
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
    dut.io.response.valid.poke(false.B)
    dut.io.response.bits.poke(0.U.asTypeOf(new ysyx_26030103_MMUResponse))
    dut.io.request.ready.poke(false.B)
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
    dut.io.request.ready.poke(true.B)
  }

  "LSUMMU+LSU VIPT" should "VA 先进入 DCache，PA 返回后按非恒等映射回填" in {
    simulate(new ysyx_26030103_LSUDmmuHarness) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      pokeLoad(dut, Va, PC = 0x1000L)
      dut.io.in.valid.poke(true.B)
      waitUntil(clock, bool(dut.io.in.ready), "DMMU 未受理 load")
      clock.step()
      dut.io.in.valid.poke(false.B)

      // MMU 翻译请求先出现；此时 DCache 已经持有 VA 请求。
      waitUntil(clock, bool(dut.io.request.valid), "MMU 翻译请求未发出")
      long(dut.io.request.bits.vaddr) should be(Va)
      bool(dut.io.arvalid) should be(false)
      clock.step() // 请求握手后进入 xwait 才接收翻译响应
      pokeResponse(dut, fault = false, paddr = Pa, vaddr = Va)
      dut.io.response.valid.poke(true.B)
      clock.step()
      dut.io.response.valid.poke(false.B)

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
      pokeLoad(dut, VaFault, PC = 0x2000L)
      dut.io.in.valid.poke(true.B)
      waitUntil(clock, bool(dut.io.in.ready), "DMMU 未受理 load")
      clock.step()
      dut.io.in.valid.poke(false.B)

      waitUntil(clock, bool(dut.io.request.valid), "MMU 翻译请求未发出")
      clock.step() // 进入 xwait
      pokeResponse(dut, fault = true, paddr = 0L, vaddr = VaFault)
      dut.io.response.valid.poke(true.B)
      clock.step()
      dut.io.response.valid.poke(false.B)

      // 故障请求不得产生 AR/load 响应；下一拍提交精确 fault。
      clock.step(3)
      bool(dut.io.arvalid) should be(false)
      bool(dut.io.out.valid) should be(false)
      bool(dut.io.faultCommit) should be(true)
      long(dut.io.faultCause) should be(13)
      long(dut.io.FaultPC) should be(0x2000L)
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
      pokeStore(dut, Va, data, PC = 0x3000L)
      dut.io.in.valid.poke(true.B)
      waitUntil(clock, bool(dut.io.in.ready), "DMMU 未受理 store")
      clock.step()
      dut.io.in.valid.poke(false.B)

      // store 已进入 LSU 写缓冲，但 PA 未确认：不允许任何写通道动作。
      // request.ready 为高时翻译请求只持续一拍，因此先等 request.valid 再观察，
      // 不能先走固定拍数，否则会错过握手。
      waitUntil(clock, bool(dut.io.request.valid), "store 翻译请求未发出")
      long(dut.io.request.bits.vaddr) should be(Va)
      bool(dut.io.awvalid) should be(false)
      bool(dut.io.wvalid) should be(false)
      clock.step() // 翻译请求握手
      pokeResponse(dut, fault = false, paddr = Pa, vaddr = Va)
      dut.io.response.valid.poke(true.B)
      clock.step()
      dut.io.response.valid.poke(false.B)

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
      pokeStore(dut, VaFault, 0x55667788L, PC = 0x4000L)
      dut.io.in.valid.poke(true.B)
      waitUntil(clock, bool(dut.io.in.ready), "DMMU 未受理 store")
      clock.step()
      dut.io.in.valid.poke(false.B)

      waitUntil(clock, bool(dut.io.request.valid), "store 翻译请求未发出")
      bool(dut.io.awvalid) should be(false)
      bool(dut.io.wvalid) should be(false)
      clock.step() // 进入 xwait
      pokeResponse(dut, fault = true, paddr = 0L, vaddr = VaFault)
      dut.io.response.valid.poke(true.B)
      clock.step()
      dut.io.response.valid.poke(false.B)

      // 精确 page fault：无写事务、无 store 退休，faultCommit 一次。
      clock.step(3)
      bool(dut.io.awvalid) should be(false)
      bool(dut.io.wvalid) should be(false)
      bool(dut.io.out.valid) should be(false)
      bool(dut.io.faultCommit) should be(true)
      long(dut.io.faultCause) should be(13)
      long(dut.io.FaultPC) should be(0x4000L)
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
    val FaultPC = Output(UInt(32.W))
    val busy = Output(Bool())
    val request = Decoupled(new ysyx_26030103_MMURequest)
    val response = Flipped(Decoupled(new ysyx_26030103_MMUResponse))
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
  val DMMU = Module(new ysyx_26030103_LSUMMU(XLEN = 32, AddressWidth = 32, ParallelVIPT = true))
  val LSU = Module(
    new ysyx_26030103_LSU(
        XLEN = 32,
        AddressWidth = 32,
        BusDataWidth = 32,
      DCacheEnable = true,
      DCacheBlockSizeLog2 = 4,
      DCacheIndexBits = 2,
      DCacheVIPT = true,
      VIPTParallel = true
    )
  )
  DMMU.io.in.valid := io.in.valid
  DMMU.io.in.bits := io.in.bits
  io.in.ready := DMMU.io.in.ready
  _root_.ysyx_26030103.common.ysyx_26030103_StageConnect(
    DMMU.io.out,
    LSU.io.in,
    LSU.io.FlushEXMEM || DMMU.io.memKill
  )
  DMMU.io.privilege := io.privilege
  DMMU.io.satp := io.satp
  DMMU.io.sum := false.B
  DMMU.io.mxr := false.B
  DMMU.io.flush := io.flush
  DMMU.io.PMPFault := false.B
  io.request.valid := DMMU.io.request.valid
  io.request.bits := DMMU.io.request.bits
  DMMU.io.request.ready := io.request.ready
  DMMU.io.response.valid := io.response.valid
  DMMU.io.response.bits := io.response.bits
  io.response.ready := DMMU.io.response.ready
  io.faultCommit := DMMU.io.faultCommit
  io.faultCause := DMMU.io.faultCause
  io.FaultPC := DMMU.io.FaultPC
  io.busy := DMMU.io.busy

  LSU.io.VIPTPAddress := DMMU.io.memPhysicalAddress
  LSU.io.VIPTPAddressValid := DMMU.io.memPhysicalAddressValid
  LSU.io.VIPTPAddressVirtualAddress := DMMU.io.memPhysicalAddressVirtualAddress
  LSU.io.VIPTKill := DMMU.io.memKill
  DMMU.io.memActive := LSU.io.Busy
  DMMU.io.memTrap := LSU.io.MemTrapCommit
  LSU.io.DCacheFlush := false.B
  // 普通访存不受原子总线独占影响；该 harness 不覆盖 A 扩展。
  LSU.io.BusQuiet := true.B
  LSU.io.ReservationClear := false.B
  // 该 harness 不启用 DMA snoop，显式绑零避免内部 sink 悬空。
  LSU.io.DCacheDMASnoopValid := false.B
  LSU.io.DCacheDMASnoopStart := 0.U
  LSU.io.DCacheDMASnoopEnd := 0.U
  // 该 harness 走 MMU 路径，PMP 由 DMMU 翻译后检查。
  LSU.io.PMPFault := false.B
  io.out.valid := LSU.io.out.valid
  io.out.bits := LSU.io.out.bits
  LSU.io.out.ready := io.out.ready

  LSU.io.DataBus.AW.AWREADY := io.awready
  LSU.io.DataBus.W.WREADY := io.wready
  LSU.io.DataBus.B.BVALID := io.bvalid
  LSU.io.DataBus.B.BID := 0.U
  LSU.io.DataBus.B.BRESP := io.bresp
  LSU.io.DataBus.AR.ARREADY := io.arready
  LSU.io.DataBus.R.RVALID := io.rvalid
  LSU.io.DataBus.R.RID := 0.U
  LSU.io.DataBus.R.RDATA := io.rdata
  LSU.io.DataBus.R.RRESP := io.rresp
  LSU.io.DataBus.R.RLAST := io.rlast
  io.arvalid := LSU.io.DataBus.AR.ARVALID
  io.arADDR := LSU.io.DataBus.AR.ARADDR
  io.arLEN := LSU.io.DataBus.AR.ARLEN
  io.rready := LSU.io.DataBus.R.RREADY
  io.awvalid := LSU.io.DataBus.AW.AWVALID
  io.awADDR := LSU.io.DataBus.AW.AWADDR
  io.wvalid := LSU.io.DataBus.W.WVALID
  io.wdata := LSU.io.DataBus.W.WDATA
  io.wstrb := LSU.io.DataBus.W.WSTRB
  io.bready := LSU.io.DataBus.B.BREADY
}
