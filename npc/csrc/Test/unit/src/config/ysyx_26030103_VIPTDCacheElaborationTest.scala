package ysyx_26030103.config

import _root_.circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.ysyx_26030103

/** VIPT + DCache 全 SoC 组合环回归。
  *
  * 修复点：`ysyx_26030103.scala` 中 DMMU 的 `memKill` 不再直接接到
  * DMMU->LSU 这个 StageConnect 的 flush，而是打一拍
  * (`DMMUMemKillForStage`)。直接组合连接会形成
  * `LSU.in.valid -> LSU.MemoryBusy -> EXU.MEMBusy -> EXU.FlushIF
  * -> DMMU.flush -> DMMU.memKill -> StageConnect.flush -> LSU.in.valid`
  * 的组合环；firtool 在生成 SystemVerilog 时会把它报成组合环错误。
  *
  * 因此这里必须走 `emitSystemVerilog`（会运行 firtool），不能用
  * `emitCHIRRTL` 代替：CHIRRTL 只做 Chisel elaboration，不展开也不做
  * 组合环检查，检测不出这类问题。
  */
class ysyx_26030103_VIPTDCacheElaborationTest extends AnyFlatSpec with Matchers {
  /** 标准 Sv39 + VIPT + alias 安全：9 位 index + 4 位块内偏移 = 13 > 12
    * 位页内偏移，ICache/DCache 都会生成物理行 alias 维护硬件。
    */
  private val VIPTMMU = ysyx_26030103_MMUProfile(
    enabled = true,
    xlen = 64,
    levels = 3,
    PTESizeBytes = 8,
    pageTableSizeBytes = 4096,
    PTEFormat = "riscv64",
    PTEConfig = ysyx_26030103_PTEConfig(PTEBits = 64, PPNHigh = 53),
    cacheMode = "vipt",
    cacheVIPTAliasSafe = true,
    cacheVIPTAliasScanMode = "parallel_n",
    cacheVIPTAliasScanWidth = 4,
    cacheVIPTAliasEventQueueDepth = 4
  )

  private val Config = ysyx_26030103_NPCConfig(
    XLEN = 64,
    AddressWidth = 64,
    ICacheEnable = true,
    ICacheIndexBits = 9,
    ICacheBlockSizeLog2 = 4,
    DCacheEnable = true,
    DCacheIndexBits = 9,
    DCacheBlockSizeLog2 = 4,
    // 只验证流水线/缓存/MMU 结构的 elaboration；关闭观测与 trace
    // 逻辑以缩短 firtool 运行时间，不影响 DMMU->LSU 这条路径。
    SDBEnable = false,
    DiffTestEnable = false,
    MTraceEnable = false,
    ITraceEnable = false,
    FTraceEnable = false,
    IRingBufEnable = false,
    DicArchCounterEnable = false,
    MMU = VIPTMMU
  )

  /** 全核 SystemVerilog 只生成一次：耗时较长，且本套件的两个用例都要用。 */
  private lazy val SystemVerilog: String =
    ChiselStage.emitSystemVerilog(new ysyx_26030103(Config))

  "VIPT + DCache 配置" should "落在 alias 页内偏移之外并真正生成 alias 维护硬件" in {
    Config.MMU.pageBits should be(12)
    (Config.ICacheIndexBits + Config.ICacheBlockSizeLog2) should be > 12
    (Config.DCacheIndexBits + Config.DCacheBlockSizeLog2) should be > 12
    Config.ICacheVIPTAliasHardware should be(true)
    Config.DCacheVIPTAliasHardware should be(true)
    Config.DCacheVIPTAliasEventQueueDepth should be(4)
  }

  "NPC top" should "在 VIPT + DCache + MMU 下无组合环地生成 SystemVerilog" in {
    // firtool 是这里的关键回归点：DMMU.memKill 若组合回灌
    // StageConnect.flush，emitSystemVerilog 会直接抛错。
    SystemVerilog should include("module ysyx_26030103")
  }
}
