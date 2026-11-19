package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.common.ysyx_26030103_PMARegion
import _root_.ysyx_26030103.infra.ysyx_26030103_AXI5Xbar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** AXI5Xbar `LastByte` 的 ARLEN=255 溢出审计。
  *
  * 嫌疑缺陷（npc/source/Infra/AXI5/ysyx_26030103_AXI5Xbar.scala）：
  * {{{
  *   val TotalBytes = (Len + 1.U) * BeatBytes
  * }}}
  * `Len` 只有 8 位，Chisel 的 `+` 不增长位宽，因此 `ARLEN=255` 时
  * `Len + 1.U` 在 8 位内回绕为 0，`TotalBytes=0`，INCR 分支的
  * `AlignedStart + TotalBytes - 1.U` 变成 `AlignedStart - 1`（即 41 位
  * 全 1 加法回绕）。于是一笔真正跨越 PMA 末尾的 burst 被误判为落在
  * RAM 内，`decode` 返回 TargetSoCBus，AR 被转发给 SoCBus。
  *
  * 对照：同一仓库的 ysyx_26030103_AXI5WidthBridge.scala 第 109 行使用了
  * `Len.pad(9) + 1.U`，说明 Xbar 这里漏掉了位宽扩展。
  *
  * 用例：
  *   1. 主用例：ARADDR=0x83fffffc, ARLEN=255, ARSIZE=2, INCR。
  *      真实访问范围 0x83fffffc..0x840003fb，越过 PMA 末尾 0x84000000。
  *      正确行为是本地 DECERR，绝不向 SoCBus 发出 AR。
  *      当前 RTL 会把末字节算成 0x83fffffb（RAM 内）并发出 SoCBus AR，
  *      所以 `SOCARSeen should be(false)` 应当失败 —— 即确认缺陷。
  *   2. 控制用例：ARADDR=0x83fffff8, ARLEN=1, ARSIZE=2，末字节
  *      0x83ffffff 仍在 RAM 内，必须转发到 SoCBus，证明 harness 能看到
  *      合法的下游 AR，失败只与 8 位溢出有关。
  */
class ysyx_26030103_XbarBurst255AuditTest extends AnyFlatSpec with Matchers {
  private val AddressW = 32

  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def big(value: UInt): BigInt = value.peek().litValue

  private val Ram = ysyx_26030103_PMARegion(
    Base = 0x80000000L,
    Size = 0x04000000L, // EndExclusive = 0x84000000
    Readable = true,
    Writable = true,
    Cacheable = true,
    Executable = true
  )

  private def newDut(): ysyx_26030103_AXI5Xbar =
    new ysyx_26030103_AXI5Xbar(
      AddressWidth = AddressW,
      PMARegions = Seq(Ram),
      PLICBaseValue = BigInt("0c000000", 16),
      PLICSizeBytes = BigInt("00300000", 16),
      DataWidth = 32,
      IDWidth = 4
    )

  private case class Observed(
      SOCARSeen: Boolean,
      SOCAddress: BigInt,
      SOCLength: BigInt,
      response: Int,
      beats: Int
  ) {
    def clue: String =
      s"SOCARSeen=$SOCARSeen, socAddr=0x${SOCAddress.toString(16)}, " +
        s"SOCLength=$SOCLength, response=$response, beats=$beats"
  }

  /** 复位并把所有输入/下游响应模型置为静默。下游 ARREADY 保持低，
    * 因此一旦 Xbar 真的发出 SoCBus AR，ARVALID 会保持可见而不会被握手
    * 吃掉，便于稳定观测。`io.in.R.RREADY` 拉高，便于正确实现把本地
    * DECERR 数据拍送回来。
    */
  private def resetDut(dut: ysyx_26030103_AXI5Xbar): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)

    val in = dut.io.in
    in.AW.AWVALID.poke(false.B)
    in.AW.AWADDR.poke(0.U(AddressW.W))
    in.AW.AWID.poke(0.U(4.W))
    in.AW.AWLEN.poke(0.U(8.W))
    in.AW.AWSIZE.poke(2.U(3.W))
    in.AW.AWBURST.poke(0.U(2.W))
    in.AW.AWPROT.poke(0.U(3.W))
    in.AW.AWCACHE.poke(0.U(4.W))
    in.AW.AWQOS.poke(0.U(4.W))

    in.W.WVALID.poke(false.B)
    in.W.WDATA.poke(0.U(32.W))
    in.W.WSTRB.poke(0.U(4.W))
    in.W.WLAST.poke(false.B)
    in.B.BREADY.poke(false.B)

    in.AR.ARVALID.poke(false.B)
    in.AR.ARADDR.poke(0.U(AddressW.W))
    in.AR.ARID.poke(0.U(4.W))
    in.AR.ARLEN.poke(0.U(8.W))
    in.AR.ARSIZE.poke(2.U(3.W))
    in.AR.ARBURST.poke(0.U(2.W))
    in.AR.ARPROT.poke(0.U(3.W))
    in.AR.ARCACHE.poke(0.U(4.W))
    in.AR.ARQOS.poke(0.U(4.W))
    in.R.RREADY.poke(true.B)

    val SOC = dut.io.SoCBus
    SOC.AW.AWREADY.poke(false.B)
    SOC.W.WREADY.poke(false.B)
    SOC.B.BVALID.poke(false.B)
    SOC.B.BID.poke(0.U(4.W))
    SOC.B.BRESP.poke(0.U(2.W))
    SOC.AR.ARREADY.poke(false.B)
    SOC.R.RVALID.poke(false.B)
    SOC.R.RID.poke(0.U(4.W))
    SOC.R.RDATA.poke(0.U(32.W))
    SOC.R.RRESP.poke(0.U(2.W))
    SOC.R.RLAST.poke(false.B)

    val CLINT = dut.io.CLINT
    CLINT.AW.AWREADY.poke(false.B)
    CLINT.W.WREADY.poke(false.B)
    CLINT.B.BVALID.poke(false.B)
    CLINT.B.BID.poke(0.U(4.W))
    CLINT.B.BRESP.poke(0.U(2.W))
    CLINT.AR.ARREADY.poke(false.B)
    CLINT.R.RVALID.poke(false.B)
    CLINT.R.RID.poke(0.U(4.W))
    CLINT.R.RDATA.poke(0.U(32.W))
    CLINT.R.RRESP.poke(0.U(2.W))
    CLINT.R.RLAST.poke(false.B)

    val PLIC = dut.io.PLIC
    PLIC.AW.AWREADY.poke(false.B)
    PLIC.W.WREADY.poke(false.B)
    PLIC.B.BVALID.poke(false.B)
    PLIC.B.BID.poke(0.U(4.W))
    PLIC.B.BRESP.poke(0.U(2.W))
    PLIC.AR.ARREADY.poke(false.B)
    PLIC.R.RVALID.poke(false.B)
    PLIC.R.RID.poke(0.U(4.W))
    PLIC.R.RDATA.poke(0.U(32.W))
    PLIC.R.RRESP.poke(0.U(2.W))
    PLIC.R.RLAST.poke(false.B)
  }

  /** 注入恰好一笔 INCR 读 AR（ID=1），等待 1 拍握手后撤销 ARVALID。 */
  private def issueOneRead(
      dut: ysyx_26030103_AXI5Xbar,
      addr: Long,
      length: Int,
      size: Int
  ): Boolean = {
    val in = dut.io.in
    in.AR.ARADDR.poke(addr.U(AddressW.W))
    in.AR.ARID.poke(1.U(4.W))
    in.AR.ARLEN.poke(length.U(8.W))
    in.AR.ARSIZE.poke(size.U(3.W))
    in.AR.ARBURST.poke(1.U(2.W)) // INCR
    in.AR.ARPROT.poke(0.U(3.W))
    in.AR.ARCACHE.poke(0.U(4.W))
    in.AR.ARQOS.poke(0.U(4.W))
    in.AR.ARVALID.poke(true.B)

    val accepted = bool(in.AR.ARREADY)
    dut.clock.step()
    in.AR.ARVALID.poke(false.B)
    accepted
  }

  /** 观测固定拍数内的 SoCBus AR 与本地 R 响应。返回是否曾看到 SoCBus
    * ARVALID（以及其 ARADDR/ARLEN）和第一个上游 R beat 的 RRESP。
    */
  private def observe(
      dut: ysyx_26030103_AXI5Xbar,
      cycles: Int
  ): Observed = {
    var SOCARSeen = false
    var SOCAddress = BigInt(0)
    var SOCLength = BigInt(0)
    var response = -1
    var beats = 0
    var i = 0
    while (i < cycles) {
      if (bool(dut.io.SoCBus.AR.ARVALID)) {
        SOCARSeen = true
        SOCAddress = big(dut.io.SoCBus.AR.ARADDR)
        SOCLength = big(dut.io.SoCBus.AR.ARLEN)
      }
      if (bool(dut.io.in.R.RVALID) && bool(dut.io.in.R.RREADY)) {
        beats += 1
        if (response < 0) response = dut.io.in.R.RRESP.peek().litValue.toInt
      }
      dut.clock.step()
      i += 1
    }
    Observed(SOCARSeen, SOCAddress, SOCLength, response, beats)
  }

  "AXI5Xbar LastByte with ARLEN=255" should
    "reject a burst crossing the PMA end instead of forwarding it to SoCBus" in {
      simulate(newDut()) { dut =>
        resetDut(dut)

        // 0x83fffffc + 256 拍 * 4 字节 => 末字节 0x840003fb，越过
        // PMA 末尾 0x84000000。该 burst 必须在 Xbar 本地被拒绝。
        withClue("Xbar 未能接受上游 AR（harness/复位问题）: ") {
          issueOneRead(dut, 0x83fffffcL, 255, 2) should be(true)
        }

        val observed = observe(dut, 40)
        withClue(
          s"ARLEN=255 溢出审计: ${observed.clue}; " +
            "真实访问越过 0x84000000，Xbar 不应向 SoCBus 发出任何 AR: "
        ) {
          // 当前 `(Len + 1.U)` 在 8 位回绕，LastByte 算成 0x83fffffb，
          // 落在 RAM 内，因此这条断言应当失败，即确认缺陷。
          observed.SOCARSeen should be(false)
        }
        withClue(
          s"ARLEN=255 溢出审计: ${observed.clue}; " +
            "越界 burst 应在本地返回 DECERR: "
        ) {
          // 正确实现下本地 DECERR 首拍应在数十拍内出现（RRESP=3）。
          // 若 RTL 把请求转发到静默的 SoCBus，则永远不会返回该响应。
          observed.response should be(3)
        }
      }
    }

  it should
    "forward a legal burst whose last byte is inside the PMA region" in {
      simulate(newDut()) { dut =>
        resetDut(dut)
        // 控制组：ARADDR=0x83fffff8, ARLEN=1, ARSIZE=2 =>
        // 末字节 0x83ffffff，仍位于 RAM [0x80000000, 0x84000000) 内，
        // 必须转发给 SoCBus。放行 ARREADY 以完成一次真实握手。
        dut.io.SoCBus.AR.ARREADY.poke(true.B)

        withClue("Xbar 未能接受上游合法 AR: ") {
          issueOneRead(dut, 0x83fffff8L, 1, 2) should be(true)
        }

        val observed = observe(dut, 30)
        withClue(s"合法 burst 控制组: ${observed.clue}; ") {
          observed.SOCARSeen should be(true)
          observed.SOCAddress should be(BigInt(0x83fffff8L))
          observed.SOCLength should be(BigInt(1))
        }
        // 合法请求走 SoCBus，不应被本地判为 DECERR。
        withClue(s"合法 burst 不应本地 DECERR: ${observed.clue}; ") {
          observed.response should be(-1)
        }
      }
    }
}
