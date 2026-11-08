package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.common.ysyx_26030103_EXUMessage
import _root_.ysyx_26030103.mem.ysyx_26030103_LSU

/** Confirms the LSU/DCache VIPT contract break in the NON-parallel configuration.
  *
  * Source evidence:
  *   - `npc/src/MEM/ysyx_26030103_LSU.scala:66-67`:
  *       DataVipt = DCacheVipt && DCacheEnable
  *       DataViptParallel = DataVipt && ViptParallel
  *     and `:198` instantiates the DCache with `Vipt = DataVipt`.  Therefore
  *     DCacheVipt=true + ViptParallel=false still produces a VIPT DCache.
  *   - `npc/src/MEM/ysyx_26030103_LSU.scala:844-849`:
  *       req.bits.addr   := ActiveInstruction.ALUResult   (physical)
  *       req.bits.vaddr  := ActiveInstruction.VAddr        (virtual)
  *       req_paddr_valid := true
  *       req_paddr_vaddr := ActiveInstruction.ALUResult    (physical!)
  *     The non-parallel branch drives the PHYSICAL address into the port whose
  *     job is to carry the VIRTUAL tag of the physical address.
  *   - `npc/src/Cache/DCache/ysyx_26030103_DCacheReg.scala:155-156,185`:
  *       IncomingPaddrMatch = !Vipt || (req_paddr_valid && req_paddr_vaddr === req.bits.vaddr)
  *       ViptNeedWait       = Vipt && !IncomingPaddrMatch
  *     With VA=0x00001050 and PA=0x80002000 the comparison is always false, so
  *     the request goes down the wait path.
  *   - `DCacheReg.scala:589-595` still accepts the request while SIdle/ViptAcceptable,
  *     `:687-691` latches WaitPaddr/WaitVaddrReg=req.bits.vaddr (0x1050), and
  *     `:189-190` only resolves when `req_paddr_vaddr === WaitVaddrReg`.  The LSU
  *     keeps presenting 0x80002000, so ResolveVaddrMatch is never true: no AR,
  *     no response, LSU stays Busy forever.
  *
  * The first `should` block is expected to FAIL on the current RTL.  The second
  * `should` block is the control: identical harness, supported ViptParallel=true.
  * It must PASS, proving the harness, the side-band PA tag wiring and the AXI
  * slave model are sound, and isolating the deadlock to ViptParallel=false.
  */
class ysyx_26030103_LSUViptNonParallelAuditTest extends AnyFlatSpec with Matchers {
  private val AddrW = 32
  // Non-identity mapping: the DMMU would translate this VA into this PA.
  private val Va = 0x00001050L
  private val Pa = 0x80002000L
  private val LoadWord = 0xdeadbeefL
  private val CycleBound = 200
  // DCacheBlockSizeLog2=4 -> 16 B line; BusDataWidth=32 -> 4 B/beat.
  // A cacheable miss therefore refills with a 4-beat burst: ARLEN = 3.
  private val ExpectedArLen = (1 << 4) / (32 / 8) - 1

  private case class Observation(
      accepted: Boolean,
      inReadySeen: Boolean,
      arSeen: Boolean,
      arAddr: Long,
      arLen: Long,
      arSize: Long,
      outValid: Boolean,
      loadData: BigInt,
      busyAtEnd: Boolean,
      cycles: Int
  )

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long = value.peek().litValue.toLong

  private def newDut(viptParallel: Boolean): ysyx_26030103_LSU =
    new ysyx_26030103_LSU(
      XLEN = 32,
      DCacheEnable = true,
      DCacheBlockSizeLog2 = 4,
      DCacheIndexBits = 2,
      DCacheVipt = true,
      ViptParallel = viptParallel
    )

  private def resetDut(
      dut: ysyx_26030103_LSU,
      clock: Clock,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.out.ready.poke(true.B)
    dut.io.DCacheFlush.poke(false.B)
    dut.io.BusQuiet.poke(true.B)
    dut.io.ReservationClear.poke(false.B)
    dut.io.ViptPAddr.poke(0.U(AddrW.W))
    dut.io.ViptPAddrValid.poke(false.B)
    dut.io.ViptPAddrVaddr.poke(Va.U(AddrW.W))
    dut.io.ViptKill.poke(false.B)
    dut.io.PmpFault.poke(false.B)
    dut.io.DCacheDmaSnoopValid.poke(false.B)
    dut.io.DCacheDmaSnoopStart.poke(0.U(AddrW.W))
    dut.io.DCacheDmaSnoopEnd.poke(0.U(AddrW.W))
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

  /** Non-identity-mapped word load: ALUResult carries the PA (as the DMMU
    * output) while VAddr carries the VA used for the cache index.
    */
  private def pokeLoad(dut: ysyx_26030103_LSU): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(false.B)
    dut.io.in.bits.ALUResult.poke(Pa.U(AddrW.W))
    dut.io.in.bits.VAddr.poke(Va.U(AddrW.W))
    dut.io.in.bits.WidthSelect.poke(2.U(2.W))
    dut.io.in.bits.LoadSigned.poke(false.B)
    dut.io.in.bits.pc.poke(0x1000.U(AddrW.W))
    dut.io.in.bits.Retire.poke(true.B)
    dut.io.in.bits.RegisterWrite.poke(true.B)
    dut.io.in.bits.Rd.poke(5.U)
  }

  /** Run the identical scenario for both VIPT configurations. */
  private def runHarness(viptParallel: Boolean): Observation = {
    var captured: Option[Observation] = None
    simulate(newDut(viptParallel)) { dut =>
      val clock = dut.clock
      resetDut(dut, clock, dut.reset)
      pokeLoad(dut)

      // Side-band PA channel: a perfectly valid PA, correctly tagged with the
      // VA of this very instruction.  The non-parallel LSU ignores it.
      dut.io.ViptPAddr.poke(Pa.U(AddrW.W))
      dut.io.ViptPAddrValid.poke(true.B)
      dut.io.ViptPAddrVaddr.poke(Va.U(AddrW.W))

      // Always-willing AXI read slave: a correct implementation must complete.
      dut.io.DataBus.AR.ARREADY.poke(true.B)
      dut.io.DataBus.R.RVALID.poke(true.B)
      dut.io.DataBus.R.RID.poke(0.U(4.W))
      dut.io.DataBus.R.RDATA.poke(LoadWord.U(32.W))
      dut.io.DataBus.R.RRESP.poke(0.U(2.W))
      dut.io.DataBus.R.RLAST.poke(true.B)
      dut.io.DataBus.B.BVALID.poke(false.B)

      // Present the load and capture the accepting edge.
      var inReadySeen = false
      var accepted = false
      var acceptCycles = 0
      dut.io.in.valid.poke(true.B)
      while (!accepted && acceptCycles < CycleBound) {
        if (bool(dut.io.in.ready)) {
          inReadySeen = true
          accepted = true
        }
        clock.step()
        acceptCycles += 1
      }
      dut.io.in.valid.poke(false.B)

      // Bounded observation window.
      var cycles = 0
      var arSeen = false
      var arAddr = -1L
      var arLen = -1L
      var arSize = -1L
      var outValid = false
      var loadData = BigInt(0)
      while (cycles < CycleBound) {
        if (bool(dut.io.in.ready)) { inReadySeen = true }
        if (!arSeen && bool(dut.io.DataBus.AR.ARVALID) &&
            bool(dut.io.DataBus.AR.ARREADY)) {
          arSeen = true
          arAddr = long(dut.io.DataBus.AR.ARADDR)
          arLen = long(dut.io.DataBus.AR.ARLEN)
          arSize = long(dut.io.DataBus.AR.ARSIZE)
        }
        if (bool(dut.io.out.valid)) {
          outValid = true
          loadData = dut.io.out.bits.LoadData.peek().litValue
        }
        clock.step()
        cycles += 1
      }

      captured = Some(
        Observation(
          accepted = accepted,
          inReadySeen = inReadySeen,
          arSeen = arSeen,
          arAddr = arAddr,
          arLen = arLen,
          arSize = arSize,
          outValid = outValid,
          loadData = loadData,
          busyAtEnd = bool(dut.io.Busy),
          cycles = cycles
        )
      )
    }
    captured.get
  }

  private def describe(tag: String, obs: Observation): String = {
    val addrText =
      if (obs.arAddr < 0) "<none>" else f"0x${obs.arAddr}%08x"
    val loadText = "0x" + obs.loadData.toString(16)
    s"$tag: accepted=${obs.accepted}, inReadySeen=${obs.inReadySeen}, " +
      s"busy=${obs.busyAtEnd}, arSeen=${obs.arSeen}, arAddr=$addrText, " +
      s"arLen=${obs.arLen}, arSize=${obs.arSize}, outValid=${obs.outValid}, " +
      s"loadData=$loadText, observedCycles=${obs.cycles}, bound=$CycleBound, " +
      s"expected AR{ADDR=0x${Pa.toHexString}, LEN=$ExpectedArLen} and " +
      s"out.valid with LoadData=0x${LoadWord.toHexString}"
  }

  "LSU non-parallel VIPT (ViptParallel = false)" should
    "complete a VA/PA-mismatched word load instead of deadlocking" in {
      // Expected to FAIL on the current RTL: ViptNeedWait is entered because
      // req_paddr_vaddr is the PA (0x80002000) while req.bits.vaddr is the VA
      // (0x00001050); resolution needs the side port to carry 0x00001050, but
      // the non-parallel LSU hardwires req_paddr_vaddr to ALUResult (the PA).
      val obs = runHarness(viptParallel = false)
      val clue = describe("non-parallel VIPT", obs)
      info(clue)

      withClue(clue + "；非并行模式把 ALUResult(PA) 接到 req_paddr_vaddr，" +
        "而 DCache 需要的是该 PA 对应的 VA 标签，故请求永远等不到 resolve。" +
        s"实际 arSeen=${obs.arSeen}，outValid=${obs.outValid}，" +
        s"loadData=0x${obs.loadData.toString(16)}。") {
        obs.accepted should be(true)
        obs.arSeen should be(true)
        obs.arAddr should be(Pa)
        obs.arLen should be(ExpectedArLen.toLong)
        obs.outValid should be(true)
        obs.loadData should be(BigInt(LoadWord))
      }
    }

  "LSU non-parallel VIPT control (ViptParallel = true)" should
    "complete the same load with the identical harness" in {
      // CONTROL: the supported parallel configuration consumes the side-band
      // PA tag normally, so this must PASS.  If it fails, the harness (not the
      // non-parallel path) is at fault.
      val obs = runHarness(viptParallel = true)
      val clue = describe("parallel VIPT control", obs)
      info(clue)

      withClue(clue + "；对照组使用受支持的并行模式，PA 端口标签与 VA 匹配，" +
        "应当完成同一笔 load。") {
        obs.accepted should be(true)
        obs.arSeen should be(true)
        obs.arAddr should be(Pa)
        obs.arLen should be(ExpectedArLen.toLong)
        obs.outValid should be(true)
        obs.loadData should be(BigInt(LoadWord))
      }
    }
}
