package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.infra.ysyx_26030103_PLIC
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** PLIC source 下标审计：确认两个与 `numSources` 位宽相关的问题。
  *
  * 被测 RTL：`npc/src/Infra/ysyx_26030103_PLIC.scala`
  *
  * A. pending 寄存器是 `numSources` 位（第 50 行 `RegInit(0.U(numSources.W))`），
  *    但 pending 读窗口只有一个 32 位字：
  *      - 第 84 行 `PendingAddr = addrOf(0x1000L)` 只解码 `base+0x1000`；
  *      - 第 243 行 `RWord := pending` 把 64 位 pending 赋给 32 位 `RWord`
  *        （第 141 行），高位被截断。
  *    于是 numSources > 32 时 source >= 32 永远不会被软件看到。enable 寄存器
  *    已经按 `Words = ceil(numSources/32)` 支持多字（第 33、46、100-108 行），
  *    所以 64-source 的 PLIC 显然“意图”支持 source >= 32；pending 缺少高字
  *    是缺陷。
  *
  *    重要地址语义：source 40 对应 pending 的 bit 40，即 **word 1 的 bit 8**，
  *    而不是 word 0 的 bit 8（word 0 的 bit 8 是 source 8）。因此正确实现下：
  *      - `base+0x1000`（word 0）读回 bit 8 == 0；
  *      - `base+0x1004`（word 1）读回 bit 8 == 1 且 RRESP == OKAY。
  *    当前 RTL 对 `base+0x1004` 落入 `.otherwise` 分支（第 251-254 行），
  *    返回 data=0、RRESP=SLVERR。断言“高字应可见”会失败，即确认缺陷 A。
  *
  * B. complete ID 截断：
  *      - 第 199 行 `val doneIdx = WWord(IdBits - 1, 0)`，numSources=32 时
  *        IdBits=5，writing 33(=0b100001) 被截成 1；
  *      - 第 201 行 `completeFire := doneIdx < numSources.U` 用的是截断后的值，
  *        所以越界 ID 33 仍被判为合法；
  *      - 第 261-271 行据此清掉 claimed(1)。
  *    正确行为：complete ID 超出 [1, numSources-1] 必须被忽略。若先 claim
  *    source 1，再写 complete=33，source 1 应保持 claimed（pending 仍为 0、
  *    external_irq 仍为低、再次 claim 返回 0）。当前 RTL 会清 claimed(1)，
  *    于是 pending(1) 重新置位、irq 抬高、再次 claim 返回 1。断言会失败，
  *    即确认缺陷 B。
  */
class ysyx_26030103_PLICSourceIndexAuditTest
    extends AnyFlatSpec with Matchers {
  /** 单次 AXI 事务的轮询上限，避免 RTL 缺陷导致测试死循环。 */
  private val BusTimeout = 200

  private def bool(v: Bool): Boolean = v.peek().litValue == 1

  private def setup(dut: ysyx_26030103_PLIC): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.sources.poke(0.U)
    dut.io.AW.AWVALID.poke(false.B)
    dut.io.AW.AWADDR.poke(0.U)
    dut.io.AW.AWID.poke(0.U)
    dut.io.AW.AWLEN.poke(0.U)
    dut.io.AW.AWSIZE.poke(2.U)
    dut.io.AW.AWBURST.poke(0.U)
    dut.io.W.WVALID.poke(false.B)
    dut.io.W.WDATA.poke(0.U)
    dut.io.W.WSTRB.poke(0.U)
    dut.io.W.WLAST.poke(false.B)
    dut.io.B.BREADY.poke(false.B)
    dut.io.AR.ARVALID.poke(false.B)
    dut.io.AR.ARADDR.poke(0.U)
    dut.io.AR.ARID.poke(0.U)
    dut.io.AR.ARLEN.poke(0.U)
    dut.io.AR.ARSIZE.poke(2.U)
    dut.io.AR.ARBURST.poke(0.U)
    dut.io.R.RREADY.poke(false.B)
  }

  private case class WriteResult(resp: Int, cycles: Int)

  /** 单拍 AXI 写；返回 B 通道响应与等待拍数。 */
  private def write(
      dut: ysyx_26030103_PLIC,
      addr: Long,
      data: BigInt
  ): WriteResult = {
    var cycles = 0
    dut.io.AW.AWVALID.poke(true.B)
    dut.io.AW.AWADDR.poke(addr.U)
    dut.io.AW.AWLEN.poke(0.U)
    dut.io.AW.AWSIZE.poke(2.U)
    dut.io.AW.AWBURST.poke(0.U)
    dut.io.W.WVALID.poke(true.B)
    dut.io.W.WDATA.poke(data.U(32.W))
    dut.io.W.WSTRB.poke(15.U)
    dut.io.W.WLAST.poke(true.B)
    dut.clock.step()
    dut.io.AW.AWVALID.poke(false.B)
    dut.io.W.WVALID.poke(false.B)

    while (!bool(dut.io.B.BVALID) && cycles < BusTimeout) {
      dut.clock.step()
      cycles += 1
    }
    val resp =
      if (bool(dut.io.B.BVALID)) dut.io.B.BRESP.peek().litValue.toInt else -1
    dut.io.B.BREADY.poke(true.B)
    dut.clock.step()
    dut.io.B.BREADY.poke(false.B)
    WriteResult(resp, cycles)
  }

  private case class ReadResult(data: BigInt, resp: Int, cycles: Int)

  /** 单拍 AXI 读；返回 R 通道数据、响应与等待拍数。 */
  private def read(
      dut: ysyx_26030103_PLIC,
      addr: Long
  ): ReadResult = {
    var cycles = 0
    dut.io.AR.ARVALID.poke(true.B)
    dut.io.AR.ARADDR.poke(addr.U)
    dut.io.AR.ARLEN.poke(0.U)
    dut.io.AR.ARSIZE.poke(2.U)
    dut.io.AR.ARBURST.poke(0.U)
    dut.clock.step()
    dut.io.AR.ARVALID.poke(false.B)

    while (!bool(dut.io.R.RVALID) && cycles < BusTimeout) {
      dut.clock.step()
      cycles += 1
    }
    val valid = bool(dut.io.R.RVALID)
    val data = if (valid) dut.io.R.RDATA.peek().litValue else BigInt(-1)
    val resp = if (valid) dut.io.R.RRESP.peek().litValue.toInt else -1
    dut.io.R.RREADY.poke(true.B)
    dut.clock.step()
    dut.io.R.RREADY.poke(false.B)
    ReadResult(data, resp, cycles)
  }

  "PLIC pending read" should
    "expose sources >= 32 in a second pending word" in {
      simulate(
        new ysyx_26030103_PLIC(
          numSources = 64,
          priorityBits = 3,
          maxPriority = 7,
          base = BigInt("0c000000", 16),
          AddressWidth = 32,
          DataWidth = 32
        )
      ) { dut =>
        setup(dut)

        // source 40：priority @ base + 4*40 = 0x0c0000a0。
        val prio = write(dut, 0x0c0000a0L, BigInt(7))
        // enable word1 的 bit8 -> source 40：0x0c002000 + 4 = 0x0c002004。
        val en = write(dut, 0x0c002004L, BigInt(1) << 8)
        // 单 hart 上下文 threshold = 0。
        val thr = write(dut, 0x0c200000L, BigInt(0))

        // 以电平方式抬高 source 40。
        dut.sources.poke((BigInt(1) << 40).U(64.W))
        dut.clock.step(2)

        // harness 自检：内核确实看到了 source 40（enable+priority 均生效）。
        val irq = bool(dut.external_irq)
        // enable word1 应可读回（证明多字 enable 布局存在）。
        val enBack = read(dut, 0x0c002004L)
        // pending word0：source 40 不在这里，bit8 应为 0。
        val lowWord = read(dut, 0x0c001000L)
        // pending word1：正确实现应在这里返回 source 40 的 bit8 且 OKAY。
        val highWord = read(dut, 0x0c001004L)

        withClue(
          s"PLIC source>=32 pending audit: " +
            s"irq=$irq, enableWord1=0x${enBack.data.toString(16)}" +
            s"(resp=${enBack.resp}), " +
            s"pendingWord0=0x${lowWord.data.toString(16)}" +
            s"(resp=${lowWord.resp}), " +
            s"pendingWord1=0x${highWord.data.toString(16)}" +
            s"(resp=${highWord.resp}), " +
            s"writeResps=(prio=${prio.resp},enable=${en.resp}," +
            s"threshold=${thr.resp}); "
        ) {
          // 基本配置写入必须成功，source 40 必须真实挂起。
          prio.resp should be(0)
          en.resp should be(0)
          thr.resp should be(0)
          irq should be(true)
          enBack.resp should be(0)
          enBack.data should be(BigInt(1) << 8)

          // word0 只承载 source 0..31，因此 source 40 不出现在 bit8。
          (lowWord.data & (BigInt(1) << 8)) should be(BigInt(0))
          lowWord.resp should be(0)

          // 正确多字 PLIC：word1 的 bit8 == source 40 == 1，且响应 OKAY。
          // 当前 RTL：0x1004 未被解码，返回 data=0 / SLVERR -> 断言失败。
          highWord.resp should be(0)
          ((highWord.data >> 8) & 1) should be(BigInt(1))
        }
      }
    }

  "PLIC complete" should
    "ignore an out-of-range complete ID instead of truncating it" in {
      simulate(new ysyx_26030103_PLIC()) { dut =>
        setup(dut)

        // source 1：priority=7、enable bit1、threshold=0。
        write(dut, 0x0c000004L, BigInt(7))
        write(dut, 0x0c002000L, BigInt(2))
        write(dut, 0x0c200000L, BigInt(0))
        dut.sources.poke(2.U)
        dut.clock.step(2)
        val irqBefore = bool(dut.external_irq)

        // claim source 1；随后 pending(1) 清 0、claimed(1) 置 1。
        val claim = read(dut, 0x0c200004L)
        val pendingAfterClaim = read(dut, 0x0c001000L)
        val irqAfterClaim = bool(dut.external_irq)

        // complete ID 33 = 0b100001；numSources=32 时合法 ID 只有 1..31。
        // 当前 RTL 用 WWord(4,0) 截断成 1，错误地完成 source 1。
        val complete = write(dut, 0x0c200004L, BigInt(33))
        // 给 pending 网关两个周期重新评估（源 1 仍保持高电平）。
        dut.clock.step(2)

        val pendingAfterBadComplete = read(dut, 0x0c001000L)
        val irqAfterBadComplete = bool(dut.external_irq)
        // 若 source 1 的 claim 状态被错误清除，这里会再次 claim 到 1。
        val reclaim = read(dut, 0x0c200004L)

        withClue(
          s"PLIC complete-ID truncation audit: " +
            s"irqBefore=$irqBefore, claim=${claim.data}" +
            s"(resp=${claim.resp}), " +
            s"pendingAfterClaim=0x${pendingAfterClaim.data.toString(16)}, " +
            s"irqAfterClaim=$irqAfterClaim, " +
            s"completeResp=${complete.resp}, " +
            s"pendingAfterBadComplete=" +
            s"0x${pendingAfterBadComplete.data.toString(16)}, " +
            s"irqAfterBadComplete=$irqAfterBadComplete, " +
            s"reclaim=${reclaim.data}(resp=${reclaim.resp}); "
        ) {
          // 前置条件：source 1 能正常中断并被 claim。
          irqBefore should be(true)
          claim.resp should be(0)
          claim.data should be(BigInt(1))
          (pendingAfterClaim.data & BigInt(2)) should be(BigInt(0))
          irqAfterClaim should be(false)

          // 正确行为：越界 complete ID 33 被忽略，source 1 保持 claimed。
          // 当前 RTL 截断成 1 并清 claimed(1)，导致下列断言失败。
          (pendingAfterBadComplete.data & BigInt(2)) should be(BigInt(0))
          irqAfterBadComplete should be(false)
          reclaim.resp should be(0)
          reclaim.data should be(BigInt(0))
        }
      }
    }
}
