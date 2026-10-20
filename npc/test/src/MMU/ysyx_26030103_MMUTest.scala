package ysyx_26030103.mmu

import _root_.circt.stage.ChiselStage
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** 批次 1 的结构测试：先保证所有默认/边界参数都能 elaboration。
  *
  * 行为测试在后续仿真环境接入后补齐；这里至少防止 RTL 在配置组合下
  * 出现未连接、位宽不合法或参数错误。
  */
class ysyx_26030103_MMUTest extends AnyFlatSpec with Matchers {
  private def emit(module: => chisel3.Module): String =
    ChiselStage.emitSystemVerilog(module)

  "TLB" should "支持 1 项 1 路" in {
    emit(new ysyx_26030103_TLB(entries = 1, ways = 1, asidBits = 0, globalEnable = false))
  }

  it should "支持组相联、ASID 和 Global" in {
    emit(new ysyx_26030103_TLB(entries = 8, ways = 2, asidBits = 9, globalEnable = true))
  }

  it should "支持 A/D ignore 模式" in {
    emit(new ysyx_26030103_TLB(entries = 4, ways = 1, adMode = "ignore"))
  }

  it should "支持 rr/plru/lru 替换" in {
    emit(new ysyx_26030103_TLB(entries = 8, ways = 4, replacement = "rr"))
    emit(new ysyx_26030103_TLB(entries = 8, ways = 4, replacement = "plru"))
    emit(new ysyx_26030103_TLB(entries = 8, ways = 4, replacement = "lru"))
  }

  "PTW" should "支持已实现的 A/D 模式" in {
    emit(new ysyx_26030103_PTW("fault"))
    emit(new ysyx_26030103_PTW("ignore"))
  }

  "MMU" should "在 TLB 开关和 MMU 开关下都能 elaboration" in {
    emit(new ysyx_26030103_MMU(ysyx_26030103_MMUProfile()))
    emit(new ysyx_26030103_MMU(ysyx_26030103_MMUProfile(
      enabled = true,
      tlbEnabled = true
    )))
    emit(new ysyx_26030103_MMU(ysyx_26030103_MMUProfile(
      enabled = true,
      tlbEnabled = false
    )))
  }

  it should "支持 unified TLB 几何" in {
    emit(new ysyx_26030103_MMU(ysyx_26030103_MMUProfile(
      enabled = true,
      tlbEnabled = true,
      tlbOrganization = "unified",
      utlbEntries = 8,
      utlbWays = 2
    )))
  }
}
