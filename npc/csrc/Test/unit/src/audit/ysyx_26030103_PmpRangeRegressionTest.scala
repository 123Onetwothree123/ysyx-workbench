package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.common.ysyx_26030103_EXUMessage
import _root_.ysyx_26030103.infra.ysyx_26030103_PMP
import _root_.ysyx_26030103.mem.ysyx_26030103_LSU
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** PMP 范围检查回归：8 字节访问跨越 4 字节 NA4 区域边界时必须 fault。
  *
  * RISC-V PMP 要求检查整个访问范围，而不是只看起始地址。当前 PMP 只有
  * 单地址输入，LSU 把本次访问的首地址送给 PMP；entry0 是 4 字节 NA4
  * 区域且允许 R/W/X 时，0x80000000 处的 8 字节 load 会被错误放行并发出
  * AR。正确实现必须以 PMP 故障（load access fault, cause=5）拦下它，
  * 且不得产生任何 AXI 请求。
  *
  * 这里真实地把 LSU 与 PMP 连起来，覆盖“LSU 输出起始地址 → PMP 判定 →
  * LSU 发 AR”的完整路径，而不是只测 PMP 的单地址匹配。
  */
class ysyx_26030103_PmpRangeRegressionTest extends AnyFlatSpec with Matchers {
  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def long(value: UInt): Long = value.peek().litValue.toLong

  /** 把 LSU 的 PMP 请求口与 PMP 叶子模块直接相连的小 harness。 */
  private class PmpRangeHarness extends Module {
    val io = IO(new Bundle {
      val cfg = Input(Vec(16, UInt(8.W)))
      val addr = Input(Vec(16, UInt(32.W)))
      val inValid = Input(Bool())
      val inBits = Input(new ysyx_26030103_EXUMessage(32))
      val outReady = Input(Bool())
      val pmpAddr = Output(UInt(32.W))
      val pmpFault = Output(Bool())
      val arValid = Output(Bool())
      val arAddr = Output(UInt(32.W))
      val memTrapCommit = Output(Bool())
      val memTrapCause = Output(UInt(32.W))
      val memTrapTval = Output(UInt(32.W))
    })

    val lsu = Module(
      new ysyx_26030103_LSU(
        XLEN = 32,
        AddressWidth = 32,
        DCacheEnable = false
      )
    )
    val pmp = Module(
      new ysyx_26030103_PMP(entries = 16, AddressWidth = 32)
    )

    // LSU 输入 / 输出。
    lsu.io.in.valid := io.inValid
    lsu.io.in.bits := io.inBits
    lsu.io.out.ready := io.outReady

    // 固定输入。
    lsu.io.DCacheFlush := false.B
    lsu.io.BusQuiet := true.B
    lsu.io.ReservationClear := false.B
    lsu.io.ViptPAddr := 0.U(32.W)
    lsu.io.ViptPAddrValid := false.B
    lsu.io.ViptPAddrVaddr := 0.U(32.W)
    lsu.io.ViptKill := false.B
    lsu.io.DCacheDmaSnoopValid := false.B
    lsu.io.DCacheDmaSnoopStart := 0.U(32.W)
    lsu.io.DCacheDmaSnoopEnd := 0.U(32.W)

    // data 侧 PMP 检查：起始/末字节地址来自 LSU 当前需要检查的访存。
    pmp.io.cfg := io.cfg
    pmp.io.addr := io.addr
    pmp.io.dataAddr := lsu.io.PmpAddr
    pmp.io.dataLastAddr := lsu.io.PmpLastAddr
    pmp.io.dataPriv := 1.U // S 模式
    pmp.io.dataRead := lsu.io.PmpRead
    pmp.io.dataWrite := lsu.io.PmpWrite
    // fetch 侧不使用；给足 M 模式权限避免干扰。
    pmp.io.fetchAddr := 0.U
    pmp.io.fetchPriv := 3.U
    lsu.io.PmpFault := pmp.io.dataFault

    // AXI 从设备默认不响应，只观察 LSU 是否发起读请求。
    lsu.io.DataBus.AW.AWREADY := false.B
    lsu.io.DataBus.W.WREADY := false.B
    lsu.io.DataBus.B.BVALID := false.B
    lsu.io.DataBus.B.BID := 0.U(4.W)
    lsu.io.DataBus.B.BRESP := 0.U(2.W)
    lsu.io.DataBus.AR.ARREADY := false.B
    lsu.io.DataBus.R.RVALID := false.B
    lsu.io.DataBus.R.RID := 0.U(4.W)
    lsu.io.DataBus.R.RDATA := 0.U(32.W)
    lsu.io.DataBus.R.RRESP := 0.U(2.W)
    lsu.io.DataBus.R.RLAST := false.B

    io.pmpAddr := lsu.io.PmpAddr
    io.pmpFault := pmp.io.dataFault
    io.arValid := lsu.io.DataBus.AR.ARVALID
    io.arAddr := lsu.io.DataBus.AR.ARADDR
    io.memTrapCommit := lsu.io.MemTrapCommit
    io.memTrapCause := lsu.io.MemTrapCause
    io.memTrapTval := lsu.io.MemTrapTval
  }

  private def resetDut(
      dut: PmpRangeHarness,
      clock: Clock,
      reset: Reset
  ): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    for (i <- 0 until 16) {
      dut.io.cfg(i).poke(0.U)
      dut.io.addr(i).poke(0.U)
    }
    dut.io.inValid.poke(false.B)
    dut.io.inBits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.inBits)))
    dut.io.outReady.poke(true.B)
    clock.step()
  }

  private def pokeLoad8(
      dut: PmpRangeHarness,
      address: Long,
      pc: Long
  ): Unit = {
    dut.io.inBits.poke(0.U.asTypeOf(chiselTypeOf(dut.io.inBits)))
    dut.io.inBits.Retire.poke(true.B)
    dut.io.inBits.RegisterWrite.poke(true.B)
    dut.io.inBits.WBSelect.poke(1.U(2.W))
    dut.io.inBits.Rd.poke(5.U)
    dut.io.inBits.MemoryValid.poke(true.B)
    dut.io.inBits.MemoryWrite.poke(false.B)
    dut.io.inBits.IsAtomic.poke(false.B)
    dut.io.inBits.ALUResult.poke(address.U(32.W))
    dut.io.inBits.VAddr.poke(address.U(32.W))
    dut.io.inBits.WidthSelect.poke(3.U(2.W)) // 8 字节访问
    dut.io.inBits.pc.poke(pc.U(32.W))
    dut.io.inValid.poke(true.B)
  }

  "PMP range check" should
    "reject an 8-byte load crossing a 4-byte NA4 region boundary" in {
      simulate(new PmpRangeHarness) { dut =>
        resetDut(dut, dut.clock, dut.reset)

        // entry0：A=NA4（4 字节区域），R/W/X，L=0，覆盖 0x80000000..0x80000003。
        dut.io.cfg(0).poke("h17".U)
        dut.io.addr(0).poke("h20000000".U) // 0x80000000 >> 2
        for (i <- 1 until 16) {
          dut.io.cfg(i).poke(0.U)
          dut.io.addr(i).poke(0.U)
        }

        // 0x80000000 是 8 字节对齐，不会因非对齐提前 trap；但本次访问覆盖
        // 0x80000000..0x80000007，超出 4 字节 NA4 区域。
        pokeLoad8(dut, 0x80000000L, 0x80000000L)

        var sawAr = false
        var sawFault = false
        var sawTrap = false
        var trapCause = -1L
        var trapTval = -1L
        for (_ <- 0 until 12) {
          if (bool(dut.io.arValid)) sawAr = true
          if (bool(dut.io.pmpFault)) sawFault = true
          if (bool(dut.io.memTrapCommit)) {
            sawTrap = true
            trapCause = long(dut.io.memTrapCause)
            trapTval = long(dut.io.memTrapTval)
          }
          dut.clock.step()
        }

        withClue(
          s"8 字节 load@0x80000000 越出 4 字节 NA4 区域，" +
            s"pmpFault 曾为 $sawFault，但 LSU 仍发出了 AR" +
            (if (sawAr) s"（ARADDR=0x${long(dut.io.arAddr).toHexString}）" else "")
        ) {
          sawAr should be(false)
        }
        withClue(s"越界访问必须按 load access fault 提交，实际 trap=$sawTrap") {
          sawTrap should be(true)
        }
        trapCause should be(5L)
        trapTval should be(0x80000000L)
      }
    }
}
