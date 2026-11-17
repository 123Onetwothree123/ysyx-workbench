package ysyx_26030103.cache

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._

/** 单请求直映射 DCache 的公共流水线，读通道回填，写通道由 LSU 写缓冲负责。
  *
  * Reg 与 SRAM 共用 tag/alias/PMA/refill/AXI 逻辑；数据阵列的读写方式和
  * 命中响应级由 useSramStorage / bypassResponse 两个钩子隔离。
  * 两套存储各自的相对语句顺序、RegInit 初值和 last-connect 顺序保持不变。
  */
private[cache] trait ysyx_26030103_DCacheCore {
  this: ysyx_26030103_DCacheBase =>

  // 由实现类以 protected val 提供，保持构造参数名/默认值不变。
  protected def Enable: Boolean
  protected def BlockSizeLog2: Int
  protected def IndexBits: Int
  protected def AddressWidth: Int
  protected def DataWidth: Int
  protected def BusDataWidth: Int
  protected def VIPT: Boolean
  protected def VIPTAliasSafe: Boolean
  protected def VIPTAliasScanMode: String
  protected def DMASnoop: Boolean
  protected def VIPTAliasScanWidth: Int
  protected def VIPTAliasDirectoryEntries: Int
  protected def VIPTAliasEventQueueDepth: Int
  protected def CacheableBase: Long
  protected def CacheableMask: Long
  protected def DrainTimeout: Int
  protected def PMARegions: Seq[ysyx_26030103_PMARegion]

  // 存储/时序钩子：Reg 返回 false/false；SRAM 返回 true 与 Bypass 参数。
  protected def useSramStorage: Boolean
  protected def bypassResponse: Boolean
  protected def ownerName: String

  require(BusDataWidth == 32 || BusDataWidth == 64,
    s"$ownerName BusDataWidth 非法: $BusDataWidth")
  ysyx_26030103_VIPTAliasScan.requireMode(VIPTAliasScanMode, ownerName)
  private val aliasScanActive =
    VIPTAliasSafe && Enable &&
      ysyx_26030103_VIPTAliasScan.isScan(VIPTAliasScanMode)
  private val aliasAllSets =
    VIPTAliasSafe && Enable &&
      ysyx_26030103_VIPTAliasScan.isAllSets(VIPTAliasScanMode)
  private val aliasDirectoryMode =
    VIPTAliasSafe && Enable &&
      ysyx_26030103_VIPTAliasScan.isDirectory(VIPTAliasScanMode)
  require(DataWidth == 32 || DataWidth == 64,
    s"DCache DataWidth 非法: $DataWidth")
  require(BlockSizeLog2 >= 2, "DCache line must contain at least one 32-bit word")
  require(DataWidth == 32 || BlockSizeLog2 >= 3,
    "64 位 DCache 访问需要至少两个 32 位 word 的 cache line")
  require(IndexBits >= 1, "DCache must contain at least two sets")
  require(
    IndexBits + BlockSizeLog2 < AddressWidth,
    "DCache geometry must leave at least one tag bit"
  )
  // alias 模式必须比较完整物理行号：index 含 VPN 位时，PA 中与 index
  // 对应的位并不由 VA index 决定，窄 tag 会让两个只有该位不同的物理行
  // 在同一 set 中 false hit。
  private val tagBits =
    if (VIPTAliasSafe) AddressWidth - BlockSizeLog2
    else AddressWidth - IndexBits - BlockSizeLog2
  private def tagOf(address: UInt): UInt =
    if (VIPTAliasSafe) address(AddressWidth - 1, BlockSizeLog2)
    else address(AddressWidth - 1, IndexBits + BlockSizeLog2)
  private val numBlocks = 1 << IndexBits
  private val wordsPerBlock = 1 << (BlockSizeLog2 - 2)
  private val wordsPerBeat = BusDataWidth / 32
  private val beatsPerBlock = wordsPerBlock / wordsPerBeat
  private val busBytesLog2 = log2Ceil(BusDataWidth / 8)
  require(wordsPerBlock % wordsPerBeat == 0,
    s"DCache line 的 word 数($wordsPerBlock) 必须能被每拍 word 数($wordsPerBeat) 整除")
  private val indexWidth = IndexBits.max(1)
  private val offsetWidth = (BlockSizeLog2 - 2).max(1)
  private val countWidth = log2Ceil(wordsPerBlock).max(1)
  private val scanWidth =
    ysyx_26030103_VIPTAliasScan.resolveScanWidth(
      VIPTAliasScanMode,
      VIPTAliasScanWidth,
      numBlocks
    )
  private val directoryEntries =
    if (VIPTAliasDirectoryEntries <= 0) numBlocks
    else {
      require(VIPTAliasDirectoryEntries <= numBlocks,
        s"VIPT alias 目录项数($VIPTAliasDirectoryEntries) 不能超过 set 数($numBlocks)")
      VIPTAliasDirectoryEntries
    }
  private val arrayBlocks = if (Enable) numBlocks else 1
  // 8 字节访问要求低/高两个相邻 word 同处一行；LD/SD 按 ISA 8 字节对齐，
  // 块内 word 偏移总是偶数，nextOffset 不会越过行尾。
  private def nextOffset(offset: UInt): UInt =
    (offset + 1.U)(countWidth - 1, 0)
  private val readPortCount = if (DataWidth == 64) 2 else 1
  private val refillWritePortCount = wordsPerBeat
  private val storeWriteBase = refillWritePortCount
  private val storeWritePortCount = if (DataWidth == 64) 2 else 1
  private val writePortCount = refillWritePortCount + storeWritePortCount
  // 一致性 flush 是 DCache 自身的可验证接口；生产顶层已由 DMA 写 B
  // 握手的失效脉冲驱动（ysyx_26030103.scala），dontTouch 保证该端口在
  // 叶模块 elaboration 中不被裁掉。
  dontTouch(io.flush)
  val valid = RegInit(VecInit(Seq.fill(arrayBlocks)(false.B)))
  val tag = Reg(Vec(arrayBlocks, UInt(tagBits.W)))
  // Reg 介质的数据阵列；SRAM 实现不生成该寄存器堆。
  private val data: Option[Vec[Vec[UInt]]] =
    if (useSramStorage) None
    else Some(Reg(Vec(arrayBlocks, Vec(wordsPerBlock, UInt(32.W)))))
  private def readDataWord(blockIndex: UInt, wordOffset: UInt): UInt =
    data.get(blockIndex)(wordOffset)
  // 仅 all_sets 在 refill 安装/store 完成时展开全组 tag 比较；
  // serial/parallel 由扫描引擎完成，directory 由目录完成。
  private def invalidateAliasCopies(aliasTag: UInt, targetIndex: UInt): Unit = {
    if (aliasAllSets) {
      for (i <- valid.indices) {
        when(valid(i) && tag(i) === aliasTag && i.U =/= targetIndex) {
          valid(i) := false.B
        }
      }
    }
  }
  private val dmaSnoopActive = DMASnoop && Enable
  // 每个 set 的完整物理行号。alias-safe 的 tag 已含物理行号；普通几何
  // 需要把 tag 与 set index 拼回去，才能对任意 burst 范围做行号比较。
  private def dmaLineOfSet(set: Int): UInt =
    if (VIPTAliasSafe) tag(set)
    else Cat(tag(set), set.U(IndexBits.W))
  private val dmaStartLine = io.dma_snoop_start(AddressWidth - 1, BlockSizeLog2)
  private val dmaEndLine = io.dma_snoop_end(AddressWidth - 1, BlockSizeLog2)
  private def dmaLineInRange(line: UInt): Bool =
    line >= dmaStartLine && line <= dmaEndLine
  private def dmaSnoopHitVec: UInt =
    if (!dmaSnoopActive) 0.U(arrayBlocks.W)
    else
      VecInit(valid.indices.map { set =>
        valid(set) && dmaLineInRange(dmaLineOfSet(set))
      }).asUInt
  private val aliasDirectory =
    if (aliasDirectoryMode) {
      Some(
        Module(
          new ysyx_26030103_VIPTAliasDirectory(
            LineBits = AddressWidth - BlockSizeLog2,
            SetBits = IndexBits,
            NumSets = numBlocks,
            Entries = directoryEntries
          )
        )
      )
    } else {
      None
    }
  // 同步读 SRAM：读口在受理拍给地址，下一拍数据有效。
  private val sram: Option[SRAMInterface[Vec[UInt]]] =
    if (useSramStorage)
      Some(
        SRAM.masked(
          numBlocks * wordsPerBlock,
          Vec(4, UInt(8.W)),
          readPortCount,
          writePortCount,
          0
        )
      )
    else None
  private def sramPorts: SRAMInterface[Vec[UInt]] = sram.get
  // SRAM 字地址：组索引与块内 word 偏移拼成线性地址。
  private def wordAddress(blockIndex: UInt, wordOffset: UInt): UInt =
    if (wordsPerBlock > 1) Cat(blockIndex, wordOffset) else blockIndex
  val indexAddress = if (VIPT) io.req.bits.vaddr else io.req.bits.addr
  val index = indexAddress(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  val indexSafe = if (Enable) index else 0.U(indexWidth.W)
  val wordOffset =
    if (wordsPerBlock > 1) indexAddress(BlockSizeLog2 - 1, 2)
    else 0.U(offsetWidth.W)
  val requestTag = tagOf(io.req.bits.addr)
  // VIPT：req 只保证 VA 有效，PA 与它的 VA 标签都匹配后才能做 tag/PMA 判定。
  // PIPT 下这个条件恒为真，原有路径完全不变。
  val incomingPhysicalAddressMatch =
    !VIPT.B || (io.req_paddr_valid && io.req_paddr_vaddr === io.req.bits.vaddr)
  val cacheable = incomingPhysicalAddressMatch && ysyx_26030103_DCachePolicy.IsCacheable(
    io.req.bits.addr,
    Enable,
    AddressWidth,
    BlockSizeLog2,
    CacheableBase,
    CacheableMask,
    PMARegions
  )
  io.req_cacheable := cacheable
  // DMA snoop 与 CPU 请求同拍时，失效必须优先于命中：否则本拍会锁存
  // DMA 之前的旧数据，而该 B 恰好在本拍完成后对软件可见。
  private val dmaRequestLineHit = dmaSnoopActive.B && io.dma_snoop_valid &&
    dmaLineInRange(io.req.bits.addr(AddressWidth - 1, BlockSizeLog2))
  val hit =
    cacheable && valid(indexSafe) && tag(indexSafe) === requestTag &&
      !dmaRequestLineHit // 这个直接拿来做一个命中条件
  // VIPT 等待 PA 的请求状态：VA 已经选好 index/offset 并锁存，PA 未到前
  // 不判定命中、不发 AR。等待期间没有其他请求能改写阵列。
  val waitPhysicalAddress = RegInit(false.B)
  val waitVirtualAddressReg = Reg(UInt(AddressWidth.W))
  val waitIndexReg = Reg(UInt(indexWidth.W))
  val waitOffsetReg = Reg(UInt(countWidth.W))
  val waitWidthReg = Reg(UInt(2.W))
  val waitSignedReg = Reg(Bool())
  val waitDataReg = Reg(UInt(32.W))
  val waitDataHiReg = Reg(UInt(32.W))
  // SRAM 读口数据下一拍才有效，VIPT 早读需要额外的就绪/有效跟踪。
  private val waitDataValid = if (useSramStorage) Some(RegInit(false.B)) else None
  private val waitSramRead = if (useSramStorage) Some(RegInit(false.B)) else None
  private def waitDataValidValue: Bool = waitDataValid.get
  private def waitSramReadValue: Bool = waitSramRead.get
  val viptNeedWait = VIPT.B && !incomingPhysicalAddressMatch
  // PA 带受理时的 VA 标签返回：只接受属于这笔等待请求的翻译结果。
  // flush/kill 同拍时优先放弃本次 resolve，避免把已失效行或故障请求的数据
  // 送上响应通道。
  val resolveVirtualAddressMatch = !io.flush && !io.req_kill && waitPhysicalAddress &&
    io.req_paddr_valid && io.req_paddr_vaddr === waitVirtualAddressReg
  val resolvePhysicalAddress = io.req.bits.addr
  val resolveTag = tagOf(resolvePhysicalAddress)
  val resolveIndexSafe = if (Enable) waitIndexReg else 0.U(indexWidth.W)
  val resolveCacheable = ysyx_26030103_DCachePolicy.IsCacheable(
    resolvePhysicalAddress,
    Enable,
    AddressWidth,
    BlockSizeLog2,
    CacheableBase,
    CacheableMask,
    PMARegions
  )
  private val dmaResolveLineHit = dmaSnoopActive.B && io.dma_snoop_valid &&
    dmaLineInRange(resolvePhysicalAddress(AddressWidth - 1, BlockSizeLog2))
  // SRAM 的早读数据晚一拍到达，命中判定要等数据就绪；Reg 恒就绪。
  val resolveReady: Bool =
    if (useSramStorage) resolveVirtualAddressMatch && (waitDataValidValue || waitSramReadValue)
    else resolveVirtualAddressMatch
  val resolveHit = resolveReady && resolveCacheable &&
    valid(resolveIndexSafe) && tag(resolveIndexSafe) === resolveTag &&
    !dmaResolveLineHit
  val requestAddressReg = Reg(UInt(AddressWidth.W))
  val requestWidthReg = Reg(UInt(2.W))
  val requestSignedReg = Reg(Bool())
  val requestIndexReg = Reg(UInt(indexWidth.W))
  val requestTagReg = Reg(UInt(tagBits.W))
  val requestOffsetReg = Reg(UInt(countWidth.W))
  val refillBaseReg = Reg(UInt(AddressWidth.W))
  val refillCount = RegInit(0.U(countWidth.W))
  val refillActive = RegInit(false.B)
  // 单MSHR回填期间逐字记录已经成功返回的数据。缓存行在最后一拍以前仍然
  // 保持invalid，但这些字可以安全地服务同一行的后续load；未置位的字绝不
  // 能读取data阵列，因为其中可能还是旧行或尚未初始化的内容。
  val refillWordValid = RegInit(0.U(wordsPerBlock.W))
  private def refillBeatWordIndex(lane: Int): UInt =
    if (wordsPerBlock > 1) (refillCount + lane.U)(countWidth - 1, 0)
    else 0.U(offsetWidth.W)
  private def refillBeatWordMask: UInt =
    (0 until wordsPerBeat)
      .map(lane => UIntToOH(refillBeatWordIndex(lane), wordsPerBlock))
      .reduce(_ | _)
  private def wordInRefillBeat(word: UInt): Bool =
    if (wordsPerBeat == 1) {
      word === refillCount
    } else {
      word >= refillCount && word < (refillCount + wordsPerBeat.U)
    }
  private def refillBeatWord(word: UInt, data: UInt): UInt =
    if (wordsPerBeat == 1) {
      data(31, 0)
    } else {
      Mux(word(0), data(63, 32), data(31, 0))
    }
  // 正常回填使用一笔 INCR burst。仅当不符合请求长度的从机提前给出
  val refillBurst = RegInit(false.B)
  val refillError = RegInit(false.B)
  // 记录本次 refill 第一次错误 beat 的 RRESP，供最后统一产生 fault 时
  // 保留 SLVERR/DECERR 编码。
  val refillErrorResponse = RegInit(0.U(2.W))
  val responseData = RegInit(0.U(DataWidth.W))
  val wideLowReg = RegInit(0.U(32.W))
  val wideBeat = RegInit(0.U(1.W))
  val wideLowFault = RegInit(false.B)
  val responseFault = RegInit(false.B)
  val responseFaultResponse = RegInit(0.U(2.W))
  val responseValid = RegInit(false.B)
  val demandResponseDone = RegInit(false.B)
  // 当前响应对应的完整物理访问。命中/早期响应可能来自同拍受理的另一
  // 请求，不能只靠 ReqAddrReg 推断；DMA snoop 用它判断响应归属并按原
  // 访问重放。
  val responseAddressReg = Reg(UInt(AddressWidth.W))
  val responseWidthReg = Reg(UInt(2.W))
  val responseSignedReg = Reg(Bool())
  val responseCacheableReg = RegInit(false.B)
  val dmaReplayWait = RegInit(false.B)
  val dmaReplayAddress = Reg(UInt(AddressWidth.W))
  val dmaReplayWidth = Reg(UInt(2.W))
  val dmaReplaySigned = Reg(Bool())
  val dmaReplayCacheable = RegInit(false.B)
  // SRAM 命中数据晚一拍：本拍发起读，下一拍由 SramRespPending 旁路到响应
  // 并同时锁存。SramRespPending 与 ResponseValid 互斥。
  private val sramResponsePending = if (useSramStorage) Some(RegInit(false.B)) else None
  private val sramRequestAddress =
    if (useSramStorage) Some(Reg(UInt(AddressWidth.W))) else None
  private val sramRequestWidth = if (useSramStorage) Some(Reg(UInt(2.W))) else None
  private val sramRequestSigned = if (useSramStorage) Some(Reg(Bool())) else None
  private def sramResponsePendingValue: Bool = sramResponsePending.get
  private def sramRequestAddressValue: UInt = sramRequestAddress.get
  private def sramRequestWidthValue: UInt = sramRequestWidth.get
  private def sramRequestSignedValue: Bool = sramRequestSigned.get
  // Reg 的响应槽就是 ResponseValid；SRAM 还要算上仍在读口的那一拍。
  val responseOccupied: Bool =
    if (useSramStorage) responseValid || sramResponsePendingValue else responseValid
  val states = Enum(5)
  val stateIdle = states(0)
  val stateReadRequest = states(1)
  val stateReadResponse = states(2)
  val stateDrain = states(3)
  val stateDrainFault = states(4)
  val state = RegInit(stateIdle)
  // AXI 从设备违规不给 RLAST 时，SDrain 不能永久等待。超时后先为尚未
  // 交付的 demand 产生 fault 响应，再进入 SDrainFault 继续吸收迟到 beat；
  // 总线安静足够久后自动回到 Idle，使 DCache 可以继续服务。
  val drainTimer = RegInit(0.U(10.W))
  val drainRetryTimer = RegInit(0.U(10.W))
  when(state =/= stateDrain) { drainTimer := 0.U }
  when(state =/= stateDrainFault) { drainRetryTimer := 0.U }
  // flush会使缓存状态失效，但不能取消已经接受的需求请求：LSU没有取消握手，
  // 并且正在等待恰好一个响应。suppressFill在维持在途请求的同时，防止跨越
  // 本次flush的缓存行重新变为有效。
  val suppressFill = RegInit(false.B)
  val requestIndexSafe = if (Enable) requestIndexReg else 0.U(indexWidth.W)
  if (useSramStorage) {
    for (lane <- 0 until wordsPerBeat) {
      sramPorts.writePorts(lane).address :=
        wordAddress(requestIndexSafe, refillBeatWordIndex(lane))
      sramPorts.writePorts(lane).data := VecInit(
        io.AXI.R.RDATA(32 * lane + 7, 32 * lane),
        io.AXI.R.RDATA(32 * lane + 15, 32 * lane + 8),
        io.AXI.R.RDATA(32 * lane + 23, 32 * lane + 16),
        io.AXI.R.RDATA(32 * lane + 31, 32 * lane + 24)
      )
      sramPorts.writePorts(lane).mask.get := VecInit(true.B, true.B, true.B, true.B)
      sramPorts.writePorts(lane).enable := false.B
    }
  }
  // DMA snoop 握手：匹配行还有在途 R beat 或尚未被 CPU 取走的旧响应时，
  // ready 保持为低，顶层据此继续扣住 DMA B。SReadReq 阶段 AR 尚未被
  // 接受，此时读到的已经是 DMA 完成后的内存，不需要等待。
  // 若 refill 在本拍完成，末尾的失效写回优先级更高，会把同拍安装的
  // valid 压回 0。
  private val dmaSnoopResponseValid: Bool =
    if (useSramStorage) responseValid || sramResponsePendingValue else responseValid
  private val dmaSnoopResponseAddress: UInt =
    if (useSramStorage) Mux(sramResponsePendingValue, sramRequestAddressValue, responseAddressReg)
    else responseAddressReg
  private val dmaSnoopRefillLineHit = dmaSnoopActive.B && refillActive &&
    (state === stateReadResponse || state === stateDrain) &&
    dmaLineInRange(refillBaseReg(AddressWidth - 1, BlockSizeLog2))
  private val dmaSnoopResponseMatch = dmaSnoopActive.B && dmaSnoopResponseValid &&
    dmaLineInRange(dmaSnoopResponseAddress(AddressWidth - 1, BlockSizeLog2))
  private val dmaSnoopResponseReplay = dmaSnoopResponseMatch &&
    responseCacheableReg && !demandResponseDone && !responseFault
  private val dmaSnoopRefillOwnDemand = dmaSnoopRefillLineHit &&
    responseCacheableReg &&
    dmaSnoopResponseAddress(AddressWidth - 1, BlockSizeLog2) ===
      refillBaseReg(AddressWidth - 1, BlockSizeLog2)
  // 故障响应不能被“重新读取”覆盖：DMA 重放只适用于成功的数据响应。
  // 否则一次成功 replay 会把原始 fault 吞掉，LSU 将错误地写回数据。
  private val dmaSnoopReplay = dmaSnoopActive.B && !demandResponseDone &&
    !responseFault && (dmaSnoopResponseReplay || dmaSnoopRefillOwnDemand)
  private val dmaSnoopWillReplay =
    dmaSnoopActive.B && io.dma_snoop_valid && dmaSnoopReplay
  private val dmaSnoopResponseBusy = dmaSnoopActive.B && dmaSnoopResponseMatch &&
    !dmaSnoopResponseReplay && !io.resp.fire
  private val dmaSnoopVIPTWaitBusy = dmaSnoopActive.B && VIPT.B && waitPhysicalAddress
  io.dma_snoop_ready := (!dmaSnoopActive).B ||
    (!dmaSnoopRefillLineHit && !dmaSnoopResponseBusy &&
      !dmaSnoopWillReplay && !dmaReplayWait && !dmaSnoopVIPTWaitBusy)
  // 将最多两个 32 位 word 格式化为架构 load 结果。
  // width=0/1/2 分别是 LB/LH/LW，按 DataWidth 做符号/零扩展；
  // width=3 只在 64 位 DCache 下出现，直接拼接高/低 word。
  def formatLoad(
      wordLow: UInt,
      wordHigh: UInt,
      address: UInt,
      width: UInt,
      signed: Bool
  ): UInt = {
    val word = wordLow(31, 0)
    val rawWide = Cat(wordHigh(31, 0), word)
    val byteData = MuxLookup(address(1, 0), word(7, 0))(
      Seq(
        1.U -> word(15, 8),
        2.U -> word(23, 16),
        3.U -> word(31, 24)
      )
    )
    val halfData = Mux(address(1), word(31, 16), word(15, 0))
    def extend(value: UInt, bits: Int): UInt =
      if (DataWidth == bits) value
      else Mux(
        signed,
        Cat(Fill(DataWidth - bits, value(bits - 1)), value),
        Cat(0.U((DataWidth - bits).W), value)
      )
    val byteResult = extend(byteData, 8)
    val halfResult = extend(halfData, 16)
    val wordResult = extend(word, 32)
    if (DataWidth == 32) {
      MuxLookup(width, wordResult)(
        Seq(0.U -> byteResult, 1.U -> halfResult, 2.U -> wordResult)
      )
    } else {
      MuxLookup(width, wordResult)(
        Seq(
          0.U -> byteResult,
          1.U -> halfResult,
          2.U -> wordResult,
          3.U -> rawWide
        )
      )
    }
  }
  def storeMaskWord(strobe: UInt): UInt = Cat(
    Mux(strobe(3), "hff".U(8.W), 0.U(8.W)),
    Mux(strobe(2), "hff".U(8.W), 0.U(8.W)),
    Mux(strobe(1), "hff".U(8.W), 0.U(8.W)),
    Mux(strobe(0), "hff".U(8.W), 0.U(8.W))
  )
  io.AXI.AW.AWVALID := false.B // 写通道由LSU负责
  io.AXI.AW.AWID := 0.U
  io.AXI.AW.AWADDR := 0.U
  io.AXI.AW.AWLEN := 0.U
  io.AXI.AW.AWSIZE := 2.U
  io.AXI.AW.AWBURST := 1.U
  io.AXI.AW.AWPROT := 0.U
  io.AXI.AW.AWCACHE := 0.U
  io.AXI.AW.AWQOS := 0.U
  io.AXI.W.WVALID := false.B
  io.AXI.W.WDATA := 0.U
  io.AXI.W.WSTRB := 0.U
  io.AXI.W.WLAST := false.B
  io.AXI.B.BREADY := false.B
  io.AXI.AR.ARVALID := false.B
  io.AXI.AR.ARID := 0.U
  io.AXI.AR.ARADDR := 0.U
  io.AXI.AR.ARLEN := 0.U
  io.AXI.AR.ARSIZE := 2.U
  io.AXI.AR.ARBURST := 1.U
  io.AXI.AR.ARPROT := 0.U
  io.AXI.AR.ARCACHE := 0.U
  io.AXI.AR.ARQOS := 0.U
  io.AXI.R.RREADY := false.B
  // 响应槽：消费者本拍取走旧响应时，新响应可立即复用该槽。
  // SRAM 非旁路模式要等数据锁存后（晚两拍）才可见，槽位只能在真实
  // fire 时释放，否则会被超额订阅并静默丢响应。
  val responseSlotReady =
    if (useSramStorage)
      !responseOccupied || (if (bypassResponse) io.resp.ready else io.resp.fire)
    else !responseValid || io.resp.ready
  if (useSramStorage && !bypassResponse) {
    assert(
      !(sramResponsePendingValue && io.resp.fire),
      "DCache non-bypass response slot over-subscribed"
    )
  }
  // 响应状态与回填状态解耦，使关键字能在缓存行其余数据到达前重启流水线。
  // 被 DMA snoop 命中的旧响应不能交付：本拍压掉 valid，下一拍重新发起
  // 读取（此时内存已包含 DMA 新数据）。
  private val dmaDropResponse = dmaSnoopActive.B && io.dma_snoop_valid &&
    dmaSnoopResponseReplay
  private val respVisible: Bool =
    if (useSramStorage && bypassResponse) responseOccupied else responseValid
  io.resp.valid := respVisible && !dmaDropResponse && !dmaReplayWait
  if (!useSramStorage) { io.resp.bits.data := responseData }
  io.resp.bits.fault := responseFault
  io.resp.bits.FaultResponse := responseFaultResponse
  io.axi_active := state === stateReadRequest || state === stateReadResponse ||
    state === stateDrain || state === stateDrainFault
  io.perf_refill_req := refillActive && state === stateReadRequest
  io.perf_refill_resp := refillActive && state === stateReadResponse
  val perfLineFill = WireDefault(false.B)
  io.perf_line_fill := perfLineFill
  val storeIndexAddress = if (VIPT) io.StoreVAddress else io.StoreAddress
  val storeIndex = storeIndexAddress(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  val storeIndexSafe = if (Enable) storeIndex else 0.U(indexWidth.W)
  val storeOffset =
    if (wordsPerBlock > 1) storeIndexAddress(BlockSizeLog2 - 1, 2)
    else 0.U(offsetWidth.W)
  val storeTag = tagOf(io.StoreAddress)
  if (useSramStorage) {
    // 掩码直接接字节使能, 与读改写等价。
    sramPorts.writePorts(storeWriteBase).address :=
      wordAddress(storeIndexSafe, storeOffset)
    sramPorts.writePorts(storeWriteBase).data := VecInit(
      io.StoreData(7, 0),
      io.StoreData(15, 8),
      io.StoreData(23, 16),
      io.StoreData(31, 24)
    )
    sramPorts.writePorts(storeWriteBase).mask.get := VecInit(
      io.StoreStrobe(0),
      io.StoreStrobe(1),
      io.StoreStrobe(2),
      io.StoreStrobe(3)
    )
    sramPorts.writePorts(storeWriteBase).enable := false.B
    if (DataWidth == 64) {
      // 8 字节 store 的高 word 使用独立写口，避免与低 word 争用同一
      // SRAM 地址端口。
      sramPorts.writePorts(storeWriteBase + 1).address :=
        wordAddress(storeIndexSafe, nextOffset(storeOffset))
      sramPorts.writePorts(storeWriteBase + 1).data := VecInit(
        io.StoreData(39, 32),
        io.StoreData(47, 40),
        io.StoreData(55, 48),
        io.StoreData(63, 56)
      )
      sramPorts.writePorts(storeWriteBase + 1).mask.get := VecInit(
        io.StoreStrobe(4),
        io.StoreStrobe(5),
        io.StoreStrobe(6),
        io.StoreStrobe(7)
      )
      sramPorts.writePorts(storeWriteBase + 1).enable := false.B
    }
  }
  val storeCacheable = ysyx_26030103_DCachePolicy.IsCacheable(
    io.StoreAddress,
    Enable,
    AddressWidth,
    BlockSizeLog2,
    CacheableBase,
    CacheableMask,
    PMARegions
  )
  io.perf_writeback := io.StoreValid && storeCacheable
  val storeHit = storeCacheable &&
    valid(storeIndexSafe) && tag(storeIndexSafe) === storeTag
  val storeConflictsRefill = io.StoreValid && refillActive && storeCacheable &&
    io.StoreAddress(AddressWidth - 1, BlockSizeLog2) ===
      refillBaseReg(AddressWidth - 1, BlockSizeLog2)

  // Early restart之后，尾部回填仍独占AXI读ID，但其他set的完整hit以及本次
  // 回填中已经成功返回的字都不需要第二个MSHR。旧需求响应必须已经被消费
  // （或本拍正在消费），从而保持无tag响应接口的严格请求顺序。未返回字、
  // refill所在set的异tag请求和任何第二次miss仍反压到单MSHR释放。
  val sameRefillLine = cacheable && refillActive &&
    io.req.bits.addr(AddressWidth - 1, BlockSizeLog2) ===
      refillBaseReg(AddressWidth - 1, BlockSizeLog2)
  val wideWordReady =
    if (DataWidth == 64) VecInit(refillWordValid.asBools)(nextOffset(wordOffset))
    else true.B
  val refillWordReady = VecInit(refillWordValid.asBools)(wordOffset) &&
    (!(io.req.bits.WidthSelect === 3.U) || wideWordReady)
  val priorDemandLeaves = demandResponseDone || io.resp.fire
  val refillInProgress = state === stateReadRequest || state === stateReadResponse
  val lastRefillBeat =
    refillCount === ((beatsPerBlock - 1) * wordsPerBeat).U
  // RREADY在SReadResp恒为高，因此RVALID即表示本拍会消费该beat。寄存的
  // RefillError要到下一拍才生效；将本拍RRESP错误和“必须为单拍末尾却缺
  // RLAST”的协议错误直接旁路进ready，避免错误被采样的同拍仍接受partial hit。
  val refillBeatArrives = state === stateReadResponse && refillActive && io.AXI.R.RVALID
  val requiredLastMissing = refillBeatArrives && !io.AXI.R.RLAST &&
    (lastRefillBeat || !refillBurst)
  val refillFaultNow =
    (refillBeatArrives && io.AXI.R.RRESP =/= 0.U) || requiredLastMissing
  // 同拍成功store会在时钟沿才写入cache阵列；若此时接受同一行的load，响应
  // 会锁存store之前的旧值，因此保守地将该load反压一拍。
  val storeConflictsRequest = io.StoreValid && storeCacheable && cacheable &&
    io.StoreAddress(AddressWidth - 1, BlockSizeLog2) ===
      io.req.bits.addr(AddressWidth - 1, BlockSizeLog2)
  // 半填行只在“同一 set”内可见：alias 几何下另一个 VA 可能用同一 PA
  // 命中不同 index，而 refill 只把数据写进 ReqIndexSafe。
  val partialHitUnderRefill = refillInProgress && sameRefillLine &&
    (index === requestIndexSafe) &&
    refillWordReady && !refillError && !suppressFill &&
    !storeConflictsRefill && !refillFaultNow
  val fullHitUnderRefill = refillInProgress && refillActive && hit
  val hitUnderRefill = priorDemandLeaves && !io.flush &&
    !storeConflictsRequest && (fullHitUnderRefill || partialHitUnderRefill)
  val acceptedHit = Mux(state === stateIdle, hit, hitUnderRefill)
  // serial/parallel alias 扫描的事件源：
  //   refill：cacheable AR 被接受，安装新行时清掉其它 set 的旧副本；
  //   store ：写直达完成但按 VA index 未命中，可能有另一 set 的旧副本。
  // store 命中时该行已是唯一副本，直接原地更新即可，不需要扫描。
  val refillAliasEvent = state === stateReadRequest && io.AXI.AR.ARREADY &&
    refillActive && !suppressFill && !io.flush
  val storeAliasEvent = io.StoreValid && storeCacheable && !storeHit
  // 扫描/事件排队期间反压新 load，保证不会命中尚未清理的 alias 副本。
  val aliasScanStatus = ysyx_26030103_VIPTAliasScan.dcacheScan(
    aliasScanActive,
    valid,
    tag,
    refillAliasEvent,
    requestTagReg,
    requestIndexSafe,
    storeAliasEvent,
    storeTag,
    io.flush,
    scanWidth,
    VIPTAliasEventQueueDepth
  )
  val aliasScanBusy = aliasScanStatus.busy
  // directory 模式在 AR 握手拍更新目录；同拍不受理新 load，避免组合
  // 命中尚未失效的旧 alias 副本。store 拍已有 StoreConflictsRequest 拦
  // 同一物理行的 load，目录失效在同一时钟沿完成。
  val aliasDirectoryInstallBusy =
    if (aliasDirectoryMode) refillAliasEvent else false.B
  // 扫描模式把 refill 新行的 valid 推迟到本轮 refill 事件扫描完成。
  // 队列里可能先排着更早的 store 事件，因此用 refillCleared 脉冲 +
  // 一个 sticky 位记住“当前 refill 的旧副本已经清完”。
  // 只有扫描模式才生成 refill 事件跟踪与延迟安装状态；all_sets/
  // directory 不增加额外的 valid 写口。
  private val aliasScanState =
    if (aliasScanActive) {
      Some(
        (
          RegInit(false.B),
          Reg(UInt(indexWidth.W)),
          Reg(UInt(tagBits.W)),
          RegInit(false.B)
        )
      )
    } else {
      None
    }
  val aliasInstallAllowed: Bool =
    if (!aliasScanActive) {
      true.B
    } else {
      val (refillCleared, deferredIndex, deferredTag, deferredValid) =
        aliasScanState.get
      when(refillAliasEvent) {
        refillCleared := false.B
      }
      when(aliasScanStatus.refillCleared) {
        refillCleared := true.B
      }
      // 队列溢出会丢弃 store 事件；在飞 refill 的数据可能早于这些
      // store，必须抑制本次安装并清掉挂起行。
      when(aliasScanStatus.overflow) {
        refillCleared := false.B
        deferredValid := false.B
        suppressFill := true.B
        refillWordValid := 0.U
        valid(requestIndexSafe) := false.B
      }
      // store 已把新值写到内存；挂起的新行若与 store 同一物理行，其
      // refill 数据可能早于 store，同一拍也不能补装。
      val storeHitsDeferred =
        io.StoreValid && storeCacheable && deferredValid &&
          storeTag === deferredTag
      when(
        deferredValid && (refillCleared || aliasScanStatus.refillCleared) &&
          !aliasScanStatus.overflow && !storeHitsDeferred &&
          !suppressFill && !io.flush
      ) {
        valid(deferredIndex) := true.B
        deferredValid := false.B
      }
      when(storeHitsDeferred) {
        deferredValid := false.B
      }
      (refillCleared || aliasScanStatus.refillCleared) &&
        !aliasScanStatus.overflow && !storeHitsDeferred
    }
  aliasDirectory.foreach { dir =>
    dir.io.install := refillAliasEvent
    dir.io.installLine := requestTagReg
    dir.io.installSet := requestIndexSafe
    // store 无论命中与否都查目录：命中保留目标 set 并原地更新镜像，
    // 未命中/失败 store 失效其它 set 的旧副本。
    dir.io.invalidateLine := io.StoreValid && storeCacheable
    dir.io.invalidateLineAddress := storeTag
    dir.io.keepSet := storeHit && io.StoreResponse === 0.U
    dir.io.keepSetIndex := storeIndexSafe
    dir.io.invalidateLine2 := false.B
    dir.io.invalidateLine2Address := 0.U
    // DMA 地址级 snoop 按物理行范围清目录项；Cache valid 由下面的
    // DmaSnoopHitVec/refill 抑制路径同步清除。
    dir.io.invalidateRange := dmaSnoopActive.B && io.dma_snoop_valid
    dir.io.invalidateRangeStart := dmaStartLine
    dir.io.invalidateRangeEnd := dmaEndLine
    // refill/store 自身清掉某个 set 时，目录必须同步释放该 set：
    // refill 错误、drain 超时、store 与 refill 冲突、失败 store 都
    // 可能让 Cache 在没有经过目录失效接口的情况下清掉有效行。
    dir.io.release := refillFaultNow ||
      (state === stateDrain && drainTimer === DrainTimeout.U) ||
      storeConflictsRefill ||
      (io.StoreValid && storeCacheable && storeHit && io.StoreResponse =/= 0.U)
    dir.io.releaseSet := Mux(
      refillFaultNow || (state === stateDrain && drainTimer === DrainTimeout.U),
      requestIndexSafe,
      storeIndexSafe
    )
    dir.io.clear := io.flush
  }
  // 同拍命中 store 会在此沿才写入阵列/镜像：SIdle 命中路径也必须反压
  // 同线 load 一拍，否则 Reg 实现会锁存 store 之前的旧值（SRAM 实现因
  // 读口打拍而恰好看到新值），两实现语义不一致。
  // VIPT 缺少 PA 时只允许在空闲且无回填时受理；等待期间不再接新请求。
  val viptAcceptable = !viptNeedWait || (state === stateIdle && !refillActive)
  io.req.ready := responseSlotReady && !io.flush && !io.req_kill &&
    !(dmaSnoopActive.B && io.dma_snoop_valid) && !dmaReplayWait &&
    !waitPhysicalAddress && viptAcceptable && !aliasScanBusy &&
    !aliasDirectoryInstallBusy &&
    (state === stateIdle || (hitUnderRefill && !viptNeedWait)) &&
    !storeConflictsRequest
  // SRAM 读口: SIdle 命中与 refill 中 partial hit 都满足 req.fire && AcceptedHit，
  // 都读当前请求地址的字；VIPT 缺 PA 时也用 VA index 提前发起读。
  private val sramReadWord: UInt =
    if (useSramStorage) {
      sramPorts.readPorts(0).address := wordAddress(indexSafe, wordOffset)
      sramPorts.readPorts(0).enable :=
        (io.req.fire && acceptedHit) || (io.req.fire && viptNeedWait)
      val sramReadBytes = sramPorts.readPorts(0).data
      Cat(sramReadBytes(3), sramReadBytes(2), sramReadBytes(1), sramReadBytes(0))
    } else {
      0.U(32.W)
    }
  private val sramReadWordHi: UInt =
    if (useSramStorage && DataWidth == 64) {
      sramPorts.readPorts(1).address :=
        wordAddress(indexSafe, nextOffset(wordOffset))
      sramPorts.readPorts(1).enable :=
        (io.req.fire && acceptedHit) || (io.req.fire && viptNeedWait)
      val sramReadBytes = sramPorts.readPorts(1).data
      Cat(sramReadBytes(3), sramReadBytes(2), sramReadBytes(1), sramReadBytes(0))
    } else {
      0.U(32.W)
    }
  // 早读数据在本拍到达时也可以直接参与 resolve。
  val resolveWord: UInt =
    if (useSramStorage) Mux(waitSramReadValue, sramReadWord, waitDataReg) else waitDataReg
  val resolveWordHi: UInt =
    if (useSramStorage) Mux(waitSramReadValue, sramReadWordHi, waitDataHiReg)
    else waitDataHiReg
  // 性能计数器: 已返回的MSHR字按hit计数，不应伪造第二次cache miss。
  // VIPT 未拿到 PA 时推迟到 resolve 拍再统计，避免把“尚未翻译”误报成 miss。
  io.perf_hit := (io.req.fire && !viptNeedWait && acceptedHit) ||
    (resolveReady && resolveHit)
  io.perf_miss := (io.req.fire && !viptNeedWait && cacheable && !acceptedHit) ||
    (resolveReady && resolveCacheable && !resolveHit)
  if (useSramStorage) {
    when(waitSramReadValue) {
      waitDataReg := sramReadWord
      if (DataWidth == 64) { waitDataHiReg := sramReadWordHi }
      waitDataValidValue := true.B
    }
  }
  // 响应数据源: 旁路模式下 SRAM 数据到达当拍用刚取回的字, 其它情况用响应寄存器。
  if (useSramStorage) {
    io.resp.bits.data := (if (bypassResponse) {
      Mux(
        sramResponsePendingValue,
        formatLoad(
          sramReadWord,
          sramReadWordHi,
          sramRequestAddressValue,
          sramRequestWidthValue,
          sramRequestSignedValue
        ),
        responseData
      )
    } else { responseData })
  }
  if (useSramStorage) {
    when(io.req.fire && acceptedHit) {
      sramRequestAddressValue := io.req.bits.addr
      sramRequestWidthValue := io.req.bits.WidthSelect
      sramRequestSignedValue := io.req.bits.signed
    }
    sramResponsePendingValue := io.req.fire && acceptedHit
    // VIPT 缺 PA 的受理拍已经提前发起 SRAM 读，下一拍锁存。
    waitSramReadValue := io.req.fire && viptNeedWait
    // SRAM 数据到达的当拍: 旁路模式下若同拍被消费则不再锁存;
    // 非旁路模式下锁存并置 valid(下一拍响应)。
    when(sramResponsePendingValue) {
      responseData := formatLoad(
        sramReadWord,
        sramReadWordHi,
        sramRequestAddressValue,
        sramRequestWidthValue,
        sramRequestSignedValue
      )
      responseFault := false.B
      responseFaultResponse := 0.U
      if (bypassResponse) {
        when(!io.resp.ready) {
          responseValid := true.B
        }
      } else {
        responseValid := true.B
      }
    }
  }

  when(io.StoreValid && storeCacheable) {
    // 需求请求可能已在其缓存行仍在到达时提前重启。若更年轻store此时完成，
    // 即使BRESP报错也不能排除部分副作用；剩余读拍都可能已过期，因此应完成
    // 排空但绝不安装此次回填。
    when(storeConflictsRefill) {
      suppressFill := true.B
      refillWordValid := 0.U
      valid(storeIndexSafe) := false.B
    }
    when(storeHit) {
      when(io.StoreResponse === 0.U) {
        if (useSramStorage) {
          // 成功的单拍 store 精确知道新字节值，直接维护写直达镜像。
          sramPorts.writePorts(storeWriteBase).enable := true.B
          if (DataWidth == 64) {
            when(io.StoreStrobe(7, 4) =/= 0.U) {
              sramPorts.writePorts(storeWriteBase + 1).enable := true.B
            }
          }
        } else {
          // 成功的写直达 store 直接更新缓存镜像。RV64 的 8 字节 store
          // 同时更新低/高两个相邻 word；低 32 位掩码始终来自 StoreData
          // 的低半部分，高半部分仅在高 4 位字节使能非零时写入。
          val lowMask = storeMaskWord(io.StoreStrobe(3, 0))
          val lowData = io.StoreData(31, 0)
          if (wordsPerBlock > 1) {
            data.get(storeIndexSafe)(storeOffset) :=
              (data.get(storeIndexSafe)(storeOffset) & ~lowMask) |
                (lowData & lowMask)
          } else {
            data.get(storeIndexSafe)(0) :=
              (data.get(storeIndexSafe)(0) & ~lowMask) | (lowData & lowMask)
          }
          if (DataWidth == 64) {
            when(io.StoreStrobe(7, 4) =/= 0.U) {
              val highMask = storeMaskWord(io.StoreStrobe(7, 4))
              val highData = io.StoreData(63, 32)
              if (wordsPerBlock > 1) {
                data.get(storeIndexSafe)(nextOffset(storeOffset)) :=
                  (data.get(storeIndexSafe)(nextOffset(storeOffset)) & ~highMask) |
                    (highData & highMask)
              } else {
                data.get(storeIndexSafe)(0) :=
                  (data.get(storeIndexSafe)(0) & ~highMask) | (highData & highMask)
              }
            }
          }
        }
      }.otherwise {
        // SLVERR/DECERR不保证从机完全没有副作用。不能把失败store的数据
        // 投机写入cache，但也不能继续保留可能已经过期的旧缓存行。
        valid(storeIndexSafe) := false.B
      }
    }
  }
  // 写直达 store 完成后内存已是新值：同一物理行的其他 alias 副本（即使
  // store 本身按 VA index 没有命中）都必须失效。成功命中时保留 StoreIndex
  // 这份并由上面的写镜像/写口更新；失败 store 则连命中行一起失效。
  if (aliasAllSets) {
    for (i <- valid.indices) {
      val keepTarget = storeHit && io.StoreResponse === 0.U &&
        storeIndexSafe === i.U
      when(io.StoreValid && storeCacheable && valid(i) &&
          tag(i) === storeTag && !keepTarget) {
        valid(i) := false.B
      }
    }
  }
  // 先清旧响应再安装新响应：与 io.resp.fire 同拍受理的命中必须让新响应
  // 在下一拍保持 valid。
  when(io.resp.fire) {
    responseValid := false.B
    demandResponseDone := true.B
  }
  when(io.req.fire) {
    responseAddressReg := io.req.bits.addr
    responseWidthReg := io.req.bits.WidthSelect
    responseSignedReg := io.req.bits.signed
    responseCacheableReg := cacheable
    when(hitUnderRefill) {
      // 此请求的数据已经在先前R拍写入阵列。不要覆盖ReqIndex/Tag/Base等
      // MSHR元数据，也不要改变正在进行的AR/R状态。
      if (!useSramStorage) {
        responseData := formatLoad(
          readDataWord(indexSafe, wordOffset),
          (if (wordsPerBlock > 1) readDataWord(indexSafe, nextOffset(wordOffset))
           else 0.U(32.W)),
          io.req.bits.addr,
          io.req.bits.WidthSelect,
          io.req.bits.signed
        )
      }
      responseFault := false.B
      responseFaultResponse := 0.U
      if (!useSramStorage) { responseValid := true.B }
    }.elsewhen(viptNeedWait) {
      // VIPT：PA 尚未带正确 VA 标签返回。受理拍只锁存 VA 选出的
      // index/offset，绝不判定命中、发 AR 或提交故障响应。
      waitPhysicalAddress := true.B
      waitVirtualAddressReg := io.req.bits.vaddr
      waitIndexReg := index
      waitOffsetReg := wordOffset
      waitWidthReg := io.req.bits.WidthSelect
      waitSignedReg := io.req.bits.signed
      if (useSramStorage) {
        waitDataValidValue := false.B
      } else {
        waitDataReg := readDataWord(indexSafe, wordOffset)
        waitDataHiReg :=
          (if (wordsPerBlock > 1) readDataWord(indexSafe, nextOffset(wordOffset))
           else 0.U(32.W))
      }
    }.otherwise {
      requestAddressReg := io.req.bits.addr
      requestWidthReg := io.req.bits.WidthSelect
      requestSignedReg := io.req.bits.signed
      requestIndexReg := index
      requestTagReg := requestTag
      requestOffsetReg := wordOffset
      wideBeat := 0.U
      wideLowFault := false.B
      responseFault := false.B
      responseFaultResponse := 0.U
      responseValid := false.B
      demandResponseDone := false.B
      suppressFill := false.B
      when(hit) {
        if (!useSramStorage) {
          responseData := formatLoad(
            readDataWord(indexSafe, wordOffset),
            (if (wordsPerBlock > 1) readDataWord(indexSafe, nextOffset(wordOffset))
             else 0.U(32.W)),
            io.req.bits.addr,
            io.req.bits.WidthSelect,
            io.req.bits.signed
          )
          responseValid := true.B
        }
        state := stateIdle
      }.otherwise {
        refillActive := cacheable
        refillBurst := cacheable
        refillCount := 0.U
        refillError := false.B
        refillErrorResponse := 0.U
        refillWordValid := 0.U
        refillBaseReg := Cat(
          io.req.bits.addr(AddressWidth - 1, BlockSizeLog2),
          0.U(BlockSizeLog2.W)
        )
        when(cacheable) { valid(indexSafe) := false.B }
        state := stateReadRequest
      }
    }
  }
  // 迟到的 PA 与受理时 VA 标签匹配且早读数据已就绪：现在才用物理 tag
  // 判定命中/缺失。命中直接用早读字；缺失用解析出的 PA 启动 refill，
  // 安装位置仍是 VA index。
  when(resolveReady) {
    waitPhysicalAddress := false.B
    responseAddressReg := resolvePhysicalAddress
    responseWidthReg := waitWidthReg
    responseSignedReg := waitSignedReg
    responseCacheableReg := resolveCacheable
    when(resolveHit) {
      responseData := formatLoad(
        resolveWord,
        resolveWordHi,
        waitVirtualAddressReg,
        waitWidthReg,
        waitSignedReg
      )
      responseFault := false.B
      responseFaultResponse := 0.U
      responseValid := true.B
      demandResponseDone := true.B
    }.otherwise {
      requestAddressReg := resolvePhysicalAddress
      requestWidthReg := waitWidthReg
      requestSignedReg := waitSignedReg
      requestIndexReg := waitIndexReg
      requestTagReg := resolveTag
      requestOffsetReg := waitOffsetReg
      wideBeat := 0.U
      wideLowFault := false.B
      responseFault := false.B
      responseFaultResponse := 0.U
      responseValid := false.B
      demandResponseDone := false.B
      suppressFill := false.B
      refillActive := resolveCacheable
      refillBurst := resolveCacheable
      refillCount := 0.U
      refillError := false.B
      refillErrorResponse := 0.U
      refillWordValid := 0.U
      refillBaseReg := Cat(
        resolvePhysicalAddress(AddressWidth - 1, BlockSizeLog2),
        0.U(BlockSizeLog2.W)
      )
      when(resolveCacheable) { valid(resolveIndexSafe) := false.B }
      state := stateReadRequest
    }
  }
  // MMU 翻译故障杀死等待中的请求：不能留下响应或总线事务。
  when(io.req_kill) {
    waitPhysicalAddress := false.B
    if (useSramStorage) {
      waitDataValidValue := false.B
      waitSramReadValue := false.B
    }
  }
  switch(state) {
    is(stateReadRequest) {
      io.AXI.AR.ARVALID := true.B
      io.AXI.AR.ARADDR := Mux(
        refillActive,
        refillBaseReg + Cat(refillCount, 0.U(2.W)),
        requestAddressReg
      )
      io.AXI.AR.ARLEN := Mux(
        refillActive && refillBurst,
        (beatsPerBlock - 1).U,
        Mux(!refillActive && requestWidthReg === 3.U &&
          (BusDataWidth == 32).B, 1.U, 0.U)
      )
      io.AXI.AR.ARSIZE := Mux(
        refillActive,
        busBytesLog2.U(3.W),
        Mux(requestWidthReg === 3.U,
          (if (BusDataWidth == 64) 3.U else 2.U),
          requestWidthReg)
      )
      // AXI要求ARVALID一旦提出就保持到握手。即使flush到来，也继续完成
      // 地址和R通道事务；响应仍交付demand，但不安装该次回填的cache line。
      when(io.AXI.AR.ARREADY) {
        // 缓存行在 AR 被接受时安装：清除其他 set 中同物理行的旧副本。
        // 若本次 refill 已被 store/flush 抑制，则不再改动别名副本。
        when(refillActive && !suppressFill && !io.flush) {
          invalidateAliasCopies(requestTagReg, requestIndexSafe)
        }
        state := stateReadResponse
      }
    }
    is(stateReadResponse) {
      io.AXI.R.RREADY := true.B
      when(io.AXI.R.RVALID) {
        when(refillActive) {
          val beatError = io.AXI.R.RRESP =/= 0.U
          val keywordBeat = wordInRefillBeat(requestOffsetReg)
          val requestWide = requestWidthReg === 3.U
          val wideHighInBeat = requestWide &&
            wordInRefillBeat(nextOffset(requestOffsetReg))
          val wideHighBeat = wideHighInBeat && !keywordBeat
          when(beatError) {
            refillError := true.B
            refillWordValid := 0.U
            when(!refillError) {
              refillErrorResponse := io.AXI.R.RRESP
            }
            // 任一 beat 出错都意味着本次 refill 不可安装。若 demand 还没有
            // 拿到响应，就立即以该错误产生 fault，而不是只对 keyword/high
            // beat 报错，导致后续成功 beat 被 RefillError 挡住后永久等待。
            when(!demandResponseDone && !responseValid) {
              responseFault := true.B
              responseFaultResponse := io.AXI.R.RRESP
              responseValid := true.B
            }
          }
          when(!beatError) {
            // flush期间或之后返回的数据可以满足需求load，但不得重新填充
            // 已失效的缓存行。
            when(
              !refillError && !suppressFill && !io.flush &&
                !storeConflictsRefill
            ) {
              tag(requestIndexSafe) := requestTagReg
              for (lane <- 0 until wordsPerBeat) {
                if (useSramStorage) {
                  sramPorts.writePorts(lane).enable := true.B
                } else {
                  data.get(requestIndexSafe)(refillBeatWordIndex(lane)) :=
                    io.AXI.R.RDATA(32 * lane + 31, 32 * lane)
                }
              }
              refillWordValid := refillWordValid | refillBeatWordMask
            }
            // 早期 beat 已经产生 fault 时，关键词 beat 不得把响应覆盖成
            // 成功数据；RefillError 在本次回填结束前保持锁存。
            when(keywordBeat && !demandResponseDone && !refillError) {
              val keywordWord =
                refillBeatWord(requestOffsetReg, io.AXI.R.RDATA)
              when(requestWide) {
                when(wideHighInBeat) {
                  responseData := formatLoad(
                    keywordWord,
                    refillBeatWord(nextOffset(requestOffsetReg), io.AXI.R.RDATA),
                    requestAddressReg,
                    requestWidthReg,
                    requestSignedReg
                  )
                  responseFault := false.B
                  responseFaultResponse := 0.U
                  responseValid := true.B
                }.otherwise {
                  wideLowReg := keywordWord
                }
              }.otherwise {
                responseData := formatLoad(
                  keywordWord,
                  0.U(32.W),
                  requestAddressReg,
                  requestWidthReg,
                  requestSignedReg
                )
                responseFault := false.B
                responseFaultResponse := 0.U
                responseValid := true.B
              }
            }
            when(wideHighBeat && !demandResponseDone && !responseValid &&
                !refillError && !beatError) {
              responseData := formatLoad(
                wideLowReg,
                refillBeatWord(nextOffset(requestOffsetReg), io.AXI.R.RDATA),
                requestAddressReg,
                requestWidthReg,
                requestSignedReg
              )
              responseFault := false.B
              responseFaultResponse := 0.U
              responseValid := true.B
            }
          }
          when(lastRefillBeat) {
            when(io.AXI.R.RLAST) {
              val refillInstallOk =
                !suppressFill && !io.flush && !storeConflictsRefill &&
                  !refillError && !beatError
              perfLineFill := refillInstallOk
              when(refillInstallOk) {
                // serial/parallel 扫描尚未清完旧副本时先挂起，扫描结束
                // 后再置 valid；扫描窗口内不出现两份有效副本。
                when(aliasInstallAllowed) {
                  valid(requestIndexSafe) := true.B
                }.elsewhen(!aliasScanStatus.overflow) {
                  if (aliasScanActive) {
                    val (_, deferredIndex, deferredTag, deferredValid) =
                      aliasScanState.get
                    deferredValid := true.B
                    deferredIndex := requestIndexSafe
                    deferredTag := requestTagReg
                  }
                }
              }.otherwise {
                valid(requestIndexSafe) := false.B
              }
              // 兜底：即使错误 beat 发生时响应槽恰好被别的路径挡住，也不能
              // 在 refill 结束、缓存行已失效后留下一个永不返回的 demand。
              when((refillError || beatError) && !demandResponseDone &&
                  !responseValid) {
                responseFault := true.B
                responseFaultResponse := Mux(
                  refillErrorResponse =/= 0.U,
                  refillErrorResponse,
                  Mux(beatError, io.AXI.R.RRESP, 2.U)
                )
                responseValid := true.B
              }
              refillActive := false.B
              refillBurst := false.B
              refillWordValid := 0.U
              suppressFill := false.B
              state := stateIdle
            }.otherwise {
              // 预期的最后一拍到达时没有RLAST。仲裁器仍持有此响应期间，
              // 不得让load退休；应使缓存行失效并持续排空，直到迟到的RLAST出现。
              valid(requestIndexSafe) := false.B
              refillError := true.B
              refillWordValid := 0.U
              // 绝不修改或重新产生已经对外可见的Decoupled响应。若关键字先前已被
              // 消费，这个迟到的协议错误只会使缓存行失效并将其排空。
              when(!demandResponseDone && !responseValid) {
                responseFault := true.B
                responseFaultResponse := Mux(beatError, io.AXI.R.RRESP, 2.U)
                responseValid := true.B
              }
              state := stateDrain
            }
          }.elsewhen(refillBurst) {
            when(io.AXI.R.RLAST) {
              refillBurst := false.B
              refillCount := refillCount + wordsPerBeat.U
              state := stateReadRequest
            }.otherwise {
              refillCount := refillCount + wordsPerBeat.U
            }
          }.otherwise {
            // 退化请求只有一拍，因此必须以RLAST结束。
            when(io.AXI.R.RLAST) {
              refillCount := refillCount + wordsPerBeat.U
              state := stateReadRequest
            }.otherwise {
              valid(requestIndexSafe) := false.B
              refillError := true.B
              refillWordValid := 0.U
              when(!demandResponseDone && !responseValid) {
                responseFault := true.B
                responseFaultResponse := Mux(beatError, io.AXI.R.RRESP, 2.U)
                responseValid := true.B
              }
              state := stateDrain
            }
          }
        }.otherwise {
          if (BusDataWidth == 64) {
            when(requestWidthReg === 3.U) {
              when(io.AXI.R.RRESP =/= 0.U) {
                responseFault := true.B
                responseFaultResponse := io.AXI.R.RRESP
              }.otherwise {
                responseData := formatLoad(
                  io.AXI.R.RDATA(31, 0),
                  io.AXI.R.RDATA(63, 32),
                  requestAddressReg,
                  requestWidthReg,
                  requestSignedReg
                )
              }
              responseValid := true.B
              when(io.AXI.R.RLAST) {
                suppressFill := false.B
                state := stateIdle
              }.otherwise {
                responseFault := true.B
                responseFaultResponse := Mux(
                  io.AXI.R.RRESP =/= 0.U,
                  io.AXI.R.RRESP,
                  2.U
                )
                state := stateDrain
              }
            }.otherwise {
              val uncachedWord = Mux(
                requestAddressReg(2),
                io.AXI.R.RDATA(63, 32),
                io.AXI.R.RDATA(31, 0)
              )
              when(io.AXI.R.RRESP =/= 0.U) {
                responseFault := true.B
                responseFaultResponse := io.AXI.R.RRESP
              }.otherwise {
                responseData := formatLoad(
                  uncachedWord,
                  0.U(32.W),
                  requestAddressReg,
                  requestWidthReg,
                  requestSignedReg
                )
              }
              responseValid := true.B
              when(io.AXI.R.RLAST) {
                suppressFill := false.B
                state := stateIdle
              }.otherwise {
                responseFault := true.B
                responseFaultResponse := Mux(
                  io.AXI.R.RRESP =/= 0.U,
                  io.AXI.R.RRESP,
                  2.U
                )
                state := stateDrain
              }
            }
          } else {
            when(requestWidthReg === 3.U) {
              when(wideBeat === 0.U) {
                when(io.AXI.R.RLAST) {
                  responseFault := true.B
                  responseFaultResponse := Mux(
                    io.AXI.R.RRESP =/= 0.U,
                    io.AXI.R.RRESP,
                    2.U
                  )
                  responseValid := true.B
                  state := stateIdle
                }.otherwise {
                  wideLowReg := io.AXI.R.RDATA
                  wideLowFault := io.AXI.R.RRESP =/= 0.U
                  wideBeat := 1.U
                }
              }.otherwise {
                when(io.AXI.R.RLAST) {
                  when(wideLowFault || io.AXI.R.RRESP =/= 0.U) {
                    responseFault := true.B
                    responseFaultResponse := Mux(
                      io.AXI.R.RRESP =/= 0.U,
                      io.AXI.R.RRESP,
                      2.U
                    )
                  }.otherwise {
                    responseData := formatLoad(
                      wideLowReg,
                      io.AXI.R.RDATA,
                      requestAddressReg,
                      requestWidthReg,
                      requestSignedReg
                    )
                  }
                  responseValid := true.B
                  suppressFill := false.B
                  state := stateIdle
                }.otherwise {
                  when(!demandResponseDone && !responseOccupied) {
                    responseFault := true.B
                    responseFaultResponse := Mux(
                      io.AXI.R.RRESP =/= 0.U,
                      io.AXI.R.RRESP,
                      2.U
                    )
                    responseValid := true.B
                  }
                  state := stateDrain
                }
              }
            }.otherwise {
              when(io.AXI.R.RRESP =/= 0.U) {
                responseFault := true.B
                responseFaultResponse := io.AXI.R.RRESP
              }.otherwise {
                responseData := formatLoad(
                  io.AXI.R.RDATA,
                  0.U(32.W),
                  requestAddressReg,
                  requestWidthReg,
                  requestSignedReg
                )
              }
              responseValid := true.B
              when(io.AXI.R.RLAST) {
                suppressFill := false.B
                state := stateIdle
              }.otherwise {
                responseFault := true.B
                responseFaultResponse := Mux(
                  io.AXI.R.RRESP =/= 0.U,
                  io.AXI.R.RRESP,
                  2.U
                )
                state := stateDrain
              }
            }
          }
        }
      }
    }
    is(stateDrain) {
      io.AXI.R.RREADY := true.B
      when(io.AXI.R.RVALID && io.AXI.R.RLAST) {
        refillActive := false.B
        refillBurst := false.B
        refillWordValid := 0.U
        suppressFill := false.B
        state := stateIdle
      }.elsewhen(drainTimer === DrainTimeout.U) {
        // 从设备长期不给 RLAST：为尚未交付的 demand 产生 fault，缓存行
        // 保持无效，然后进入吸收态，迟到的 beat 不能再被后续请求使用。
        // 已经 valid 的响应可能正被下游反压，Decoupled payload 必须保持
        // 稳定；超时只允许填充空响应槽，绝不能把成功改写成故障。
        when(!demandResponseDone && !responseOccupied) {
          responseFault := true.B
          responseFaultResponse := 2.U
          responseValid := true.B
        }
        valid(requestIndexSafe) := false.B
        refillActive := false.B
        refillBurst := false.B
        refillError := false.B
        refillErrorResponse := 0.U
        refillWordValid := 0.U
        suppressFill := false.B
        state := stateDrainFault
      }.otherwise {
        drainTimer := drainTimer + 1.U
      }
    }
    is(stateDrainFault) {
      // 继续吸收违规从设备的迟到拍，直到 RLAST 使总线重新同步。
      io.AXI.R.RREADY := true.B
      when(io.AXI.R.RVALID && io.AXI.R.RLAST) {
        state := stateIdle
      }.elsewhen(drainRetryTimer === DrainTimeout.U && !io.AXI.R.RVALID) {
        // 通道已安静足够久：放弃这笔无法同步的事务，恢复服务。
        state := stateIdle
      }.otherwise {
        drainRetryTimer := drainRetryTimer + 1.U
      }
    }
  }
  // DMA 地址级 snoop 必须放在所有 refill/store 写回之后：同一拍 refill
  // 刚把旧行安装为 valid 时，这里的失效写回优先级更高。
  if (dmaSnoopActive) {
    // 在旧 refill 排空、状态回 SIdle 后，按原访问重新发起一次读取。
    // 此时 DMA 的 B 已经到达（下游写完成），新读取必然看到新数据。
    when(dmaReplayWait && state === stateIdle && !waitPhysicalAddress && !io.flush) {
      val replayLine =
        Cat(dmaReplayAddress(AddressWidth - 1, BlockSizeLog2),
          0.U(BlockSizeLog2.W))
      requestAddressReg := dmaReplayAddress
      requestWidthReg := dmaReplayWidth
      requestSignedReg := dmaReplaySigned
      requestIndexReg :=
        dmaReplayAddress(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
      requestTagReg := tagOf(dmaReplayAddress)
      requestOffsetReg :=
        (if (wordsPerBlock > 1) dmaReplayAddress(BlockSizeLog2 - 1, 2)
         else 0.U(offsetWidth.W))
      refillActive := dmaReplayCacheable
      refillBurst := dmaReplayCacheable
      refillCount := 0.U
      refillError := false.B
      refillErrorResponse := 0.U
      refillWordValid := 0.U
      refillBaseReg := replayLine
      responseValid := false.B
      if (useSramStorage) { sramResponsePendingValue := false.B }
      demandResponseDone := false.B
      suppressFill := false.B
      state := stateReadRequest
      dmaReplayWait := false.B
    }
    val dmaSnoopHit = dmaSnoopHitVec
    for (set <- valid.indices) {
      when(io.dma_snoop_valid && dmaSnoopHit(set)) {
        valid(set) := false.B
      }
    }
    when(io.dma_snoop_valid && dmaSnoopRefillLineHit) {
      // 旧 burst 的剩余 R beat 仍会到达，但它们既不能安装也不能形成响应。
      suppressFill := true.B
      refillWordValid := 0.U
      valid(requestIndexSafe) := false.B
    }
    // 放在 refill RLAST 之后：同拍 RLAST 可能刚把新行挂到 deferred，
    // 或被本拍 DMA snoop 抑制，必须立刻取消，扫描结束后不得补装。
    if (aliasScanActive) {
      val (_, deferredIndex, deferredTag, deferredValid) =
        aliasScanState.get
      when(io.dma_snoop_valid && deferredValid &&
          dmaLineInRange(deferredTag)) {
        valid(deferredIndex) := false.B
        deferredValid := false.B
      }
      when(io.dma_snoop_valid && dmaSnoopRefillLineHit) {
        deferredValid := false.B
      }
    }
    when(io.dma_snoop_valid && dmaSnoopReplay) {
      dmaReplayWait := true.B
      dmaReplayAddress := dmaSnoopResponseAddress
      dmaReplayWidth :=
        (if (useSramStorage)
           Mux(sramResponsePendingValue, sramRequestWidthValue, responseWidthReg)
         else responseWidthReg)
      dmaReplaySigned :=
        (if (useSramStorage)
           Mux(sramResponsePendingValue, sramRequestSignedValue, responseSignedReg)
         else responseSignedReg)
      dmaReplayCacheable := responseCacheableReg
      responseValid := false.B
      if (useSramStorage) { sramResponsePendingValue := false.B }
    }
  }
  // 目录失效放在 refill/store valid 写回之后：目录冲突挤出的旧行或
  // store/DMA snoop 必须压掉同拍 refill 最后一拍的 valid 置位。
  aliasDirectory.foreach { dir =>
    for (set <- valid.indices) {
      when(dir.io.invalidate(set)) {
        valid(set) := false.B
      }
    }
  }
  when(io.flush) { // fence.i/外部全局一致性/原子提交：清空全部行并取消 snoop 重放
    valid.foreach(_ := false.B)
    refillWordValid := 0.U
    dmaReplayWait := false.B
    if (aliasScanActive) {
      val (refillCleared, _, _, deferredValid) = aliasScanState.get
      deferredValid := false.B
      refillCleared := false.B
    }
    when(state =/= stateIdle) { suppressFill := true.B }
  }
}
