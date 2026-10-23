package ysyx_26030103.cache

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.infra._

// 单请求直映射DCache，读通道回填，写通道由LSU写缓冲负责
class ysyx_26030103_DCacheSRAM(
    Enable: Boolean = true,
    BlockSizeLog2: Int = 4,
    IndexBits: Int = 5,
    AddressWidth: Int = 32,
    DataWidth: Int = 32,
    Vipt: Boolean = false,
    // index 含 VPN 位时，同一物理行可被不同 VA 映射到不同 set；refill
    // 安装和写直达 store 完成时按物理 tag 失效其他 set 的副本。
    ViptAliasSafe: Boolean = false,
    // alias 失效扫描方式：all_sets 单拍全扫描；serial 每周期扫描一个 set，
    // 扫描期间反压新 load，store 事件进入小队列等待扫描。
    ViptAliasScanMode: String = "all_sets",
    // DMA 地址级 snoop：只有 DMA_COHERENCE=hardware_snoop 才生成行失效
    // 与在途 refill 等待硬件，默认配置完全保留原有时序。
    DmaSnoop: Boolean = false,
    CacheableBase: Long = 0x80000000L,
    CacheableMask: Long = 0x80000000L,
    // true=命中响应第一拍旁路SRAM输出(保持1拍延迟);
    // false=老实打一拍(响应晚一拍), 用于量化"不旁路"的性能代价
    Bypass: Boolean = true,
    PMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false)
) extends ysyx_26030103_DCacheBase(AddressWidth, DataWidth) {
  ysyx_26030103_ViptAliasScan.requireMode(ViptAliasScanMode, "DCacheSRAM")
  private val AliasScanSerial =
    ViptAliasSafe && Enable &&
      ysyx_26030103_ViptAliasScan.isSerial(ViptAliasScanMode)
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
  private val ArrayBlocks = if (Enable) NumBlocks else 1
  private def NextOffset(offset: UInt): UInt =
    (offset + 1.U)(CountWidth - 1, 0)
  private val ReadPorts = if (DataWidth == 64) 2 else 1
  private val WritePorts = if (DataWidth == 64) 3 else 2
  // 一致性 flush 是 DCache 自身的可验证接口；生产顶层已由 DMA 写 B
  // 握手的失效脉冲驱动（ysyx_26030103.scala），dontTouch 保证该端口在
  // 叶模块 elaboration 中不被裁掉。
  dontTouch(io.flush)
  val valid = RegInit(VecInit(Seq.fill(ArrayBlocks)(false.B)))
  val tag = Reg(Vec(ArrayBlocks, UInt(TagBits.W)))
  // VIPT alias 维护：同一物理行只保留一份。refill 安装新行、写直达 store
  // 完成后扫描 tag 阵列，失效其他 set 中的同物理行副本。扫描仅在 alias
  // 危险几何且显式打开 MMU_CACHE_VIPT_ALIAS_SAFE 时生成。
  private def InvalidateAliasCopies(AliasTag: UInt, TargetIndex: UInt): Unit = {
    if (ViptAliasSafe && Enable && !AliasScanSerial) {
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
  // data改为同步读SRAM：单块NumBlocks×WordsPerBlock的32位SRAM，1读口+2写口
  // (refill填充、store镜像更新)，写口自带字节掩码。SRAM.masked配合
  // UseSRAMBlackbox由Chisel直接生成Verilog(内联黑盒)，不经过CIRCT的
  // memory降低，因此多写口的字节掩码不会被丢弃。
  // valid/tag留在触发器阵列（小、需要复位、判定命中不占端口）。
  val sram = SRAM.masked(NumBlocks * WordsPerBlock, Vec(4, UInt(8.W)), ReadPorts, WritePorts, 0)
  // SRAM字地址：组索引与块内word偏移拼成线性地址。
  private def WordAddress(BlockIndex: UInt, WordOffset: UInt): UInt =
    if (WordsPerBlock > 1) Cat(BlockIndex, WordOffset) else BlockIndex
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
  // DMA snoop 与 CPU 请求同拍时，失效必须优先于命中。
  private val DmaReqLineHit = DmaSnoopActive.B && io.dma_snoop_valid &&
    DmaLineInRange(io.req.bits.addr(AddressWidth - 1, BlockSizeLog2))
  val hit =
    cacheable && valid(IndexSafe) && tag(IndexSafe) === ReqTag &&
      !DmaReqLineHit // 这个直接拿来做一个命中条件
  // VIPT 等待 PA 的请求状态。SRAM 读口在受理拍就用 VA index 提前发起，
  // 数据下一拍锁存到 WaitDataReg；PA 标签匹配后若 tag 仍命中即可返回。
  val WaitPaddr = RegInit(false.B)
  val WaitVaddrReg = Reg(UInt(AddressWidth.W))
  val WaitIndexReg = Reg(UInt(IndexWidth.W))
  val WaitOffsetReg = Reg(UInt(CountWidth.W))
  val WaitWidthReg = Reg(UInt(2.W))
  val WaitSignedReg = Reg(Bool())
  val WaitDataReg = Reg(UInt(32.W))
  val WaitDataHiReg = Reg(UInt(32.W))
  val WaitDataValid = RegInit(false.B)
  val WaitSramRead = RegInit(false.B)
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
  val WideLowReg = RegInit(0.U(32.W))
  val WideBeat = RegInit(0.U(1.W))
  val WideLowFault = RegInit(false.B)
  val ResponseFault = RegInit(false.B)
  val ResponseFaultResp = RegInit(0.U(2.W))
  val ResponseValid = RegInit(false.B)
  val DemandResponseDone = RegInit(false.B)
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
  // 写口0(refill填充): 使能默认拉低, 在R拍填充路径里置高; 数据/掩码常连。
  sram.writePorts(0).address := WordAddress(ReqIndexSafe, RefillCount)
  sram.writePorts(0).data := VecInit(
    io.AXI.R.RDATA(7, 0),
    io.AXI.R.RDATA(15, 8),
    io.AXI.R.RDATA(23, 16),
    io.AXI.R.RDATA(31, 24)
  )
  sram.writePorts(0).mask.get := VecInit(true.B, true.B, true.B, true.B)
  sram.writePorts(0).enable := false.B
  // 与 Reg 版保持同一套 load 格式化语义：0/1/2 字节/半字/字，
  // 64 位 DCache 下 width=3 直接拼接两个相邻 word。
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
  // SRAM命中数据晚一拍：受理命中(含refill中partial hit)时发起读。Bypass模式
  // 下一拍由SramRespPending旁路到响应并同时锁存；非旁路模式下一拍锁存、再下一
  // 拍才作为响应。SramRespPending与ResponseValid互斥(前者是"SRAM数据本拍到"的拍)。
  val SramRespPending = RegInit(false.B)
  val SramReqAddr = Reg(UInt(AddressWidth.W))
  val SramReqWidth = Reg(UInt(2.W))
  val SramReqSigned = Reg(Bool())
  // 当前响应对应的完整物理访问；DMA snoop 用它判断旧响应归属，并在需要
  // 时按原访问重新发起读取。SRAM 命中响应可能来自另一个请求，不能只看
  // ReqAddrReg。
  val ResponseAddrReg = Reg(UInt(AddressWidth.W))
  val ResponseWidthReg = Reg(UInt(2.W))
  val ResponseSignedReg = Reg(Bool())
  val ResponseCacheableReg = RegInit(false.B)
  val DmaReplayWait = RegInit(false.B)
  val DmaReplayAddr = Reg(UInt(AddressWidth.W))
  val DmaReplayWidth = Reg(UInt(2.W))
  val DmaReplaySigned = Reg(Bool())
  val DmaReplayCacheable = RegInit(false.B)
  val ResponseOccupied = ResponseValid || SramRespPending
  // 非旁路模式响应要等数据锁存后（晚两拍）才可见，槽位只能在真实 fire
  // 时释放；否则 SramRespPending 期间（响应尚不可见）也会放行新请求，
  // 槽被超额订阅，数据到达拍的置位会被同拍 fire 的清除覆盖，响应静默丢失。
  val ResponseSlotReady =
    !ResponseOccupied || (if (Bypass) io.resp.ready else io.resp.fire)
  if (!Bypass) {
    assert(
      !(SramRespPending && io.resp.fire),
      "DCache non-bypass response slot over-subscribed"
    )
  }
  // DMA snoop 握手：匹配行还有在途 R beat 或尚未被 CPU 取走的旧响应时，
  // ready 保持为低，顶层据此继续扣住 DMA B。SReadReq 阶段的 AR 尚未被
  // 接受，此时读到的已经是 DMA 完成后的内存，不需要等待。
  private val DmaSnoopRespValid = ResponseValid || SramRespPending
  private val DmaSnoopResponseAddr =
    Mux(SramRespPending, SramReqAddr, ResponseAddrReg)
  private val DmaSnoopRefillLineHit = DmaSnoopActive.B && RefillActive &&
    (state === SReadResp || state === SDrain) &&
    DmaLineInRange(RefillBaseReg(AddressWidth - 1, BlockSizeLog2))
  private val DmaSnoopRespMatch = DmaSnoopActive.B && DmaSnoopRespValid &&
    DmaLineInRange(DmaSnoopResponseAddr(AddressWidth - 1, BlockSizeLog2))
  private val DmaSnoopRespReplay = DmaSnoopRespMatch &&
    ResponseCacheableReg && !DemandResponseDone
  private val DmaSnoopRefillOwnDemand = DmaSnoopRefillLineHit &&
    ResponseCacheableReg &&
    DmaSnoopResponseAddr(AddressWidth - 1, BlockSizeLog2) ===
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
  // 响应状态与回填状态解耦，使关键字能在缓存行其余数据到达前重启流水线。
  // 被 DMA snoop 命中的旧响应不能交付：本拍压掉 valid，下一拍重新发起
  // 读取（此时内存已包含 DMA 新数据）。
  private val DmaDropResponse = DmaSnoopActive.B && io.dma_snoop_valid &&
    DmaSnoopRespReplay
  io.resp.valid := (if (Bypass) ResponseOccupied else ResponseValid) &&
    !DmaDropResponse && !DmaReplayWait
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
  // 写口1(store镜像更新): 使能默认拉低, 在BRESP成功的store路径里置高;
  // 掩码直接接字节使能, 与原先的读改写等价。
  sram.writePorts(1).address := WordAddress(StoreIndexSafe, StoreOffset)
  sram.writePorts(1).data := VecInit(
    io.StoreData(7, 0),
    io.StoreData(15, 8),
    io.StoreData(23, 16),
    io.StoreData(31, 24)
  )
  sram.writePorts(1).mask.get := VecInit(
    io.StoreStrb(0),
    io.StoreStrb(1),
    io.StoreStrb(2),
    io.StoreStrb(3)
  )
  sram.writePorts(1).enable := false.B
  if (DataWidth == 64) {
    // 8 字节 store 的高 word 使用独立写口，避免与低 word 争用同一
    // SRAM 地址端口。
    sram.writePorts(2).address :=
      WordAddress(StoreIndexSafe, NextOffset(StoreOffset))
    sram.writePorts(2).data := VecInit(
      io.StoreData(39, 32),
      io.StoreData(47, 40),
      io.StoreData(55, 48),
      io.StoreData(63, 56)
    )
    sram.writePorts(2).mask.get := VecInit(
      io.StoreStrb(4),
      io.StoreStrb(5),
      io.StoreStrb(6),
      io.StoreStrb(7)
    )
    sram.writePorts(2).enable := false.B
  }
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
  // serial alias 扫描的事件源：refill 安装与写直达 store 未命中。
  // store 命中时该行已是唯一副本，原地更新即可，不需要扫描。
  val RefillAliasEvent = state === SReadReq && io.AXI.AR.ARREADY &&
    RefillActive && !suppressFill && !io.flush
  val StoreAliasEvent = io.StoreValid && StoreCacheable && !StoreHit
  // 扫描/事件排队期间反压新 load；all_sets 模式恒为 false。
  val AliasScanBusy =
    if (AliasScanSerial) {
      ysyx_26030103_ViptAliasScan.serialEventQueueScan(
        valid,
        tag,
        RefillAliasEvent,
        ReqTagReg,
        ReqIndexSafe,
        StoreAliasEvent,
        StoreTag,
        io.flush
      )
    } else {
      false.B
    }
  // 同拍命中 store 会在此沿才写入阵列/镜像：SIdle 命中路径也必须反压
  // 同线 load 一拍，否则 Reg 实现会锁存 store 之前的旧值（SRAM 实现因
  // 读口打拍而恰好看到新值），两实现语义不一致。
  // VIPT 缺少 PA 时只允许在空闲且无回填时受理；等待期间不再接新请求。
  val ViptAcceptable = !ViptNeedWait || (state === SIdle && !RefillActive)
  io.req.ready := ResponseSlotReady && !io.flush && !io.req_kill &&
    !(DmaSnoopActive.B && io.dma_snoop_valid) && !DmaReplayWait &&
    !WaitPaddr && ViptAcceptable && !AliasScanBusy &&
    (state === SIdle || (HitUnderRefill && !ViptNeedWait)) &&
    !StoreConflictsRequest
  // 性能计数器: 已返回的MSHR字按hit计数，不应伪造第二次cache miss。
  // VIPT 未拿到 PA 时推迟到 resolve 拍再统计（放在 ResolveHit 定义之后）。
  // SRAM读口: SIdle命中与refill中partial hit都满足req.fire && AcceptedHit,
  // 都读当前请求地址的字。VIPT 缺 PA 时也用 VA index 提前发起读，
  // 下一拍锁存到 WaitDataReg，供 PA 到达后的 tag 判定使用。
  sram.readPorts(0).address := WordAddress(IndexSafe, WordOffset)
  sram.readPorts(0).enable :=
    (io.req.fire && AcceptedHit) || (io.req.fire && ViptNeedWait)
  val sramReadBytes = sram.readPorts(0).data
  val sramReadWord =
    Cat(sramReadBytes(3), sramReadBytes(2), sramReadBytes(1), sramReadBytes(0))
  val sramReadWordHi =
    if (DataWidth == 64) {
      sram.readPorts(1).address :=
        WordAddress(IndexSafe, NextOffset(WordOffset))
      sram.readPorts(1).enable :=
        (io.req.fire && AcceptedHit) || (io.req.fire && ViptNeedWait)
      val bytes = sram.readPorts(1).data
      Cat(bytes(3), bytes(2), bytes(1), bytes(0))
    } else {
      0.U(32.W)
    }
  // 早读数据在本拍到达时也可以直接参与 resolve。
  val ResolveWord = Mux(WaitSramRead, sramReadWord, WaitDataReg)
  val ResolveWordHi = Mux(WaitSramRead, sramReadWordHi, WaitDataHiReg)
  val ResolveWordValid = WaitDataValid || WaitSramRead
  val ResolveReady = ResolveVaddrMatch && ResolveWordValid
  private val DmaResolveLineHit = DmaSnoopActive.B && io.dma_snoop_valid &&
    DmaLineInRange(ResolvePaddr(AddressWidth - 1, BlockSizeLog2))
  val ResolveHit = ResolveReady && ResolveCacheable &&
    valid(ResolveIndexSafe) && tag(ResolveIndexSafe) === ResolveTag &&
    !DmaResolveLineHit
  io.perf_hit := (io.req.fire && !ViptNeedWait && AcceptedHit) ||
    (ResolveReady && ResolveHit)
  io.perf_miss := (io.req.fire && !ViptNeedWait && cacheable && !AcceptedHit) ||
    (ResolveReady && ResolveCacheable && !ResolveHit)
  when(WaitSramRead) {
    WaitDataReg := sramReadWord
    if (DataWidth == 64) { WaitDataHiReg := sramReadWordHi }
    WaitDataValid := true.B
  }
  // 响应数据源: 旁路模式下SRAM数据到达当拍用刚取回的字, 其它情况用响应寄存器。
  io.resp.bits.data := (if (Bypass) {
    Mux(
      SramRespPending,
      FormatLoad(
        sramReadWord,
        sramReadWordHi,
        SramReqAddr,
        SramReqWidth,
        SramReqSigned
      ),
      ResponseData
    )
  } else { ResponseData })
  when(io.req.fire && AcceptedHit) {
    SramReqAddr := io.req.bits.addr
    SramReqWidth := io.req.bits.WidthSelect
    SramReqSigned := io.req.bits.signed
  }
  SramRespPending := io.req.fire && AcceptedHit
  // VIPT 缺 PA 的受理拍已经提前发起 SRAM 读，下一拍锁存。
  WaitSramRead := io.req.fire && ViptNeedWait
  // SRAM数据到达的当拍: 旁路模式下若同拍被消费则不再锁存;
  // 非旁路模式下锁存并置valid(下一拍响应)。
  when(SramRespPending) {
    ResponseData := FormatLoad(
      sramReadWord,
      sramReadWordHi,
      SramReqAddr,
      SramReqWidth,
      SramReqSigned
    )
    ResponseFault := false.B
    ResponseFaultResp := 0.U
    if (Bypass) {
      when(!io.resp.ready) {
        ResponseValid := true.B
      }
    } else {
      ResponseValid := true.B
    }
  }

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
        // 成功的单拍 store 精确知道新字节值，可以直接维护写直达镜像。
        // 写口1已常连好地址/数据/字节掩码，这里只需放行使能。
        sram.writePorts(1).enable := true.B
        if (DataWidth == 64) {
          when(io.StoreStrb(7, 4) =/= 0.U) {
            sram.writePorts(2).enable := true.B
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
  // 这份并由上面的写口更新；失败 store 则连命中行一起失效。
  if (ViptAliasSafe && Enable && !AliasScanSerial) {
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
      // 此请求的数据已经在先前R拍写入SRAM。不要覆盖ReqIndex/Tag/Base等
      // MSHR元数据，也不要改变正在进行的AR/R状态。响应数据在下一拍
      // 由SRAM输出旁路(与SIdle命中同一条路径)。
      ResponseFault := false.B
      ResponseFaultResp := 0.U
    }.elsewhen(ViptNeedWait) {
      // VIPT：PA 尚未带正确 VA 标签返回。只锁存 VA 选出的
      // index/offset，SRAM 读口已在本拍用 VA index 提前读；不判定命中/
      // 缺失，也不发 AR。
      WaitPaddr := true.B
      WaitVaddrReg := io.req.bits.vaddr
      WaitIndexReg := index
      WaitOffsetReg := WordOffset
      WaitWidthReg := io.req.bits.WidthSelect
      WaitSignedReg := io.req.bits.signed
      WaitDataValid := false.B
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
        // 命中数据由SRAM在下一拍给出, 由SramRespPending旁路。
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
  // 迟到的 PA 与受理时 VA 标签匹配且早读数据已就绪：现在才用物理 tag
  // 判定命中/缺失。命中直接用早读字；缺失用解析出的 PA 启动 refill，
  // 安装位置仍是 VA index。
  when(ResolveReady) {
    WaitPaddr := false.B
    ResponseAddrReg := ResolvePaddr
    ResponseWidthReg := WaitWidthReg
    ResponseSignedReg := WaitSignedReg
    ResponseCacheableReg := ResolveCacheable
    when(ResolveHit) {
      ResponseData := FormatLoad(
        ResolveWord,
        ResolveWordHi,
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
    WaitDataValid := false.B
    WaitSramRead := false.B
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
              // 写口0已常连好地址/数据/全1掩码, 这里只需放行使能。
              sram.writePorts(0).enable := true.B
              RefillWordValid :=
                RefillWordValid | UIntToOH(RefillCount, WordsPerBlock)
            }
            when(KeywordBeat && !DemandResponseDone) {
              when(ReqWide) {
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
              valid(ReqIndexSafe) :=
                !suppressFill && !io.flush && !StoreConflictsRefill &&
                  !RefillError && !BeatError
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
    // 旧 refill 排空、状态回 SIdle 后，按原访问重新发起读取。此时 DMA B
    // 已到达（下游写完成），新读取必然看到新数据。
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
      SramRespPending := false.B
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
      suppressFill := true.B
      RefillWordValid := 0.U
      valid(ReqIndexSafe) := false.B
    }
    when(io.dma_snoop_valid && DmaSnoopReplay) {
      DmaReplayWait := true.B
      DmaReplayAddr := DmaSnoopResponseAddr
      DmaReplayWidth :=
        Mux(SramRespPending, SramReqWidth, ResponseWidthReg)
      DmaReplaySigned :=
        Mux(SramRespPending, SramReqSigned, ResponseSignedReg)
      DmaReplayCacheable := ResponseCacheableReg
      ResponseValid := false.B
      SramRespPending := false.B
    }
  }
  when(io.flush) { // fence.i/外部全局一致性：清空全部行并取消 snoop 重放
    valid.foreach(_ := false.B)
    RefillWordValid := 0.U
    DmaReplayWait := false.B
    when(state =/= SIdle) { suppressFill := true.B }
  }
}
