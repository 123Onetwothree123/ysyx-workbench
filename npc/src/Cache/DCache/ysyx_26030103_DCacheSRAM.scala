package ysyx_26030103.cache

import chisel3._
import _root_.ysyx_26030103.common._

// 单请求直映射DCache（同步读 SRAM 存储）：命中晚一拍，可用于综合配置。
// 公共逻辑见 ysyx_26030103_DCacheCore。
class ysyx_26030103_DCacheSRAM(
    protected val Enable: Boolean = true,
    protected val BlockSizeLog2: Int = ysyx_26030103_KconfigDefaults.NpcDCacheBlockSizeLog2,
    protected val IndexBits: Int = ysyx_26030103_KconfigDefaults.NpcDCacheIndexBits,
    protected val AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    protected val DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
    // 直接例化默认 32 位 AXI 数据通路（与叶测试/回归的 32 位 beat 约定一致）；
    // SoC/Factory 会显式传入配置的 BusDataWidth。
    protected val BusDataWidth: Int = 32,
    protected val VIPT: Boolean = false,
    // index 含 VPN 位时，同一物理行可被不同 VA 映射到不同 set；refill
    // 安装和写直达 store 完成时按物理 tag 失效其他 set 的副本。
    protected val VIPTAliasSafe: Boolean = false,
    // alias 失效扫描方式：all_sets 单拍全扫描；serial 每周期扫描一个 set；
    // parallel_n 每周期扫描 N 个 set；directory 维护物理行目录。
    protected val VIPTAliasScanMode: String = "all_sets",
    // DMA 地址级 snoop：只有 DMA_COHERENCE=hardware_snoop 才生成行失效
    // 与在途 refill 等待硬件，默认配置完全保留原有时序。
    protected val DMASnoop: Boolean = false,
    // parallel_n 的每周期扫描宽度；0=自动(min(4, sets))，超过 sets 时截断。
    protected val VIPTAliasScanWidth: Int = 0,
    // directory 的目录项数；0=跟随 sets（完整无冲突）。
    protected val VIPTAliasDirectoryEntries: Int = 0,
    // alias 事件队列深度；必须是 2 的幂。
    protected val VIPTAliasEventQueueDepth: Int =
      ysyx_26030103_KconfigDefaults.MMUCacheVIPTAliasEventQueueDepth,
    protected val CacheableBase: Long = 0L,
    protected val CacheableMask: Long = 0L,
    // true=命中响应第一拍旁路SRAM输出；false=老实打一拍（响应晚一拍）。
    protected val Bypass: Boolean = true,
    protected val DrainTimeout: Int = ysyx_26030103_KconfigDefaults.DCacheDrainTimeout,
    protected val PMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false)
) extends ysyx_26030103_DCacheBase(AddressWidth, DataWidth, BusDataWidth)
    with ysyx_26030103_DCacheCore {
  protected def useSramStorage: Boolean = true
  protected def bypassResponse: Boolean = Bypass
  protected def ownerName: String = "DCacheSRAM"
}
