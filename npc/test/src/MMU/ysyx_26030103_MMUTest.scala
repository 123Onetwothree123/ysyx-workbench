package ysyx_26030103.mmu

import _root_.circt.stage.ChiselStage
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile
import _root_.ysyx_26030103.config.ysyx_26030103_NPCConfig
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

  it should "支持两个查找端口" in {
    emit(new ysyx_26030103_TLB(
      entries = 8, ways = 2, lookupPorts = 2, refillPorts = 2
    ))
  }

  it should "支持非标准 pageBits/levels/indexBits 的超级页几何" in {
    // 64KiB 页 + 2 级 + 256 项/级。
    emit(new ysyx_26030103_TLB(
      entries = 4, ways = 1, superpage = true,
      pageBits = 16, levels = 2, indexBits = 8
    ))
    // 1KiB 页 + 2 级 + 2048 项/级。
    emit(new ysyx_26030103_TLB(
      entries = 4, ways = 1, superpage = true,
      pageBits = 10, levels = 2, indexBits = 11
    ))
    // 4 级 + 32 项/级，覆盖多个超级页层级。
    emit(new ysyx_26030103_TLB(
      entries = 4, ways = 1, superpage = true,
      pageBits = 12, levels = 4, indexBits = 5
    ))
  }

  "PTW" should "支持已实现的 A/D 模式" in {
    emit(new ysyx_26030103_PTW("fault"))
    emit(new ysyx_26030103_PTW("ignore"))
  }

  it should "HAS_U=n 时可裁掉 U/SUM 状态" in {
    emit(new ysyx_26030103_PTW("fault", hasU = false))
    emit(new ysyx_26030103_PTWPipeline(hasU = false))
    emit(new ysyx_26030103_TLB(entries = 4, ways = 1, hasU = false))
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

  it should "HAS_U=n 且 MMU 打开时顶层可 elaboration" in {
    emit(new _root_.ysyx_26030103.ysyx_26030103(ysyx_26030103_NPCConfig(
      MMU = ysyx_26030103_MMUProfile(
        enabled = true,
        tlbEnabled = true,
        hasU = false
      )
    )))
  }

  it should "VIPT 数据缓存打开时顶层可 elaboration" in {
    // 数据侧并行 VIPT 需要 DCache 打开；store 也会先以 VA 进入 LSU 写
    // 缓冲，等带 VA 标签的 PA 到达后才提交。这里保证该配置的顶层接线
    // 与所有端口都能完整 elaboration。
    emit(new _root_.ysyx_26030103.ysyx_26030103(ysyx_26030103_NPCConfig(
      DCacheEnable = true,
      MMU = ysyx_26030103_MMUProfile(
        enabled = true,
        tlbEnabled = true,
        cacheMode = "vipt"
      )
    )))
  }

  it should "HAS_PMP=y 且 HAS_MMU=n 时顶层可 elaboration" in {
    emit(new _root_.ysyx_26030103.ysyx_26030103(ysyx_26030103_NPCConfig(
      MMU = ysyx_26030103_MMUProfile(
        enabled = false,
        pmpEnabled = true,
        pmpEntries = 16
      )
    )))
  }

  it should "HAS_S=n 的 M-only 顶层可 elaboration 且不生成 MMU/TLB/PTW" in {
    def config = ysyx_26030103_NPCConfig(
      MMU = ysyx_26030103_MMUProfile(
        enabled = false,
        tlbEnabled = false,
        hasS = false,
        hasU = false,
        pmpEnabled = true,
        pmpEntries = 16
      )
    )
    val chirrtl = ChiselStage.emitCHIRRTL(
      new _root_.ysyx_26030103.ysyx_26030103(config)
    )
    Seq("ysyx_26030103_MMU", "ysyx_26030103_TLB",
      "ysyx_26030103_PTW", "ysyx_26030103_IFUMMU",
      "ysyx_26030103_LSUMMU").foreach { name =>
      chirrtl should not include name
    }
    emit(new _root_.ysyx_26030103.ysyx_26030103(config))
  }

  it should "HAS_MMU=y 且 HAS_S=n 时在 elaboration 前明确报错" in {
    val error = intercept[IllegalArgumentException] {
      ysyx_26030103_MMUProfile(enabled = true, hasS = false, hasU = false)
    }
    error.getMessage should include("HAS_MMU=y 需要 HAS_S=y")
  }

  it should "非标准 page/levels 的 TLB 超级页可 elaboration" in {
    val profile = ysyx_26030103_MMUProfile(
      enabled = true,
      tlbEnabled = true,
      pageSizeBytes = 65536,
      levels = 2,
      pageTableSizeBytes = 1024,
      superpage = true,
      tlbSuperpage = true
    )
    emit(new ysyx_26030103_MMU(profile))
    emit(new _root_.ysyx_26030103.ysyx_26030103(
      ysyx_26030103_NPCConfig(MMU = profile)
    ))

    val fourLevel = ysyx_26030103_MMUProfile(
      enabled = true,
      tlbEnabled = true,
      pageSizeBytes = 4096,
      levels = 4,
      pageTableSizeBytes = 128,
      superpage = true,
      tlbSuperpage = true
    )
    emit(new ysyx_26030103_MMU(fourLevel))
  }

  it should "支持 unified 双查找端口" in {
    emit(new ysyx_26030103_MMU(ysyx_26030103_MMUProfile(
      enabled = true,
      tlbEnabled = true,
      tlbOrganization = "unified",
      utlbEntries = 8,
      utlbWays = 2,
      utlbLookupPorts = 2,
      refillPorts = 2,
      ptwOutstanding = 2
    )))
  }

  it should "支持 independent 与 pipelined 两种 PTW 结构" in {
    emit(new ysyx_26030103_MMU(ysyx_26030103_MMUProfile(
      enabled = true, tlbEnabled = true, ptwArch = "independent", ptwOutstanding = 2
    )))
    emit(new ysyx_26030103_MMU(ysyx_26030103_MMUProfile(
      enabled = true, tlbEnabled = true, ptwArch = "pipelined", ptwOutstanding = 2
    )))
  }

  "PTWPipeline" should "支持标准/非标准几何和 4B/8B PTE" in {
    emit(new ysyx_26030103_PTWPipeline(outstanding = 2))
    emit(new ysyx_26030103_PTWPipeline(
      outstanding = 4, pageBits = 10, levels = 2, tableEntries = 2048,
      cacheEnable = true, superpage = true
    ))
    emit(new ysyx_26030103_PTWPipeline(
      outstanding = 2, levels = 4, tableEntries = 32, pteSizeBytes = 8
    ))
  }

  it should "支持可配置的页表总线多 outstanding" in {
    emit(new ysyx_26030103_PTWPipeline(
      outstanding = 4, busOutstanding = 4, adMode = "hardware",
      cacheEnable = true, superpage = true
    ))
    emit(new ysyx_26030103_PTWPipeline(
      outstanding = 4, busOutstanding = 1, pteSizeBytes = 8,
      levels = 4, tableEntries = 32
    ))
    emit(new ysyx_26030103_PTWCluster(
      outstanding = 4, busOutstanding = 4, adMode = "hardware",
      cacheEnable = true, superpage = true
    ))

    val pipelineProfile = ysyx_26030103_MMUProfile(
      enabled = true,
      tlbEnabled = true,
      ptwArch = "pipelined",
      ptwOutstanding = 4,
      ptwBusOutstanding = 4,
      adMode = "hardware"
    )
    emit(new ysyx_26030103_MMU(pipelineProfile))
    emit(new _root_.ysyx_26030103.ysyx_26030103(
      ysyx_26030103_NPCConfig(MMU = pipelineProfile)
    ))

    val independentProfile = pipelineProfile.copy(
      ptwArch = "independent",
      ptwBusOutstanding = 2
    )
    emit(new ysyx_26030103_MMU(independentProfile))
    emit(new _root_.ysyx_26030103.ysyx_26030103(
      ysyx_26030103_NPCConfig(MMU = independentProfile)
    ))
  }

  it should "支持 follow_tlb/global miss queue 的不同队列组织" in {
    emit(new ysyx_26030103_MMU(ysyx_26030103_MMUProfile(
      enabled = true, tlbEnabled = true, tlbOrganization = "split",
      tlbMissQueueMode = "follow_tlb", itlbMissQueueEntries = 2,
      dtlbMissQueueEntries = 3, ptwOutstanding = 2
    )))
    emit(new ysyx_26030103_MMU(ysyx_26030103_MMUProfile(
      enabled = true, tlbEnabled = true, tlbOrganization = "unified",
      tlbMissQueueMode = "follow_tlb", utlbMissQueueEntries = 4,
      utlbLookupPorts = 2, ptwOutstanding = 2
    )))
    emit(new ysyx_26030103_MMU(ysyx_26030103_MMUProfile(
      enabled = true, tlbEnabled = true, tlbMissQueueMode = "global",
      globalMissQueueEntries = 4, ptwOutstanding = 2
    )))
  }

  it should "8B PTE 打开 PTW cache 时可 elaboration" in {
    emit(new ysyx_26030103_PTW(
      adMode = "fault", levels = 4, tableEntries = 32, pteSizeBytes = 8,
      cacheEnable = true, cacheEntries = 8, cacheWays = 2
    ))
    emit(new ysyx_26030103_PTWPipeline(
      outstanding = 2, levels = 4, tableEntries = 32, pteSizeBytes = 8,
      cacheEnable = true, cacheEntries = 8, cacheWays = 2
    ))
    val eightByteProfile = ysyx_26030103_MMUProfile(
      enabled = true, tlbEnabled = true, levels = 4,
      pageTableSizeBytes = 256, pteSizeBytes = 8,
      ptwCache = true, ptwCacheEntries = 8, ptwCacheWays = 2
    )
    emit(new ysyx_26030103_MMU(eightByteProfile))
    // 顶层同样要验证 8B PTE + PTW cache 的完整接线。
    emit(new _root_.ysyx_26030103.ysyx_26030103(
      ysyx_26030103_NPCConfig(MMU = eightByteProfile)
    ))
  }
}
