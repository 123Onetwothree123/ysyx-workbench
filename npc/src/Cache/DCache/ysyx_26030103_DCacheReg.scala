package ysyx_26030103.cache

import chisel3._
import _root_.ysyx_26030103.common._

// 单请求直映射DCache（寄存器阵列存储）：组合读，作为参考实现。
// 公共逻辑见 ysyx_26030103_DCacheCore。
class ysyx_26030103_DCacheReg(
    protected val Enable: Boolean = true,
    protected val BlockSizeLog2: Int = ysyx_26030103_KconfigDefaults.NpcDCacheBlockSizeLog2,
    protected val IndexBits: Int = ysyx_26030103_KconfigDefaults.NpcDCacheIndexBits,
    protected val AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    protected val DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
    // SoC/Factory 会显式传入配置的 BusDataWidth；直接例化的测试请自行指定。
    protected val BusDataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
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
    protected val DrainTimeout: Int = ysyx_26030103_KconfigDefaults.DCacheDrainTimeout,
    protected val PMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false)
) extends ysyx_26030103_DCacheBase(AddressWidth, DataWidth, BusDataWidth)
    with ysyx_26030103_DCacheCore {
  protected def useSramStorage: Boolean = false
  protected def bypassResponse: Boolean = false
  protected def ownerName: String = "DCacheReg"
}
