package ysyx_26030103.cache

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.infra._

// 单请求直映射DCache，读通道回填，写通道由LSU写缓冲负责
class ysyx_26030103_DCacheReg(
    Enable: Boolean = true,
    BlockSizeLog2: Int = 4,
    IndexBits: Int = 5,
    AddressWidth: Int = 32,
    DataWidth: Int = 32,
    Vipt: Boolean = false,
    // index 含 VPN 位时，同一物理行可被不同 VA 映射到不同 set；refill
    // 安装和写直达 store 完成时按物理 tag 失效其他 set 的副本。
    ViptAliasSafe: Boolean = false,
    // alias 失效扫描方式：all_sets 单拍全扫描；serial 每周期扫描一个 set；
    // parallel_n 每周期扫描 N 个 set；directory 维护物理行目录。
    ViptAliasScanMode: String = "all_sets",
    // DMA 地址级 snoop：只有 DMA_COHERENCE=hardware_snoop 才生成行失效
    // 与在途 refill 等待硬件，默认配置完全保留原有时序。
    DmaSnoop: Boolean = false,
    // parallel_n 的每周期扫描宽度；0=自动(min(4, sets))，超过 sets 时截断。
    ViptAliasScanWidth: Int = 0,
    // directory 的目录项数；0=跟随 sets（完整无冲突）。
    ViptAliasDirectoryEntries: Int = 0,
    CacheableBase: Long = 0x80000000L,
    CacheableMask: Long = 0x80000000L,
    PMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false)
) extends ysyx_26030103_DCacheBase(AddressWidth, DataWidth) {
  ysyx_26030103_ViptAliasScan.requireMode(ViptAliasScanMode, "DCacheReg")
  private val AliasScanActive =
    ViptAliasSafe && Enable &&
      ysyx_26030103_ViptAliasScan.isScan(ViptAliasScanMode)
  private val AliasScanParallel =
    ViptAliasSafe && Enable &&
      ysyx_26030103_ViptAliasScan.isParallel(ViptAliasScanMode)
  private val AliasAllSets =
    ViptAliasSafe && Enable &&
      ysyx_26030103_ViptAliasScan.isAllSets(ViptAliasScanMode)
  private val AliasDirectoryMode =
    ViptAliasSafe && Enable &&
      ysyx_26030103_ViptAliasScan.isDirectory(ViptAliasScanMode)
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
  private val TagBits =
    if (ViptAliasSafe) AddressWidth - BlockSizeLog2
    else AddressWidth - IndexBits - BlockSizeLog2
  private def TagOf(address: UInt): UInt =
    if (ViptAliasSafe) address(AddressWidth - 1, BlockSizeLog2)
    else address(AddressWidth - 1, IndexBits + BlockSizeLog2)
  private val NumBlocks = 1 << IndexBits
  private val WordsPerBlock = 1 << (BlockSizeLog2 - 2)
  private val IndexWidth = IndexBits.max(1)
  private val OffsetWidth = (BlockSizeLog2 - 2).max(1)
  private val CountWidth = log2Ceil(WordsPerBlock).max(1)
  private val ScanWidth =
    ysyx_26030103_ViptAliasScan.resolveScanWidth(
      ViptAliasScanMode,
      ViptAliasScanWidth,
      NumBlocks
    )
  private val DirectoryEntries =
    if (ViptAliasDirectoryEntries <= 0) NumBlocks
    else {
      require(ViptAliasDirectoryEntries <= NumBlocks,
        s"VIPT alias 目录项数($ViptAliasDirectoryEntries) 不能超过 set 数($NumBlocks)")
      ViptAliasDirectoryEntries
    }
  private val ArrayBlocks = if (Enable) NumBlocks else 1
  // 8 字节访问要求低/高两个相邻 word 同处一行。LD/SD 按 ISA 必须
  // 8 字节对齐，因此块内 word 偏移总是偶数，NextOffset 不会越过行尾。
  private def NextOffset(offset: UInt): UInt =
    (offset + 1.U)(CountWidth - 1, 0)
  // 一致性 flush 是 DCache 自身的可验证接口；生产顶层已由 DMA 写 B
  // 握手的失效脉冲驱动（ysyx_26030103.scala），dontTouch 保证该端口在
  // 叶模块 elaboration 中不被裁掉。
  dontTouch(io.flush)
  val valid = RegInit(VecInit(Seq.fill(ArrayBlocks)(false.B)))
  val tag = Reg(Vec(ArrayBlocks, UInt(TagBits.W)))
  val data = Reg(Vec(ArrayBlocks, Vec(WordsPerBlock, UInt(32.W))))
  // 仅 all_sets 在 refill 安装/store 完成时展开全组 tag 比较；
  // serial/parallel 由扫描引擎完成，directory 由目录完成。
  private def InvalidateAliasCopies(AliasTag: UInt, TargetIndex: UInt): Unit = {
    if (AliasAllSets) {
      for (i <- valid.indices) {
        when(valid(i) && tag(i) === AliasTag && i.U =/= TargetIndex) {
          valid(i) := false.B
        }
      }
    }
  }
  private val DmaSnoopActive = DmaSnoop && Enable
  // 每个 set 的完整物理行号。alias-safe 的 tag 已含物理行号；普通几何
  // 需要把 tag 与 set index 拼回去，才能对任意 burst 范围做行号比较。
  private def DmaLineOfSet(set: Int): UInt =
    if (ViptAliasSafe) tag(set)
    else Cat(tag(set), set.U(IndexBits.W))
  private val DmaStartLine = io.dma_snoop_start(AddressWidth - 1, BlockSizeLog2)
  private val DmaEndLine = io.dma_snoop_end(AddressWidth - 1, BlockSizeLog2)
  private def DmaLineInRange(line: UInt): Bool =
    line >= DmaStartLine && line <= DmaEndLine
  private def DmaSnoopHitVec: UInt =
    if (!DmaSnoopActive) 0.U(ArrayBlocks.W)
    else
      VecInit(valid.indices.map { set =>
        valid(set) && DmaLineInRange(DmaLineOfSet(set))
      }).asUInt
  private val AliasDirectory =
    if (AliasDirectoryMode) {
      Some(
        Module(
          new ysyx_26030103_ViptAliasDirectory(
            LineBits = AddressWidth - BlockSizeLog2,
            SetBits = IndexBits,
            NumSets = NumBlocks,
            Entries = DirectoryEntries
          )
        )
      )
    } else {
      None
    }
  val indexAddr = if (Vipt) io.req.bits.vaddr else io.req.bits.addr
  val index = indexAddr(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  val IndexSafe = if (Enable) index else 0.U(IndexWidth.W)
  val WordOffset =
    if (WordsPerBlock > 1) indexAddr(BlockSizeLog2 - 1, 2)
    else 0.U(OffsetWidth.W)
  val ReqTag = TagOf(io.req.bits.addr)
  // VIPT：req 只保证 VA 有效，PA 与它的 VA 标签都匹配后才能做 tag/PMA 判定。
  // PIPT 下这个条件恒为真，原有路径完全不变。
  val IncomingPaddrMatch =
    !Vipt.B || (io.req_paddr_valid && io.req_paddr_vaddr === io.req.bits.vaddr)
  val cacheable = IncomingPaddrMatch && ysyx_26030103_DCachePolicy.IsCacheable(
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
  private val DmaReqLineHit = DmaSnoopActive.B && io.dma_snoop_valid &&
    DmaLineInRange(io.req.bits.addr(AddressWidth - 1, BlockSizeLog2))
  val hit =
    cacheable && valid(IndexSafe) && tag(IndexSafe) === ReqTag &&
      !DmaReqLineHit // 这个直接拿来做一个命中条件
  // VIPT 等待 PA 的请求状态：VA 已经选好 index/offset 并锁存，PA 未到前
  // 不判定命中、不发 AR。WaitDataReg 是受理拍用 VA 组索引早读出的字，
  // PA 匹配后若 tag 仍命中即可直接返回（等待期间没有其他请求能改写阵列）。
  val WaitPaddr = RegInit(false.B)
  val WaitVaddrReg = Reg(UInt(AddressWidth.W))
  val WaitIndexReg = Reg(UInt(IndexWidth.W))
  val WaitOffsetReg = Reg(UInt(CountWidth.W))
  val WaitWidthReg = Reg(UInt(2.W))
  val WaitSignedReg = Reg(Bool())
  val WaitDataReg = Reg(UInt(32.W))
  val WaitDataHiReg = Reg(UInt(32.W))
  val ViptNeedWait = Vipt.B && !IncomingPaddrMatch
  // PA 带受理时的 VA 标签返回：只接受属于这笔等待请求的翻译结果。
  // flush/kill 同拍时优先放弃本次 resolve，避免把已失效行或故障请求的数据
  // 送上响应通道。
  val ResolveVaddrMatch = !io.flush && !io.req_kill && WaitPaddr &&
    io.req_paddr_valid && io.req_paddr_vaddr === WaitVaddrReg
  val ResolvePaddr = io.req.bits.addr
  val ResolveTag = TagOf(ResolvePaddr)
  val ResolveIndexSafe = if (Enable) WaitIndexReg else 0.U(IndexWidth.W)
  val ResolveCacheable = ysyx_26030103_DCachePolicy.IsCacheable(
    ResolvePaddr,
    Enable,
    AddressWidth,
    BlockSizeLog2,
    CacheableBase,
    CacheableMask,
    PMARegions
  )
  private val DmaResolveLineHit = DmaSnoopActive.B && io.dma_snoop_valid &&
    DmaLineInRange(ResolvePaddr(AddressWidth - 1, BlockSizeLog2))
  val ResolveHit = ResolveVaddrMatch && ResolveCacheable &&
    valid(ResolveIndexSafe) && tag(ResolveIndexSafe) === ResolveTag &&
    !DmaResolveLineHit
  val ReqAddrReg = Reg(UInt(AddressWidth.W))
  val ReqWidthReg = Reg(UInt(2.W))
  val ReqSignedReg = Reg(Bool())
  val ReqIndexReg = Reg(UInt(IndexWidth.W))
  val ReqTagReg = Reg(UInt(TagBits.W))
  val ReqOffsetReg = Reg(UInt(CountWidth.W))
  val RefillBaseReg = Reg(UInt(AddressWidth.W))
  val RefillCount = RegInit(0.U(CountWidth.W))
  val RefillActive = RegInit(false.B)
  // 单MSHR回填期间逐字记录已经成功返回的数据。缓存行在最后一拍以前仍然
  // 保持invalid，但这些字可以安全地服务同一行的后续load；未置位的字绝不
  // 能读取data阵列，因为其中可能还是旧行或尚未初始化的内容。
  val RefillWordValid = RegInit(0.U(WordsPerBlock.W))
  // 正常回填使用一笔 INCR burst。仅当不符合请求长度的从机提前给出
  // RLAST 时，才退化成逐字单拍补齐剩余数据，避免需求 load 永久挂起。
  val RefillBurst = RegInit(false.B)
  val RefillError = RegInit(false.B)
  val ResponseData = RegInit(0.U(DataWidth.W))
  // 非缓存 8 字节读需要两拍：第一拍暂存低 word，第二拍拼成 64 位响应。
  val WideLowReg = RegInit(0.U(32.W))
  val WideBeat = RegInit(0.U(1.W))
  val WideLowFault = RegInit(false.B)
  val ResponseFault = RegInit(false.B)
  val ResponseFaultResp = RegInit(0.U(2.W))
  val ResponseValid = RegInit(false.B)
  val DemandResponseDone = RegInit(false.B)
  // 当前响应对应的完整物理访问。命中/早期响应可能来自同拍受理的另一
  // 请求，不能只靠 ReqAddrReg 推断；DMA snoop 用它判断响应是否属于被
  // 改写行，并在需要时按原访问重新发起一次读取。
  val ResponseAddrReg = Reg(UInt(AddressWidth.W))
  val ResponseWidthReg = Reg(UInt(2.W))
  val ResponseSignedReg = Reg(Bool())
  val ResponseCacheableReg = RegInit(false.B)
  val DmaReplayWait = RegInit(false.B)
  val DmaReplayAddr = Reg(UInt(AddressWidth.W))
  val DmaReplayWidth = Reg(UInt(2.W))
  val DmaReplaySigned = Reg(Bool())
  val DmaReplayCacheable = RegInit(false.B)
  val states = Enum(4)
  val SIdle = states(0)
  val SReadReq = states(1)
  val SReadResp = states(2)
  val SDrain = states(3)
  val state = RegInit(SIdle)
  // flush会使缓存状态失效，但不能取消已经接受的需求请求：LSU没有取消握手，
  // 并且正在等待恰好一个响应。suppressFill在维持在途请求的同时，防止跨越
  // 本次flush的缓存行重新变为有效。
  val suppressFill = RegInit(false.B)
  val ReqIndexSafe = if (Enable) ReqIndexReg else 0.U(IndexWidth.W)
  // DMA snoop 握手：匹配行还有在途 R beat 或尚未被 CPU 取走的旧响应时，
  // ready 保持为低，顶层据此继续扣住 DMA B。SReadReq 阶段 AR 尚未被
  // 接受，此时读到的已经是 DMA 完成后的内存，不需要等待。
  // 若 refill 在本拍完成，末尾的失效写回优先级更高，会把同拍安装的
  // valid 压回 0。
  private val DmaSnoopRefillLineHit = DmaSnoopActive.B && RefillActive &&
    (state === SReadResp || state === SDrain) &&
    DmaLineInRange(RefillBaseReg(AddressWidth - 1, BlockSizeLog2))
  private val DmaSnoopRespMatch = DmaSnoopActive.B && ResponseValid &&
    DmaLineInRange(ResponseAddrReg(AddressWidth - 1, BlockSizeLog2))
  private val DmaSnoopRespReplay = DmaSnoopRespMatch &&
    ResponseCacheableReg && !DemandResponseDone
  private val DmaSnoopRefillOwnDemand = DmaSnoopRefillLineHit &&
    ResponseCacheableReg &&
    ResponseAddrReg(AddressWidth - 1, BlockSizeLog2) ===
      RefillBaseReg(AddressWidth - 1, BlockSizeLog2)
  private val DmaSnoopReplay = DmaSnoopActive.B && !DemandResponseDone &&
    (DmaSnoopRespReplay || DmaSnoopRefillOwnDemand)
  private val DmaSnoopWillReplay =
    DmaSnoopActive.B && io.dma_snoop_valid && DmaSnoopReplay
  private val DmaSnoopResponseBusy = DmaSnoopActive.B && DmaSnoopRespMatch &&
    !DmaSnoopRespReplay && !io.resp.fire
  private val DmaSnoopViptWaitBusy = DmaSnoopActive.B && Vipt.B && WaitPaddr
  io.dma_snoop_ready := (!DmaSnoopActive).B ||
    (!DmaSnoopRefillLineHit && !DmaSnoopResponseBusy &&
      !DmaSnoopWillReplay && !DmaReplayWait && !DmaSnoopViptWaitBusy)
  // 将最多两个 32 位 word 格式化为架构 load 结果。
  // width=0/1/2 分别是 LB/LH/LW，按 DataWidth 做符号/零扩展；
  // width=3 只在 64 位 DCache 下出现，直接拼接高/低 word。
  def FormatLoad(
      wordLow: UInt,
      wordHigh: UInt,
      address: UInt,
      width: UInt,
      signed: Bool
  ): UInt = {
    val word = wordLow(31, 0)
    val rawWide = Cat(wordHigh(31, 0), word)
    val ByteData = MuxLookup(address(1, 0), word(7, 0))(
      Seq(
        1.U -> word(15, 8),
        2.U -> word(23, 16),
        3.U -> word(31, 24)
      )
    )
    val HalfData = Mux(address(1), word(31, 16), word(15, 0))
    def Extend(Value: UInt, Bits: Int): UInt =
      if (DataWidth == Bits) Value
      else Mux(
        signed,
        Cat(Fill(DataWidth - Bits, Value(Bits - 1)), Value),
        Cat(0.U((DataWidth - Bits).W), Value)
      )
    val ByteResult = Extend(ByteData, 8)
    val HalfResult = Extend(HalfData, 16)
    val WordResult = Extend(word, 32)
    if (DataWidth == 32) {
      MuxLookup(width, WordResult)(
        Seq(0.U -> ByteResult, 1.U -> HalfResult, 2.U -> WordResult)
      )
    } else {
      MuxLookup(width, WordResult)(
        Seq(
          0.U -> ByteResult,
          1.U -> HalfResult,
          2.U -> WordResult,
          3.U -> rawWide
        )
      )
    }
  }
  def StoreMaskWord(strb: UInt): UInt = Cat(
    Mux(strb(3), "hff".U(8.W), 0.U(8.W)),
    Mux(strb(2), "hff".U(8.W), 0.U(8.W)),
    Mux(strb(1), "hff".U(8.W), 0.U(8.W)),
    Mux(strb(0), "hff".U(8.W), 0.U(8.W))
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
  // ResponseValid is a one-entry elastic response slot.  When the consumer
  // removes the old response in this cycle, a new request may reuse the slot
  // immediately; forbidding that pop+push case inserted a bubble between every
  // pair of cache hits.
  val ResponseSlotReady = !ResponseValid || io.resp.ready
  // 响应状态与回填状态解耦，使关键字能在缓存行其余数据到达前重启流水线。
  // 被 DMA snoop 命中的旧响应不能交付：本拍压掉 valid，下一拍重新发起
  // 读取（此时内存已包含 DMA 新数据）。
  private val DmaDropResponse = DmaSnoopActive.B && io.dma_snoop_valid &&
    DmaSnoopRespReplay
  io.resp.valid := ResponseValid && !DmaDropResponse && !DmaReplayWait
  io.resp.bits.data := ResponseData
  io.resp.bits.fault := ResponseFault
  io.resp.bits.FaultResp := ResponseFaultResp
  io.axi_active := state === SReadReq || state === SReadResp || state === SDrain
  io.perf_refill_req := RefillActive && state === SReadReq
  io.perf_refill_resp := RefillActive && state === SReadResp
  val storeIndexAddr = if (Vipt) io.StoreVAddr else io.StoreAddr
  val StoreIndex = storeIndexAddr(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  val StoreIndexSafe = if (Enable) StoreIndex else 0.U(IndexWidth.W)
  val StoreOffset =
    if (WordsPerBlock > 1) storeIndexAddr(BlockSizeLog2 - 1, 2)
    else 0.U(OffsetWidth.W)
  val StoreTag = TagOf(io.StoreAddr)
  val StoreCacheable = ysyx_26030103_DCachePolicy.IsCacheable(
    io.StoreAddr,
    Enable,
    AddressWidth,
    BlockSizeLog2,
    CacheableBase,
    CacheableMask,
    PMARegions
  )
  val StoreHit = StoreCacheable &&
    valid(StoreIndexSafe) && tag(StoreIndexSafe) === StoreTag
  val StoreConflictsRefill = io.StoreValid && RefillActive && StoreCacheable &&
    io.StoreAddr(AddressWidth - 1, BlockSizeLog2) ===
      RefillBaseReg(AddressWidth - 1, BlockSizeLog2)

  // Early restart之后，尾部回填仍独占AXI读ID，但其他set的完整hit以及本次
  // 回填中已经成功返回的字都不需要第二个MSHR。旧需求响应必须已经被消费
  // （或本拍正在消费），从而保持无tag响应接口的严格请求顺序。未返回字、
  // refill所在set的异tag请求和任何第二次miss仍反压到单MSHR释放。
  val SameRefillLine = cacheable && RefillActive &&
    io.req.bits.addr(AddressWidth - 1, BlockSizeLog2) ===
      RefillBaseReg(AddressWidth - 1, BlockSizeLog2)
  // 64 位 load 需要关键词和下一个 word 都已返回；32 位路径保持原判定。
  val WideWordReady =
    if (DataWidth == 64) VecInit(RefillWordValid.asBools)(NextOffset(WordOffset))
    else true.B
  val RefillWordReady = VecInit(RefillWordValid.asBools)(WordOffset) &&
    (!(io.req.bits.WidthSelect === 3.U) || WideWordReady)
  val PriorDemandLeaves = DemandResponseDone || io.resp.fire
  val RefillInProgress = state === SReadReq || state === SReadResp
  val LastRefillWord = RefillCount === (WordsPerBlock - 1).U
  // RREADY在SReadResp恒为高，因此RVALID即表示本拍会消费该beat。寄存的
  // RefillError要到下一拍才生效；将本拍RRESP错误和“必须为单拍末尾却缺
  // RLAST”的协议错误直接旁路进ready，避免错误被采样的同拍仍接受partial hit。
  val RefillBeatArrives = state === SReadResp && RefillActive && io.AXI.R.RVALID
  val RequiredLastMissing = RefillBeatArrives && !io.AXI.R.RLAST &&
    (LastRefillWord || !RefillBurst)
  val RefillFaultNow =
    (RefillBeatArrives && io.AXI.R.RRESP =/= 0.U) || RequiredLastMissing
  // 同拍成功store会在时钟沿才写入cache阵列；若此时接受同一行的load，响应
  // 会锁存store之前的旧值，因此保守地将该load反压一拍。
  val StoreConflictsRequest = io.StoreValid && StoreCacheable && cacheable &&
    io.StoreAddr(AddressWidth - 1, BlockSizeLog2) ===
      io.req.bits.addr(AddressWidth - 1, BlockSizeLog2)
  // 半填行只在“同一 set”内可见：alias 几何下另一个 VA 可能用同一 PA
  // 命中不同 index，而 refill 只把数据写进 ReqIndexSafe。
  val PartialHitUnderRefill = RefillInProgress && SameRefillLine &&
    (index === ReqIndexSafe) &&
    RefillWordReady && !RefillError && !suppressFill &&
    !StoreConflictsRefill && !RefillFaultNow
  val FullHitUnderRefill = RefillInProgress && RefillActive && hit
  val HitUnderRefill = PriorDemandLeaves && !io.flush &&
    !StoreConflictsRequest && (FullHitUnderRefill || PartialHitUnderRefill)
  val AcceptedHit = Mux(state === SIdle, hit, HitUnderRefill)
  // serial/parallel alias 扫描的事件源：
  //   refill：cacheable AR 被接受，安装新行时清掉其它 set 的旧副本；
  //   store ：写直达完成但按 VA index 未命中，可能有另一 set 的旧副本。
  // store 命中时该行已是唯一副本（refill/上次 store 已维护该不变量），
  // 直接原地更新即可，不需要扫描。
  val RefillAliasEvent = state === SReadReq && io.AXI.AR.ARREADY &&
    RefillActive && !suppressFill && !io.flush
  val StoreAliasEvent = io.StoreValid && StoreCacheable && !StoreHit
  // 扫描/事件排队期间反压新 load，保证不会命中尚未清理的 alias 副本。
  val AliasScanStatus =
    if (AliasScanActive) {
      ysyx_26030103_ViptAliasScan.chunkedEventQueueScan(
        valid,
        tag,
        RefillAliasEvent,
        ReqTagReg,
        ReqIndexSafe,
        StoreAliasEvent,
        StoreTag,
        io.flush,
        ScanWidth
      )
    } else {
      ysyx_26030103_ViptAliasScan.ScanStatus(
        false.B,
        true.B,
        false.B,
        false.B
      )
    }
  val AliasScanBusy = AliasScanStatus.busy
  // directory 模式在 AR 握手拍更新目录；同拍不受理新 load，避免组合
  // 命中尚未失效的旧 alias 副本。store 拍已有 StoreConflictsRequest 拦
  // 同一物理行的 load，目录失效在同一时钟沿完成。
  val AliasDirectoryInstallBusy =
    if (AliasDirectoryMode) RefillAliasEvent else false.B
  // 扫描模式把 refill 新行的 valid 推迟到本轮 refill 事件扫描完成。
  // 队列里可能先排着更早的 store 事件，因此用 refillCleared 脉冲 +
  // 一个 sticky 位记住“当前 refill 的旧副本已经清完”。
  // 只有扫描模式才生成 refill 事件跟踪与延迟安装状态；all_sets/
  // directory 不增加额外的 valid 写口。
  private val AliasScanState =
    if (AliasScanActive) {
      Some(
        (
          RegInit(false.B),
          Reg(UInt(IndexWidth.W)),
          Reg(UInt(TagBits.W)),
          RegInit(false.B)
        )
      )
    } else {
      None
    }
  val AliasInstallAllowed: Bool =
    if (!AliasScanActive) {
      true.B
    } else {
      val (refillCleared, deferredIndex, deferredTag, deferredValid) =
        AliasScanState.get
      when(RefillAliasEvent) {
        refillCleared := false.B
      }
      when(AliasScanStatus.refillCleared) {
        refillCleared := true.B
      }
      // 队列溢出会丢弃 store 事件；在飞 refill 的数据可能早于这些
      // store，必须抑制本次安装并清掉挂起行。
      when(AliasScanStatus.overflow) {
        refillCleared := false.B
        deferredValid := false.B
        suppressFill := true.B
        RefillWordValid := 0.U
        valid(ReqIndexSafe) := false.B
      }
      // store 已把新值写到内存；挂起的新行若与 store 同一物理行，其
      // refill 数据可能早于 store，同一拍也不能补装。
      val StoreHitsDeferred =
        io.StoreValid && StoreCacheable && deferredValid &&
          StoreTag === deferredTag
      when(
        deferredValid && (refillCleared || AliasScanStatus.refillCleared) &&
          !AliasScanStatus.overflow && !StoreHitsDeferred &&
          !suppressFill && !io.flush
      ) {
        valid(deferredIndex) := true.B
        deferredValid := false.B
      }
      when(StoreHitsDeferred) {
        deferredValid := false.B
      }
      (refillCleared || AliasScanStatus.refillCleared) &&
        !AliasScanStatus.overflow && !StoreHitsDeferred
    }
  AliasDirectory.foreach { dir =>
    dir.io.install := RefillAliasEvent
    dir.io.installLine := ReqTagReg
    dir.io.installSet := ReqIndexSafe
    // store 无论命中与否都查目录：命中保留目标 set 并原地更新镜像，
    // 未命中/失败 store 失效其它 set 的旧副本。
    dir.io.invalidateLine := io.StoreValid && StoreCacheable
    dir.io.invalidateLineAddr := StoreTag
    dir.io.keepSet := StoreHit && io.StoreResp === 0.U
    dir.io.keepSetIndex := StoreIndexSafe
    dir.io.invalidateLine2 := false.B
    dir.io.invalidateLine2Addr := 0.U
    // DMA 地址级 snoop 按物理行范围清目录项；Cache valid 由下面的
    // DmaSnoopHitVec/refill 抑制路径同步清除。
    dir.io.invalidateRange := DmaSnoopActive.B && io.dma_snoop_valid
    dir.io.invalidateRangeStart := DmaStartLine
    dir.io.invalidateRangeEnd := DmaEndLine
    dir.io.release := false.B
    dir.io.releaseSet := 0.U
    dir.io.clear := io.flush
  }
  // 同拍命中 store 会在此沿才写入阵列/镜像：SIdle 命中路径也必须反压
  // 同线 load 一拍，否则 Reg 实现会锁存 store 之前的旧值（SRAM 实现因
  // 读口打拍而恰好看到新值），两实现语义不一致。
  // VIPT 缺少 PA 时只允许在空闲且无回填时受理；等待期间不再接新请求。
  val ViptAcceptable = !ViptNeedWait || (state === SIdle && !RefillActive)
  io.req.ready := ResponseSlotReady && !io.flush && !io.req_kill &&
    !(DmaSnoopActive.B && io.dma_snoop_valid) && !DmaReplayWait &&
    !WaitPaddr && ViptAcceptable && !AliasScanBusy &&
    !AliasDirectoryInstallBusy &&
    (state === SIdle || (HitUnderRefill && !ViptNeedWait)) &&
    !StoreConflictsRequest
  // 性能计数器: 已返回的MSHR字按hit计数，不应伪造第二次cache miss。
  // VIPT 未拿到 PA 时推迟到 resolve 拍再统计，避免把“尚未翻译”误报成 miss。
  io.perf_hit := (io.req.fire && !ViptNeedWait && AcceptedHit) ||
    (ResolveVaddrMatch && ResolveHit)
  io.perf_miss := (io.req.fire && !ViptNeedWait && cacheable && !AcceptedHit) ||
    (ResolveVaddrMatch && ResolveCacheable && !ResolveHit)

  when(io.StoreValid && StoreCacheable) {
    // 需求请求可能已在其缓存行仍在到达时提前重启。若更年轻store此时完成，
    // 即使BRESP报错也不能排除部分副作用；剩余读拍都可能已过期，因此应完成
    // 排空但绝不安装此次回填。
    when(StoreConflictsRefill) {
      suppressFill := true.B
      RefillWordValid := 0.U
      valid(StoreIndexSafe) := false.B
    }
    when(StoreHit) {
      when(io.StoreResp === 0.U) {
        // 成功的写直达 store 直接更新缓存镜像。RV64 的 8 字节 store
        // 同时更新低/高两个相邻 word；低 32 位掩码始终来自 StoreData
        // 的低半部分，高半部分仅在高 4 位字节使能非零时写入。
        val LowMask = StoreMaskWord(io.StoreStrb(3, 0))
        val LowData = io.StoreData(31, 0)
        if (WordsPerBlock > 1) {
          data(StoreIndexSafe)(StoreOffset) :=
            (data(StoreIndexSafe)(StoreOffset) & ~LowMask) |
              (LowData & LowMask)
        } else {
          data(StoreIndexSafe)(0) :=
            (data(StoreIndexSafe)(0) & ~LowMask) | (LowData & LowMask)
        }
        if (DataWidth == 64) {
          when(io.StoreStrb(7, 4) =/= 0.U) {
            val HighMask = StoreMaskWord(io.StoreStrb(7, 4))
            val HighData = io.StoreData(63, 32)
            if (WordsPerBlock > 1) {
              data(StoreIndexSafe)(NextOffset(StoreOffset)) :=
                (data(StoreIndexSafe)(NextOffset(StoreOffset)) & ~HighMask) |
                  (HighData & HighMask)
            } else {
              data(StoreIndexSafe)(0) :=
                (data(StoreIndexSafe)(0) & ~HighMask) | (HighData & HighMask)
            }
          }
        }
      }.otherwise {
        // SLVERR/DECERR不保证从机完全没有副作用。不能把失败store的数据
        // 投机写入cache，但也不能继续保留可能已经过期的旧缓存行。
        valid(StoreIndexSafe) := false.B
      }
    }
  }
  // 写直达 store 完成后内存已是新值：同一物理行的其他 alias 副本（即使
  // store 本身按 VA index 没有命中）都必须失效。成功命中时保留 StoreIndex
  // 这份并由上面的写镜像更新；失败 store 则连命中行一起失效。
  if (AliasAllSets) {
    for (i <- valid.indices) {
      val KeepTarget = StoreHit && io.StoreResp === 0.U &&
        StoreIndexSafe === i.U
      when(io.StoreValid && StoreCacheable && valid(i) &&
          tag(i) === StoreTag && !KeepTarget) {
        valid(i) := false.B
      }
    }
  }
  // Clear the old response before installing a replacement below.  The order
  // is important for a hit accepted in the same cycle as io.resp.fire: the new
  // response must remain valid for the following cycle.
  when(io.resp.fire) {
    ResponseValid := false.B
    DemandResponseDone := true.B
  }
  when(io.req.fire) {
    ResponseAddrReg := io.req.bits.addr
    ResponseWidthReg := io.req.bits.WidthSelect
    ResponseSignedReg := io.req.bits.signed
    ResponseCacheableReg := cacheable
    when(HitUnderRefill) {
      // 此请求的数据已经在先前R拍写入阵列。不要覆盖ReqIndex/Tag/Base等
      // MSHR元数据，也不要改变正在进行的AR/R状态。
      ResponseData := FormatLoad(
        data(IndexSafe)(WordOffset),
        (if (WordsPerBlock > 1) data(IndexSafe)(NextOffset(WordOffset))
         else 0.U(32.W)),
        io.req.bits.addr,
        io.req.bits.WidthSelect,
        io.req.bits.signed
      )
      ResponseFault := false.B
      ResponseFaultResp := 0.U
      ResponseValid := true.B
    }.elsewhen(ViptNeedWait) {
      // VIPT：PA 尚未带正确 VA 标签返回。受理拍只锁存 VA 选出的
      // index/offset 和早读数据，绝不判定命中、发 AR 或提交故障响应。
      WaitPaddr := true.B
      WaitVaddrReg := io.req.bits.vaddr
      WaitIndexReg := index
      WaitOffsetReg := WordOffset
      WaitWidthReg := io.req.bits.WidthSelect
      WaitSignedReg := io.req.bits.signed
      WaitDataReg :=
        (if (WordsPerBlock > 1) data(IndexSafe)(WordOffset)
         else data(IndexSafe)(0))
      WaitDataHiReg :=
        (if (WordsPerBlock > 1) data(IndexSafe)(NextOffset(WordOffset))
         else 0.U(32.W))
    }.otherwise {
      ReqAddrReg := io.req.bits.addr
      ReqWidthReg := io.req.bits.WidthSelect
      ReqSignedReg := io.req.bits.signed
      ReqIndexReg := index
      ReqTagReg := ReqTag
      ReqOffsetReg := WordOffset
      WideBeat := 0.U
      WideLowFault := false.B
      ResponseFault := false.B
      ResponseFaultResp := 0.U
      ResponseValid := false.B
      DemandResponseDone := false.B
      suppressFill := false.B
      when(hit) {
        if (WordsPerBlock > 1) {
          ResponseData := FormatLoad(
            data(IndexSafe)(WordOffset),
            data(IndexSafe)(NextOffset(WordOffset)),
            io.req.bits.addr,
            io.req.bits.WidthSelect,
            io.req.bits.signed
          )
        } else {
          ResponseData := FormatLoad(
            data(IndexSafe)(0),
            0.U(32.W),
            io.req.bits.addr,
            io.req.bits.WidthSelect,
            io.req.bits.signed
          )
        }
        ResponseValid := true.B
        state := SIdle
      }.otherwise {
        RefillActive := cacheable
        RefillBurst := cacheable
        RefillCount := 0.U
        RefillError := false.B
        RefillWordValid := 0.U
        RefillBaseReg := Cat(
          io.req.bits.addr(AddressWidth - 1, BlockSizeLog2),
          0.U(BlockSizeLog2.W)
        )
        when(cacheable) { valid(IndexSafe) := false.B }
        state := SReadReq
      }
    }
  }
  // 迟到的 PA 与受理时 VA 标签匹配：现在才用物理 tag 判定命中/缺失。
  // 命中数据直接来自受理拍用 VA index 早读的字；缺失则用解析出的 PA
  // 启动 refill，安装位置仍是 VA index。
  when(ResolveVaddrMatch) {
    WaitPaddr := false.B
    ResponseAddrReg := ResolvePaddr
    ResponseWidthReg := WaitWidthReg
    ResponseSignedReg := WaitSignedReg
    ResponseCacheableReg := ResolveCacheable
    when(ResolveHit) {
      ResponseData := FormatLoad(
        WaitDataReg,
        WaitDataHiReg,
        WaitVaddrReg,
        WaitWidthReg,
        WaitSignedReg
      )
      ResponseFault := false.B
      ResponseFaultResp := 0.U
      ResponseValid := true.B
      DemandResponseDone := true.B
    }.otherwise {
      ReqAddrReg := ResolvePaddr
      ReqWidthReg := WaitWidthReg
      ReqSignedReg := WaitSignedReg
      ReqIndexReg := WaitIndexReg
      ReqTagReg := ResolveTag
      ReqOffsetReg := WaitOffsetReg
      WideBeat := 0.U
      WideLowFault := false.B
      ResponseFault := false.B
      ResponseFaultResp := 0.U
      ResponseValid := false.B
      DemandResponseDone := false.B
      suppressFill := false.B
      RefillActive := ResolveCacheable
      RefillBurst := ResolveCacheable
      RefillCount := 0.U
      RefillError := false.B
      RefillWordValid := 0.U
      RefillBaseReg := Cat(
        ResolvePaddr(AddressWidth - 1, BlockSizeLog2),
        0.U(BlockSizeLog2.W)
      )
      when(ResolveCacheable) { valid(ResolveIndexSafe) := false.B }
      state := SReadReq
    }
  }
  // MMU 翻译故障杀死等待中的请求：不能留下响应或总线事务。
  when(io.req_kill) {
    WaitPaddr := false.B
  }
  switch(state) {
    is(SReadReq) {
      io.AXI.AR.ARVALID := true.B
      io.AXI.AR.ARADDR := Mux(
        RefillActive,
        RefillBaseReg + Cat(RefillCount, 0.U(2.W)),
        ReqAddrReg
      )
      io.AXI.AR.ARLEN := Mux(
        RefillActive && RefillBurst,
        (WordsPerBlock - 1).U,
        Mux(!RefillActive && ReqWidthReg === 3.U, 1.U, 0.U)
      )
      // 32 位 AXI 数据通道上，8 字节非缓存访问拆成两拍 ARSIZE=2。
      io.AXI.AR.ARSIZE := Mux(
        RefillActive || ReqWidthReg === 3.U,
        2.U,
        ReqWidthReg
      )
      // AXI要求ARVALID一旦提出就保持到握手。即使flush到来，也继续完成
      // 地址和R通道事务；响应仍交付demand，但不安装该次回填的cache line。
      when(io.AXI.AR.ARREADY) {
        // 缓存行在 AR 被接受时安装：清除其他 set 中同物理行的旧副本。
        // 若本次 refill 已被 store/flush 抑制，则不再改动别名副本。
        when(RefillActive && !suppressFill && !io.flush) {
          InvalidateAliasCopies(ReqTagReg, ReqIndexSafe)
        }
        state := SReadResp
      }
    }
    is(SReadResp) {
      io.AXI.R.RREADY := true.B
      when(io.AXI.R.RVALID) {
        when(RefillActive) {
          val BeatError = io.AXI.R.RRESP =/= 0.U
          val KeywordBeat = RefillCount === ReqOffsetReg
          val ReqWide = ReqWidthReg === 3.U
          val WideHighBeat = ReqWide &&
            RefillCount === NextOffset(ReqOffsetReg)
          val ResponseBeat = KeywordBeat || WideHighBeat
          when(BeatError) {
            RefillError := true.B
            RefillWordValid := 0.U
            when(ResponseBeat && !DemandResponseDone && !ResponseValid) {
              ResponseFault := true.B
              ResponseFaultResp := io.AXI.R.RRESP
              ResponseValid := true.B
            }
          }
          when(!BeatError) {
            // flush期间或之后返回的数据可以满足需求load，但不得重新填充
            // 已失效的缓存行。
            when(
              !RefillError && !suppressFill && !io.flush &&
                !StoreConflictsRefill
            ) {
              tag(ReqIndexSafe) := ReqTagReg
              if (WordsPerBlock > 1) {
                data(ReqIndexSafe)(RefillCount) := io.AXI.R.RDATA
              } else {
                data(ReqIndexSafe)(0) := io.AXI.R.RDATA
              }
              RefillWordValid :=
                RefillWordValid | UIntToOH(RefillCount, WordsPerBlock)
            }
            when(KeywordBeat && !DemandResponseDone) {
              when(ReqWide) {
                // 8 字节 load 关键词只是低 word；等下一拍高 word 到齐后再
                // 形成响应，避免把半条数据提前交给 LSU。
                WideLowReg := io.AXI.R.RDATA
              }.otherwise {
                ResponseData := FormatLoad(
                  io.AXI.R.RDATA,
                  0.U(32.W),
                  ReqAddrReg,
                  ReqWidthReg,
                  ReqSignedReg
                )
                ResponseFault := false.B
                ResponseFaultResp := 0.U
                ResponseValid := true.B
              }
            }
            when(WideHighBeat && !DemandResponseDone && !ResponseValid &&
                !RefillError && !BeatError) {
              ResponseData := FormatLoad(
                WideLowReg,
                io.AXI.R.RDATA,
                ReqAddrReg,
                ReqWidthReg,
                ReqSignedReg
              )
              ResponseFault := false.B
              ResponseFaultResp := 0.U
              ResponseValid := true.B
            }
          }
          when(LastRefillWord) {
            when(io.AXI.R.RLAST) {
              val RefillInstallOk =
                !suppressFill && !io.flush && !StoreConflictsRefill &&
                  !RefillError && !BeatError
              when(RefillInstallOk) {
                // serial/parallel 扫描尚未清完旧副本时先挂起，扫描结束
                // 后再置 valid；扫描窗口内不出现两份有效副本。
                when(AliasInstallAllowed) {
                  valid(ReqIndexSafe) := true.B
                }.elsewhen(!AliasScanStatus.overflow) {
                  if (AliasScanActive) {
                    val (_, deferredIndex, deferredTag, deferredValid) =
                      AliasScanState.get
                    deferredValid := true.B
                    deferredIndex := ReqIndexSafe
                    deferredTag := ReqTagReg
                  }
                }
              }.otherwise {
                valid(ReqIndexSafe) := false.B
              }
              RefillActive := false.B
              RefillBurst := false.B
              RefillWordValid := 0.U
              suppressFill := false.B
              state := SIdle
            }.otherwise {
              // 预期的最后一拍到达时没有RLAST。仲裁器仍持有此响应期间，
              // 不得让load退休；应使缓存行失效并持续排空，直到迟到的RLAST出现。
              valid(ReqIndexSafe) := false.B
              RefillError := true.B
              RefillWordValid := 0.U
              // 绝不修改或重新产生已经对外可见的Decoupled响应。若关键字先前已被
              // 消费，这个迟到的协议错误只会使缓存行失效并将其排空。
              when(!DemandResponseDone && !ResponseValid) {
                ResponseFault := true.B
                ResponseFaultResp := Mux(BeatError, io.AXI.R.RRESP, 2.U)
                ResponseValid := true.B
              }
              state := SDrain
            }
          }.elsewhen(RefillBurst) {
            when(io.AXI.R.RLAST) {
              // RLAST提前到达：保留已接收的数据字，并将每个剩余数据字作为
              // 独立的单拍事务获取。
              RefillBurst := false.B
              RefillCount := RefillCount + 1.U
              state := SReadReq
            }.otherwise {
              RefillCount := RefillCount + 1.U
            }
          }.otherwise {
            // 退化请求只有一拍，因此必须以RLAST结束。
            when(io.AXI.R.RLAST) {
              RefillCount := RefillCount + 1.U
              state := SReadReq
            }.otherwise {
              valid(ReqIndexSafe) := false.B
              RefillError := true.B
              RefillWordValid := 0.U
              when(!DemandResponseDone && !ResponseValid) {
                ResponseFault := true.B
                ResponseFaultResp := Mux(BeatError, io.AXI.R.RRESP, 2.U)
                ResponseValid := true.B
              }
              state := SDrain
            }
          }
        }.otherwise {
          when(ReqWidthReg === 3.U) {
            // 非缓存 8 字节读：两拍窄传输，低 word 先到。
            when(WideBeat === 0.U) {
              when(io.AXI.R.RLAST) {
                ResponseFault := true.B
                ResponseFaultResp := Mux(
                  io.AXI.R.RRESP =/= 0.U,
                  io.AXI.R.RRESP,
                  2.U
                )
                ResponseValid := true.B
                state := SIdle
              }.otherwise {
                WideLowReg := io.AXI.R.RDATA
                WideLowFault := io.AXI.R.RRESP =/= 0.U
                WideBeat := 1.U
              }
            }.otherwise {
              when(io.AXI.R.RLAST) {
                when(WideLowFault || io.AXI.R.RRESP =/= 0.U) {
                  ResponseFault := true.B
                  ResponseFaultResp := Mux(
                    io.AXI.R.RRESP =/= 0.U,
                    io.AXI.R.RRESP,
                    2.U
                  )
                }.otherwise {
                  ResponseData := FormatLoad(
                    WideLowReg,
                    io.AXI.R.RDATA,
                    ReqAddrReg,
                    ReqWidthReg,
                    ReqSignedReg
                  )
                }
                ResponseValid := true.B
                suppressFill := false.B
                state := SIdle
              }.otherwise {
                ResponseFault := true.B
                ResponseFaultResp := Mux(
                  io.AXI.R.RRESP =/= 0.U,
                  io.AXI.R.RRESP,
                  2.U
                )
                state := SDrain
              }
            }
          }.otherwise {
            when(io.AXI.R.RRESP =/= 0.U) {
              ResponseFault := true.B
              ResponseFaultResp := io.AXI.R.RRESP
            }.otherwise {
              ResponseData := FormatLoad(
                io.AXI.R.RDATA,
                0.U(32.W),
                ReqAddrReg,
                ReqWidthReg,
                ReqSignedReg
              )
            }
            ResponseValid := true.B
            when(io.AXI.R.RLAST) {
              suppressFill := false.B
              state := SIdle
            }.otherwise {
              // 每个非缓存请求都是单拍AXI事务。缺少RLAST属于协议故障，必须先将其
              // 排空，另一个LSU读请求才能复用固定的读ID。
              ResponseFault := true.B
              ResponseFaultResp := Mux(
                io.AXI.R.RRESP =/= 0.U,
                io.AXI.R.RRESP,
                2.U
              )
              state := SDrain
            }
          }
        }
      }
    }
    is(SDrain) {
      io.AXI.R.RREADY := true.B
      when(io.AXI.R.RVALID && io.AXI.R.RLAST) {
        RefillActive := false.B
        RefillBurst := false.B
        RefillWordValid := 0.U
        suppressFill := false.B
        state := SIdle
      }
    }
  }
  // DMA 地址级 snoop 必须放在所有 refill/store 写回之后：同一拍 refill
  // 刚把旧行安装为 valid 时，这里的失效写回优先级更高。
  if (DmaSnoopActive) {
    // 在旧 refill 排空、状态回 SIdle 后，按原访问重新发起一次读取。
    // 此时 DMA 的 B 已经到达（下游写完成），新读取必然看到新数据。
    when(DmaReplayWait && state === SIdle && !WaitPaddr && !io.flush) {
      val ReplayLine =
        Cat(DmaReplayAddr(AddressWidth - 1, BlockSizeLog2),
          0.U(BlockSizeLog2.W))
      ReqAddrReg := DmaReplayAddr
      ReqWidthReg := DmaReplayWidth
      ReqSignedReg := DmaReplaySigned
      ReqIndexReg :=
        DmaReplayAddr(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
      ReqTagReg := TagOf(DmaReplayAddr)
      ReqOffsetReg :=
        (if (WordsPerBlock > 1) DmaReplayAddr(BlockSizeLog2 - 1, 2)
         else 0.U(OffsetWidth.W))
      RefillActive := DmaReplayCacheable
      RefillBurst := DmaReplayCacheable
      RefillCount := 0.U
      RefillError := false.B
      RefillWordValid := 0.U
      RefillBaseReg := ReplayLine
      ResponseValid := false.B
      DemandResponseDone := false.B
      suppressFill := false.B
      state := SReadReq
      DmaReplayWait := false.B
    }
    val DmaSnoopHit = DmaSnoopHitVec
    for (set <- valid.indices) {
      when(io.dma_snoop_valid && DmaSnoopHit(set)) {
        valid(set) := false.B
      }
    }
    when(io.dma_snoop_valid && DmaSnoopRefillLineHit) {
      // 旧 burst 的剩余 R beat 仍会到达，但它们既不能安装也不能形成响应。
      suppressFill := true.B
      RefillWordValid := 0.U
      valid(ReqIndexSafe) := false.B
    }
    // 放在 refill RLAST 之后：同拍 RLAST 可能刚把新行挂到 deferred，
    // 或被本拍 DMA snoop 抑制，必须立刻取消，扫描结束后不得补装。
    if (AliasScanActive) {
      val (_, deferredIndex, deferredTag, deferredValid) =
        AliasScanState.get
      when(io.dma_snoop_valid && deferredValid &&
          DmaLineInRange(deferredTag)) {
        valid(deferredIndex) := false.B
        deferredValid := false.B
      }
      when(io.dma_snoop_valid && DmaSnoopRefillLineHit) {
        deferredValid := false.B
      }
    }
    when(io.dma_snoop_valid && DmaSnoopReplay) {
      DmaReplayWait := true.B
      DmaReplayAddr := ResponseAddrReg
      DmaReplayWidth := ResponseWidthReg
      DmaReplaySigned := ResponseSignedReg
      DmaReplayCacheable := ResponseCacheableReg
      ResponseValid := false.B
    }
  }
  // 目录失效放在 refill/store valid 写回之后：目录冲突挤出的旧行或
  // store/DMA snoop 必须压掉同拍 refill 最后一拍的 valid 置位。
  AliasDirectory.foreach { dir =>
    for (set <- valid.indices) {
      when(dir.io.invalidate(set)) {
        valid(set) := false.B
      }
    }
  }
  when(io.flush) { // fence.i/外部全局一致性/原子提交：清空全部行并取消 snoop 重放
    valid.foreach(_ := false.B)
    RefillWordValid := 0.U
    DmaReplayWait := false.B
    if (AliasScanActive) {
      val (refillCleared, _, _, deferredValid) = AliasScanState.get
      deferredValid := false.B
      refillCleared := false.B
    }
    when(state =/= SIdle) { suppressFill := true.B }
  }
}
