package ysyx_26030103.cache
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
class ysyx_26030103_ICacheReg(
    private[cache] val Enable: Boolean = true, // false=不做缓存, 每次取指直接发单拍AXI读(和数据通路一样)
    private[cache] val BlockSizeLog2: Int = ysyx_26030103_KconfigDefaults.NpcICacheBlockSizeLog2,
    private[cache] val IndexBits: Int = ysyx_26030103_KconfigDefaults.NpcICacheIndexBits,
    private[cache] val AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    // Factory/LSU 会显式传入配置的 BusDataWidth；直接例化的测试请自行指定。
    private[cache] val DataWidth: Int = ysyx_26030103_KconfigDefaults.NpcInternalBusWidth,
    private[cache] val VIPT: Boolean = false,
    // index 含 VPN 位时，同一物理行可被不同 VA 映射到不同 set；refill
    // 安装前按物理 tag 失效其他 set 的副本，维持每个物理行至多一份。
    private[cache] val VIPTAliasSafe: Boolean = false,
    // alias 失效扫描方式：all_sets 单拍全扫描；serial 每周期扫描一个 set；
    // parallel_n 每周期扫描 N 个 set；directory 维护物理行目录。
    private[cache] val VIPTAliasScanMode: String = "all_sets",
    // parallel_n 的每周期扫描宽度；0=自动(min(4, sets))，超过 sets 时截断。
    private[cache] val VIPTAliasScanWidth: Int = 0,
    // directory 的目录项数；0=跟随 sets（完整无冲突），小于 sets 时按
    // set 低位分桶，桶冲突会失效被挤出的 Cache 行。
    private[cache] val VIPTAliasDirectoryEntries: Int = 0,
    // 自修改代码 store snoop：store B 完成时按物理地址失效对应 ICache 行。
    // 默认关闭（仅标准 fence.i），显式选择 HARDWARE_SNOOP 才生成比较逻辑。
    private[cache] val Snoop: Boolean = false,
    // DMA 地址级 snoop：DMA 写 B 到达时按字节范围失效所有重叠行。
    // 只有 DMA_COHERENCE=hardware_snoop 才生成。
    private[cache] val DMASnoop: Boolean = false,
    private[cache] val DrainTimeout: Int = ysyx_26030103_KconfigDefaults.ICacheDrainTimeout,
    private[cache] val PMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false)
) extends ysyx_26030103_ICacheBase(AddressWidth, DataWidth) with ICacheCore {
  require(DataWidth == 32 || DataWidth == 64,
    s"ICacheReg DataWidth 非法: $DataWidth")
  ysyx_26030103_VIPTAliasScan.requireMode(VIPTAliasScanMode, "ICacheReg")
  // Reg 存储：data 留在触发器阵列，命中拍组合读并锁存到 resp_data_reg。
  private lazy val data =
    Reg(Vec(if (Enable) NumberBlocks else 1, Vec(wordsPerBlock, UInt(32.W))))

  override protected def storageInit(): Unit = { val _ = data }
  protected def writeDataWord(
      blockIndex: UInt,
      wordOffset: UInt,
      word: UInt
  ): Unit = data(blockIndex)(wordOffset) := word
  protected def storageHitResponding: Bool = stage1Hit
  override protected def storageResponseData(): Unit = {
    // 命中用受理拍锁存的数据；refill 完成的可缓存行直接组合读阵列。
    io.ResponseData := Mux(
      accessFaultReg,
      "h00000013".U,
      Mux(
        stage1Hit,
        responseDataReg,
        Mux(cacheableReg, data(fetchIndexSafe)(fetchOffsetSafe), responseDataReg)
      )
    )
  }
  // 受理拍/PA 解析拍/排队缺失重查命中都把数据锁存到 resp_data_reg。
  override protected def storageOnAcceptPa(): Unit =
    responseDataReg := data(indexSafe)(blockOffset)
  override protected def storageOnResolve(): Unit =
    when(resolveRequestHit) {
      responseDataReg := data(fetchIndexSafe)(fetchOffsetSafe)
    }
  override protected def storageOnRecheck(): Unit =
    responseDataReg := data(fetchIndexSafe)(fetchOffsetSafe)
}
