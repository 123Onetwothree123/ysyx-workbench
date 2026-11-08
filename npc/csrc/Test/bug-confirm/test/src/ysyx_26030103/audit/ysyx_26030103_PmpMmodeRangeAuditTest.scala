package ysyx_26030103.audit

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import _root_.ysyx_26030103.infra.ysyx_26030103_PMP
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** PMP M 模式范围审计：未锁定表项不应限制 M 模式，即使访问越出其末尾。
  *
  * RISC-V PMP 只有在表项 L=1 时才约束 M 模式；L=0 的表项对 M 模式没有任何
  * 限制，M 模式可以访问任何地址。现有 `checkRange` 却先用 `allCovered`
  * 判断“最低匹配表项是否覆盖整个访问范围”，再与 M 模式放行条件并联，
  * 于是 M 模式下一次跨越 4 字节 NA4 未锁定表项末尾的多字节访问仍被
  * `!allCovered` 判成 fault。
  *
  * 本测试直接例化 PMP 叶子模块：
  *   - entry0 = 4 字节 NA4 @0x80000000，R/W/X，L=0；
  *   - M 模式 8 字节读 0x80000000..0x80000007 正确行为是不 fault；
  *   - 同样越界的 S 模式访问必须 fault（对照组，证明范围确实越界）；
  *   - M 模式完全落在 4 字节 NA4 内的访问不得 fault（对照组）。
  * 若第一条断言失败，即确认上述 M 模式范围缺陷。
  */
class ysyx_26030103_PmpMmodeRangeAuditTest extends AnyFlatSpec with Matchers {
  private def bool(v: Bool): Boolean = v.peek().litValue == 1

  /** 复位、清空全部 16 个 cfg/addr，并配置 entry0 为 4 字节 NA4。 */
  private def setup(dut: ysyx_26030103_PMP): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    for (i <- 0 until 16) {
      dut.io.cfg(i).poke(0.U)
      dut.io.addr(i).poke(0.U)
    }
    // entry0：A=NA4（4 字节，0x80000000..0x80000003），R/W/X，L=0。
    // cfg=0x17；pmpaddr = 0x80000000 >> 2 = 0x20000000。
    dut.io.cfg(0).poke("h17".U)
    dut.io.addr(0).poke("h20000000".U)

    // fetch 侧固定为 M 模式地址 0，避免干扰。
    dut.io.fetchAddr.poke(0.U)
    dut.io.fetchPriv.poke(3.U)

    // 数据侧默认：M 模式读 0x80000000（不越界）。
    dut.io.dataAddr.poke("h80000000".U)
    dut.io.dataLastAddr.poke("h80000000".U)
    dut.io.dataPriv.poke(3.U)
    dut.io.dataRead.poke(true.B)
    dut.io.dataWrite.poke(false.B)
  }

  private def setDataAccess(
      dut: ysyx_26030103_PMP,
      addr: Long,
      lastAddr: Long,
      priv: Int
  ): Unit = {
    dut.io.dataAddr.poke(addr.U(32.W))
    dut.io.dataLastAddr.poke(lastAddr.U(32.W))
    dut.io.dataPriv.poke(priv.U(2.W))
    dut.io.dataRead.poke(true.B)
    dut.io.dataWrite.poke(false.B)
  }

  "PMP M-mode range check" should
    "not fault an unlocked entry even when an access crosses its end" in {
      simulate(new ysyx_26030103_PMP(entries = 4, AddressWidth = 32)) { dut =>
        setup(dut)

        // 1) M 模式 8 字节读，跨越 4 字节 NA4 表项末尾。
        //    未锁定表项不约束 M 模式，因此正确行为是不 fault。
        setDataAccess(dut, 0x80000000L, 0x80000007L, 3)
        val mRangeFault = bool(dut.io.dataFault)

        // 2) 同样越界的 S 模式读必须 fault，证明该范围确实越界。
        setDataAccess(dut, 0x80000000L, 0x80000007L, 1)
        val sRangeFault = bool(dut.io.dataFault)

        // 3) M 模式 4 字节读，完全包含在 NA4 区域 0x80000000..0x80000003。
        setDataAccess(dut, 0x80000000L, 0x80000003L, 3)
        val mContainedFault = bool(dut.io.dataFault)

        withClue(
          s"M-mode range audit: mRangeFault=$mRangeFault, " +
            s"mContainedFault=$mContainedFault, sRangeFault=$sRangeFault; "
        ) {
          // M 模式不受 L=0 表项限制：越界访问必须放行。
          mRangeFault should be(false)
          // 同一越界访问在 S 模式必须 fault（隔离 harness 与范围逻辑）。
          sRangeFault should be(true)
          // 完全落在 NA4 区域内的 M 模式访问必须放行（隔离特权级逻辑）。
          mContainedFault should be(false)
        }
      }
    }
}
