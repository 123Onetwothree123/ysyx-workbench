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

  // 本套件的历史语义是 RV32（Sv32：2 级、4B PTE、32 位 VA）。
  // 64 位 Kconfig 默认下必须显式构造等价的 RV32 profile，而不是依赖默认值。
  private def rv32Profile: ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile.fromEnv(_ => None, xlen = 32)

  "TLB" should "支持 1 项 1 路" in {
    emit(new ysyx_26030103_TLB(entries = 1, ways = 1, ASIDBits = 0, globalEnable = false))
  }

  it should "支持组相联、ASID 和 Global" in {
    emit(new ysyx_26030103_TLB(entries = 8, ways = 2, ASIDBits = 9, globalEnable = true))
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
      pageBits = 16, levels = 2, indexBits = 8,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      PTEWidth = 32, PTEPPNHigh = 31
    ))
    // 1KiB 页 + 2 级 + 2048 项/级。
    emit(new ysyx_26030103_TLB(
      entries = 4, ways = 1, superpage = true,
      pageBits = 10, levels = 2, indexBits = 11,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      PTEWidth = 32, PTEPPNHigh = 31
    ))
    // 4 级 + 32 项/级，覆盖多个超级页层级。
    emit(new ysyx_26030103_TLB(
      entries = 4, ways = 1, superpage = true,
      pageBits = 12, levels = 4, indexBits = 5,
      XLEN = 32, addressWidth = 32, vaBits = 32,
      PTEWidth = 32, PTEPPNHigh = 31
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
    emit(new ysyx_26030103_MMU(rv32Profile))
    emit(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true,
      TLBEnabled = true
    )))
    emit(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true,
      TLBEnabled = false
    )))
  }

  it should "支持 unified TLB 几何" in {
    emit(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true,
      TLBEnabled = true,
      TLBOrganization = "unified",
      utlbEntries = 8,
      utlbWays = 2
    )))
  }

  it should "HAS_U=n 且 MMU 打开时顶层可 elaboration" in {
    emit(new _root_.ysyx_26030103.ysyx_26030103(ysyx_26030103_NPCConfig(
      XLEN = 32,
      AddressWidth = 32,

      MMU = rv32Profile.copy(
        enabled = true,
        TLBEnabled = true,
        hasU = false
      )
    )))
  }

  it should "VIPT 数据缓存打开时顶层可 elaboration" in {
    // 数据侧并行 VIPT 需要 DCache 打开；store 也会先以 VA 进入 LSU 写
    // 缓冲，等带 VA 标签的 PA 到达后才提交。这里保证该配置的顶层接线
    // 与所有端口都能完整 elaboration。
    emit(new _root_.ysyx_26030103.ysyx_26030103(ysyx_26030103_NPCConfig(
      XLEN = 32,
      AddressWidth = 32,

      DCacheEnable = true,
      MMU = rv32Profile.copy(
        enabled = true,
        TLBEnabled = true,
        cacheMode = "vipt"
      )
    )))
  }

  it should "HAS_PMP=y 且 HAS_MMU=n 时顶层可 elaboration" in {
    emit(new _root_.ysyx_26030103.ysyx_26030103(ysyx_26030103_NPCConfig(
      XLEN = 32,
      AddressWidth = 32,

      MMU = rv32Profile.copy(
        enabled = false,
        PMPEnabled = true,
        PMPEntries = 16
      )
    )))
  }

  it should "HAS_S=n 的 M-only 顶层可 elaboration 且不生成 MMU/TLB/PTW" in {
    def config = ysyx_26030103_NPCConfig(
      XLEN = 32,
      AddressWidth = 32,

      MMU = rv32Profile.copy(
        enabled = false,
        TLBEnabled = false,
        hasS = false,
        hasU = false,
        PMPEnabled = true,
        PMPEntries = 16
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

  it should "HAS_S=n/HAS_U=y 的 M+U 顶层可 elaboration 且不生成 MMU/TLB/PTW" in {
    def config = ysyx_26030103_NPCConfig(
      XLEN = 32,
      AddressWidth = 32,

      MMU = rv32Profile.copy(
        enabled = false,
        TLBEnabled = false,
        hasS = false,
        hasU = true,
        PMPEnabled = true,
        PMPEntries = 16
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
      rv32Profile.copy(enabled = true, hasS = false, hasU = false)
    }
    error.getMessage should include("HAS_MMU=y 需要 HAS_S=y")
  }

  it should "非标准 page/levels 的 TLB 超级页可 elaboration" in {
    val profile = rv32Profile.copy(
      enabled = true,
      TLBEnabled = true,
      pageSizeBytes = 65536,
      levels = 2,
      pageTableSizeBytes = 1024,
      superpage = true,
      TLBSuperpage = true
    )
    emit(new ysyx_26030103_MMU(profile))
    emit(new _root_.ysyx_26030103.ysyx_26030103(
      ysyx_26030103_NPCConfig(
      XLEN = 32,
      AddressWidth = 32,
      MMU = profile)
    ))

    val fourLevel = rv32Profile.copy(
      enabled = true,
      TLBEnabled = true,
      pageSizeBytes = 4096,
      levels = 4,
      pageTableSizeBytes = 128,
      superpage = true,
      TLBSuperpage = true
    )
    emit(new ysyx_26030103_MMU(fourLevel))
  }

  it should "支持 unified 双查找端口" in {
    emit(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true,
      TLBEnabled = true,
      TLBOrganization = "unified",
      utlbEntries = 8,
      utlbWays = 2,
      utlbLookupPorts = 2,
      refillPorts = 2,
      PTWOutstanding = 2
    )))
  }

  it should "支持 independent 与 pipelined 两种 PTW 结构" in {
    emit(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true, TLBEnabled = true, PTWArch = "independent", PTWOutstanding = 2
    )))
    emit(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true, TLBEnabled = true, PTWArch = "pipelined", PTWOutstanding = 2
    )))
  }

  "PTWPipeline" should "支持标准/非标准几何和 4B/8B PTE" in {
    emit(new ysyx_26030103_PTWPipeline(outstanding = 2))
    emit(new ysyx_26030103_PTWPipeline(
      outstanding = 4, pageBits = 10, levels = 2, tableEntries = 2048,
      cacheEnable = true, superpage = true,
      XLEN = 32, addressWidth = 32, vaBits = 32
    ))
    emit(new ysyx_26030103_PTWPipeline(
      outstanding = 2, levels = 4, tableEntries = 32, PTESizeBytes = 8,
      XLEN = 32, addressWidth = 32, vaBits = 32
    ))
  }

  it should "支持可配置的页表总线多 outstanding" in {
    emit(new ysyx_26030103_PTWPipeline(
      outstanding = 4, busOutstanding = 4, adMode = "hardware",
      cacheEnable = true, superpage = true
    ))
    emit(new ysyx_26030103_PTWPipeline(
      outstanding = 4, busOutstanding = 1, PTESizeBytes = 8,
      levels = 4, tableEntries = 32,
      XLEN = 32, addressWidth = 32, vaBits = 32
    ))
    emit(new ysyx_26030103_PTWCluster(
      outstanding = 4, busOutstanding = 4, adMode = "hardware",
      cacheEnable = true, superpage = true
    ))

    val pipelineProfile = rv32Profile.copy(
      enabled = true,
      TLBEnabled = true,
      PTWArch = "pipelined",
      PTWOutstanding = 4,
      PTWBusOutstanding = 4,
      adMode = "hardware"
    )
    emit(new ysyx_26030103_MMU(pipelineProfile))
    emit(new _root_.ysyx_26030103.ysyx_26030103(
      ysyx_26030103_NPCConfig(
      XLEN = 32,
      AddressWidth = 32,
      MMU = pipelineProfile)
    ))

    val independentProfile = pipelineProfile.copy(
      PTWArch = "independent",
      PTWBusOutstanding = 2
    )
    emit(new ysyx_26030103_MMU(independentProfile))
    emit(new _root_.ysyx_26030103.ysyx_26030103(
      ysyx_26030103_NPCConfig(
      XLEN = 32,
      AddressWidth = 32,
      MMU = independentProfile)
    ))
  }

  it should "支持 follow_tlb/global miss queue 的不同队列组织" in {
    emit(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true, TLBEnabled = true, TLBOrganization = "split",
      TLBMissQueueMode = "follow_tlb", itlbMissQueueEntries = 2,
      dtlbMissQueueEntries = 3, PTWOutstanding = 2
    )))
    emit(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true, TLBEnabled = true, TLBOrganization = "unified",
      TLBMissQueueMode = "follow_tlb", utlbMissQueueEntries = 4,
      utlbLookupPorts = 2, PTWOutstanding = 2
    )))
    emit(new ysyx_26030103_MMU(rv32Profile.copy(
      enabled = true, TLBEnabled = true, TLBMissQueueMode = "global",
      globalMissQueueEntries = 4, PTWOutstanding = 2
    )))
  }

  it should "8B PTE 打开 PTW cache 时可 elaboration" in {
    emit(new ysyx_26030103_PTW(
      adMode = "fault", levels = 4, tableEntries = 32, PTESizeBytes = 8,
      cacheEnable = true, cacheEntries = 8, cacheWays = 2,
      XLEN = 32, addressWidth = 32, vaBits = 32
    ))
    emit(new ysyx_26030103_PTWPipeline(
      outstanding = 2, levels = 4, tableEntries = 32, PTESizeBytes = 8,
      cacheEnable = true, cacheEntries = 8, cacheWays = 2,
      XLEN = 32, addressWidth = 32, vaBits = 32
    ))
    val eightByteProfile = rv32Profile.copy(
      enabled = true, TLBEnabled = true, levels = 4,
      pageTableSizeBytes = 256, PTESizeBytes = 8,
      PTWCache = true, PTWCacheEntries = 8, PTWCacheWays = 2
    )
    emit(new ysyx_26030103_MMU(eightByteProfile))
    // 顶层同样要验证 8B PTE + PTW cache 的完整接线。
    emit(new _root_.ysyx_26030103.ysyx_26030103(
      ysyx_26030103_NPCConfig(
      XLEN = 32,
      AddressWidth = 32,
      MMU = eightByteProfile)
    ))
  }
}
