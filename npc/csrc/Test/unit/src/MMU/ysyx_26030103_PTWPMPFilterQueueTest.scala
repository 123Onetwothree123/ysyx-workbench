package ysyx_26030103.mmu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** PTWPMPFilter fault 响应队列的定向测试。
  *
  * 关注点：
  *   - faultQueueDepth 参数（1..16）的合法性与 elaboration；
  *   - PMP 拒绝的页表请求只进入 fault 队列、不进入 out.request，并带回原 ID；
  *   - 队列满时 in.request 被反压（in.request.ready=0），队列有空间时继续接收；
  *   - 未被拒绝的请求走正常 out.request 通路，in.request.ready 跟随 out.request.ready。
  *
  * PMP 配置约定（与 ysyx_26030103_PMP 一致）：
  *   - cfg = {L, 0, 0, A[1:0], X, W, R}；
  *   - pmpaddr 保存 address[31:2]；
  *   - PTWPMPFilter 用 S 权限（dataPriv=1）检查，未匹配表项默认拒绝。
  */
class ysyx_26030103_PTWPMPFilterQueueTest
    extends AnyFlatSpec with Matchers {
  private val AllowedBase = 0x80000000L
  // 4KiB NAPOT @0x80000000 的 pmpaddr：base>>2 | (4KiB/2 - 1) 的低位掩码。
  private val AllowedNapot = 0x200001ffL
  private val AllowedAddress = 0x80000010L
  private val FaultAddress = 0x90000000L

  private def bool(v: Bool): Boolean = v.peek().litValue == 1
  private def long(v: UInt): Long = v.peek().litValue.toLong

  private def clearPMP(dut: ysyx_26030103_PTWPMPFilter): Unit = {
    for (i <- 0 until 16) {
      dut.io.config(i).poke(0.U(8.W))
      dut.io.address(i).poke(0.U(32.W))
    }
  }

  /** entry0 = 4KiB NAPOT @0x80000000，R/W/X，L=0。 */
  private def allowFirst4KiB(dut: ysyx_26030103_PTWPMPFilter): Unit = {
    dut.io.config(0).poke(0x1f.U(8.W))
    dut.io.address(0).poke(AllowedNapot.U(32.W))
  }

  private def resetDut(dut: ysyx_26030103_PTWPMPFilter): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)

    dut.io.in.request.valid.poke(false.B)
    dut.io.in.request.bits.ID.poke(0.U(4.W))
    dut.io.in.request.bits.address.poke(0.U(32.W))
    dut.io.in.request.bits.write.poke(false.B)
    dut.io.in.request.bits.data.poke(0.U(32.W))
    dut.io.in.request.bits.strb.poke(0.U(4.W))
    dut.io.in.response.ready.poke(false.B)

    dut.io.out.request.ready.poke(true.B)
    dut.io.out.response.valid.poke(false.B)
    dut.io.out.response.bits.ID.poke(0.U(4.W))
    dut.io.out.response.bits.data.poke(0.U(32.W))
    dut.io.out.response.bits.fault.poke(false.B)

    clearPMP(dut)
  }

  private def setRequest(
      dut: ysyx_26030103_PTWPMPFilter,
      ID: Int,
      addr: Long,
      write: Boolean = false
  ): Unit = {
    dut.io.in.request.valid.poke(true.B)
    dut.io.in.request.bits.ID.poke(ID.U(4.W))
    dut.io.in.request.bits.address.poke(addr.U(32.W))
    dut.io.in.request.bits.write.poke(write.B)
    dut.io.in.request.bits.data.poke(0.U(32.W))
    dut.io.in.request.bits.strb.poke(0xf.U(4.W))
  }

  private def clearRequest(dut: ysyx_26030103_PTWPMPFilter): Unit =
    dut.io.in.request.valid.poke(false.B)

  "PTWPMPFilter" should "在 faultQueueDepth=1/8/16 下可 elaboration" in {
    for (depth <- Seq(1, 8, 16)) {
      val chirrtl = ChiselStage.emitCHIRRTL(
        new ysyx_26030103_PTWPMPFilter(faultQueueDepth = depth)
      )
      withClue(s"faultQueueDepth=$depth: ") {
        chirrtl should include("ysyx_26030103_PTWPMPFilter")
        // fault 响应队列应当是独立的 Queue 实例。
        chirrtl should include("faultResp")
      }
    }
  }

  it should "拒绝 faultQueueDepth 越界值" in {
    an[IllegalArgumentException] should be thrownBy ChiselStage.emitCHIRRTL(
      new ysyx_26030103_PTWPMPFilter(faultQueueDepth = 0)
    )
    an[IllegalArgumentException] should be thrownBy ChiselStage.emitCHIRRTL(
      new ysyx_26030103_PTWPMPFilter(faultQueueDepth = 17)
    )
  }

  it should "构造 faultQueueDepth=1 与 8 并正常复位" in {
    for (depth <- Seq(1, 8)) {
      simulate(new ysyx_26030103_PTWPMPFilter(faultQueueDepth = depth)) {
        dut =>
          resetDut(dut)
          // 空队列、无请求时不应产生任何响应。
          bool(dut.io.in.response.valid) should be(false)
          bool(dut.io.out.request.valid) should be(false)
      }
    }
  }

  it should "对 PMP 拒绝的请求返回 fault 响应并原样带回 ID" in {
    simulate(new ysyx_26030103_PTWPMPFilter(faultQueueDepth = 8)) { dut =>
      resetDut(dut)
      // 只放行 0x80000000..0x80000fff，其余地址对 S 模式默认拒绝。
      allowFirst4KiB(dut)

      for (ID <- Seq(3, 9, 0)) {
        // out.request.ready 拉低不应影响 fault 请求被接收：它走 fault 队列。
        dut.io.out.request.ready.poke(false.B)
        setRequest(dut, ID, FaultAddress)
        bool(dut.io.in.request.ready) should be(true)
        bool(dut.io.out.request.valid) should be(false)

        dut.clock.step()
        clearRequest(dut)

        // fault 响应在 in.response 上给出，队列未满时不依赖外部 ready。
        bool(dut.io.in.response.valid) should be(true)
        bool(dut.io.in.response.bits.fault) should be(true)
        long(dut.io.in.response.bits.ID) should be(ID.toLong)
        long(dut.io.in.response.bits.data) should be(0L)
        bool(dut.io.out.request.valid) should be(false)

        // 消费该响应后队列应回到空。
        dut.io.in.response.ready.poke(true.B)
        dut.clock.step()
        dut.io.in.response.ready.poke(false.B)
        bool(dut.io.in.response.valid) should be(false)
      }
    }
  }

  it should "区分 PMP 读权限：可读区域放行，只可执行区域 fault" in {
    simulate(new ysyx_26030103_PTWPMPFilter(faultQueueDepth = 8)) { dut =>
      resetDut(dut)

      // X only（R=0）：页表读被拒绝。
      dut.io.config(0).poke(0x1c.U(8.W))
      dut.io.address(0).poke(AllowedNapot.U(32.W))
      setRequest(dut, 1, AllowedAddress)
      bool(dut.io.in.request.ready) should be(true)
      dut.clock.step()
      clearRequest(dut)
      bool(dut.io.in.response.valid) should be(true)
      bool(dut.io.in.response.bits.fault) should be(true)
      long(dut.io.in.response.bits.ID) should be(1L)
      dut.io.in.response.ready.poke(true.B)
      dut.clock.step()
      dut.io.in.response.ready.poke(false.B)

      // R/W/X：同一地址不再 fault。
      dut.io.config(0).poke(0x1f.U(8.W))
      setRequest(dut, 2, AllowedAddress)
      bool(dut.io.in.request.ready) should be(true)
      bool(dut.io.out.request.valid) should be(true)
      bool(dut.io.in.response.valid) should be(false)
    }
  }

  it should "faultQueueDepth=1 时队列满反压第二笔请求，排空后可继续接收" in {
    simulate(new ysyx_26030103_PTWPMPFilter(faultQueueDepth = 1)) { dut =>
      resetDut(dut)
      allowFirst4KiB(dut)

      // 第一笔 fault 请求入队，队列随即满。
      setRequest(dut, 1, FaultAddress)
      bool(dut.io.in.request.ready) should be(true)
      dut.clock.step()

      // 不消费响应，队列保持满。
      bool(dut.io.in.response.valid) should be(true)
      long(dut.io.in.response.bits.ID) should be(1L)

      // 第二笔 fault 请求在队列满时必须被反压。
      setRequest(dut, 2, FaultAddress)
      bool(dut.io.in.request.ready) should be(false)
      bool(dut.io.out.request.valid) should be(false)
      // 反压期间队首响应保持不变。
      dut.clock.step(2)
      bool(dut.io.in.request.ready) should be(false)
      bool(dut.io.in.response.valid) should be(true)
      long(dut.io.in.response.bits.ID) should be(1L)

      // 排空第一笔后，仍挂着的第二笔应被接收。
      dut.io.in.response.ready.poke(true.B)
      dut.clock.step()
      bool(dut.io.in.response.valid) should be(false)
      bool(dut.io.in.request.ready) should be(true)
      dut.clock.step()
      clearRequest(dut)
      dut.io.in.response.ready.poke(false.B)

      bool(dut.io.in.response.valid) should be(true)
      bool(dut.io.in.response.bits.fault) should be(true)
      long(dut.io.in.response.bits.ID) should be(2L)

      // 全部排空后队列重新可用。
      dut.io.in.response.ready.poke(true.B)
      dut.clock.step()
      dut.io.in.response.ready.poke(false.B)
      bool(dut.io.in.response.valid) should be(false)
    }
  }

  it should "faultQueueDepth=2 时可连续接收两笔、第三笔被反压" in {
    simulate(new ysyx_26030103_PTWPMPFilter(faultQueueDepth = 2)) { dut =>
      resetDut(dut)
      allowFirst4KiB(dut)

      setRequest(dut, 1, FaultAddress)
      bool(dut.io.in.request.ready) should be(true)
      dut.clock.step()

      setRequest(dut, 2, FaultAddress)
      bool(dut.io.in.request.ready) should be(true)
      dut.clock.step()

      // 队列已满，第三笔被反压。
      setRequest(dut, 3, FaultAddress)
      bool(dut.io.in.request.ready) should be(false)
      dut.clock.step(2)
      bool(dut.io.in.request.ready) should be(false)
      clearRequest(dut)

      // 按 FIFO 顺序返回两笔 fault 响应。
      bool(dut.io.in.response.valid) should be(true)
      long(dut.io.in.response.bits.ID) should be(1L)
      dut.io.in.response.ready.poke(true.B)
      dut.clock.step()
      bool(dut.io.in.response.valid) should be(true)
      long(dut.io.in.response.bits.ID) should be(2L)
      dut.clock.step()
      dut.io.in.response.ready.poke(false.B)
      bool(dut.io.in.response.valid) should be(false)

      // 排空后队列可再次接收。
      setRequest(dut, 4, FaultAddress)
      bool(dut.io.in.request.ready) should be(true)
    }
  }

  it should "未被拒绝的请求走 out.request 通路且 ready 跟随 out.request.ready" in {
    simulate(new ysyx_26030103_PTWPMPFilter(faultQueueDepth = 8)) { dut =>
      resetDut(dut)
      allowFirst4KiB(dut)
      setRequest(dut, 7, AllowedAddress)

      bool(dut.io.in.response.valid) should be(false)

      // 正常路径：in.request.ready 直接由 out.request.ready 决定。
      dut.io.out.request.ready.poke(false.B)
      bool(dut.io.in.request.ready) should be(false)
      bool(dut.io.out.request.valid) should be(true)
      long(dut.io.out.request.bits.ID) should be(7L)
      long(dut.io.out.request.bits.address) should be(AllowedAddress)

      dut.io.out.request.ready.poke(true.B)
      bool(dut.io.in.request.ready) should be(true)
      dut.clock.step()
      clearRequest(dut)

      // 下游响应原样透传到 in.response。
      dut.io.in.response.ready.poke(true.B)
      dut.io.out.response.valid.poke(true.B)
      dut.io.out.response.bits.ID.poke(7.U(4.W))
      dut.io.out.response.bits.data.poke(0xdeadbeefL.U(32.W))
      dut.io.out.response.bits.fault.poke(false.B)
      bool(dut.io.in.response.valid) should be(true)
      bool(dut.io.out.response.ready) should be(true)
      long(dut.io.in.response.bits.ID) should be(7L)
      long(dut.io.in.response.bits.data) should be(0xdeadbeefL)
      bool(dut.io.in.response.bits.fault) should be(false)
    }
  }
}
