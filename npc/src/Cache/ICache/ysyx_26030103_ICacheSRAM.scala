package ysyx_26030103.cache
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
class ysyx_26030103_ICacheSRAM(
    private[cache] val Enable: Boolean = true, // false=不做缓存, 每次取指直接发单拍AXI读(和数据通路一样)
    private[cache] val BlockSizeLog2: Int = ysyx_26030103_KconfigDefaults.NpcICacheBlockSizeLog2,
    private[cache] val IndexBits: Int = ysyx_26030103_KconfigDefaults.NpcICacheIndexBits,
    private[cache] val AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    // 直接例化默认 32 位 AXI 数据通路（与叶测试/回归的 32 位 beat 约定一致）；
    // Factory/LSU 会显式传入配置的 BusDataWidth。
    private[cache] val DataWidth: Int = 32,
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
    // true=命中响应的第一拍旁路SRAM输出(保持1拍延迟);
    // false=老实打一拍(响应晚一拍), 用于量化"不旁路"的性能代价
    private[cache] val Bypass: Boolean = true,
    private[cache] val DrainTimeout: Int = ysyx_26030103_KconfigDefaults.ICacheDrainTimeout,
    private[cache] val PMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false)
) extends ysyx_26030103_ICacheBase(AddressWidth, DataWidth) with ICacheCore {
  require(DataWidth == 32 || DataWidth == 64,
    s"ICacheSRAM DataWidth 非法: $DataWidth")
  ysyx_26030103_VIPTAliasScan.requireMode(VIPTAliasScanMode, "ICacheSRAM")
  // valid/tag 留在公共 core；data 用同步读 SRAM：第N拍给地址和读使能，
  // 第N+1拍数据有效。禁用模式下 SRAM 不会被访问。
  private lazy val dataMem = SyncReadMem(numBlocks * wordsPerBlock, UInt(32.W))
  // SRAM 数据晚一拍：命中响应的第一拍直接旁路 SRAM 输出，同时锁存到
  // resp_data_reg，之后被下游反压时用锁存值保证响应数据稳定。
  private lazy val responseDataHeld = RegInit(false.B)
  // VIPT 提前读：受理 VA 后立刻用 VA index 发 SRAM 读，数据下一拍锁存；
  // PA 晚到时直接使用已锁存的数据，不必再等一次 SRAM 读。
  private lazy val earlyReadPending = RegInit(false.B)

  override protected def storageInit(): Unit = {
    val _ = (dataMem, responseDataHeld, earlyReadPending)
  }
  // SRAM字地址：组索引与块内word偏移拼成一个线性地址。
  private def wordAddress(blockIndex: UInt, wordOffset: UInt): UInt =
    if (wordsPerBlock > 1) Cat(blockIndex, wordOffset) else blockIndex
  private def beatWordOf(beatData: UInt, wordOffset: UInt): UInt =
    if (wordsPerBeat == 1) beatData
    else Mux(wordOffset(0) === 1.U, beatData(63, 32), beatData(31, 0))

  protected def writeDataWord(
      blockIndex: UInt,
      wordOffset: UInt,
      word: UInt
  ): Unit = dataMem.write(wordAddress(blockIndex, wordOffset), word)
  protected def storageHitResponding: Bool =
    if (Bypass) stage1Hit else stage1Hit && responseDataHeld
  override protected def storageOnAccept(): Unit =
    earlyReadPending := VIPT.B && !incomingPhysicalAddressMatch
  override protected def storageOnRefillBeat(keywordBeat: Bool): Unit = {
    when(stage1Waits && refillCacheable && keywordBeat) {
      responseDataReg := beatWordOf(io.axi.R.RDATA, refillOffset)
    }
  }
  override protected def storageOnKill(): Unit = earlyReadPending := false.B
  override protected def storageOnFlush(): Unit = earlyReadPending := false.B
  override protected def storageDropStage1Hit(): Unit = {
    responseDataHeld := false.B
    earlyReadPending := false.B
  }
  override protected def storageRespond(): Unit = {
    // SRAM读口: 受理命中(含partial hit)、VIPT 的 PA 未到提前读，以及
    // "排队缺失重查命中"三处需要读数据。它们不会同拍发生，共用唯一读端口。
    val acceptRead =
      accept && (requestHit || (VIPT.B && !incomingPhysicalAddressMatch))
    val recheckRead =
      queuedMiss && refillState === refillIdle && !io.kill && !io.flush && recheckHit
    val sramReadEnable = acceptRead || recheckRead
    val sramReadAddress = Mux(
      acceptRead,
      wordAddress(indexSafe, blockOffset),
      wordAddress(fetchIndexSafe, fetchOffsetSafe)
    )
    val sramReadData = dataMem.read(sramReadAddress, sramReadEnable)
    // 普通命中在下一拍拿到 SRAM 数据；VIPT 提前读也在下一拍拿到数据，
    // 即使 PA 尚未到达也要先锁存，等 tag 判定通过后直接使用。
    val earlyDataArrives =
      VIPT.B && stage1Valid && earlyReadPending && !responseDataHeld
    val respDataArrives = stage1Valid && stage1Hit && !responseDataHeld
    val dataArrives = earlyDataArrives || respDataArrives
    val respDataFire = responding && io.resp_ready
    val respDataSelected = if (Bypass) {
      Mux(stage1Hit, Mux(respDataArrives, sramReadData, responseDataReg), responseDataReg)
    } else {
      responseDataReg
    }
    io.resp_data := Mux(accessFaultReg, "h00000013".U, respDataSelected)
    when(dataArrives) {
      responseDataReg := sramReadData
    }
    when(!stage1Valid || respDataFire) {
      responseDataHeld := false.B
      // 同拍受理新请求时不要清掉它刚设置的提前读标志。
      when(!accept) { earlyReadPending := false.B }
    }.elsewhen(dataArrives) {
      responseDataHeld := true.B
      earlyReadPending := false.B
    }
  }
}
