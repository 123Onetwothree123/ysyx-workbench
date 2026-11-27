package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_RISCVFields
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile

// Sv32 MMU：TLB 查找、miss queue 排队、PTW walk 三级解耦；命中/旁路直接
// 响应，缺失进入 ITLB/DTLB/UTLB 各自的 miss queue（follow_tlb）或共享的
// 全局 miss queue（global），只有队首占用 walk 槽。
// PTW ID 使用独立的 walk 槽编号（0..PTWOutstanding-1）而非上下文编号，
// 上下文数可大于 16 且仍满足 4 位 ID；完成结果按进入序号写回响应表，
// 多队列/多 walker 的乱序完成不会破坏软件可见的响应顺序。
class ysyx_26030103_MMU(
  profile: ysyx_26030103_MMUProfile = ysyx_26030103_MMUProfile())
    extends Module {
  require(profile.PTESizeBytes == 4 || profile.PTESizeBytes == 8, "PTE 大小只支持 4/8 字节")
  require(profile.levels >= 1 &&
    profile.levels <= ysyx_26030103_MMUProfile.MaxLevels,
    s"页表级数必须在 1..${ysyx_26030103_MMUProfile.MaxLevels}")
  require(profile.pageBits + profile.levels * profile.indexBitsPerLevel ==
    profile.computedAddressBits,
    "pageBits + levels*indexBits 必须等于 profile 的有效虚拟地址位数")
  // RV64 默认只接受标准 Sv39/Sv48；非标准页几何必须由 CUSTOM 模式显式
  // 打开，并使用独立的 satp MODE 编码，避免把私有几何当成标准页表格式。
  require(profile.xlen == 32 || profile.customGeometry ||
    profile.standardSv39 || profile.standardSv48,
    "RV64 MMU 只支持 STANDARD Sv39/Sv48，或显式 CUSTOM 几何")
  private val xlen = profile.xlen
  private val addressWidth = if (xlen == 64) 64 else 32
  private val vaBits = profile.computedAddressBits
  private val PTEWidth = profile.PTESizeBytes * 8
  // TLB 超级页的 shift/tag 由 profile 几何推导，fillLevel 使用统一
  // 3 位编码，因此不再限制 4 级；上限由 profile 的 8 级约束保证。
  require(!profile.TLBSuperpage || profile.superpageSupported,
    "TLB_SUPERPAGE 需要 MMU_SUPERPAGE")
  require(profile.PTWOutstanding >= 1 && profile.PTWOutstanding <= 16, "MMU_PTW_OUTSTANDING 必须在 1..16")
  require(profile.PTWBusOutstanding >= 0 && profile.PTWBusOutstanding <= 16, "MMU_PTW_BUS_OUTSTANDING 必须在 0..16")
  require(profile.refillPorts >= 1, "TLB refill 端口数必须 >= 1")
  require(Set("follow_tlb", "global").contains(profile.TLBMissQueueMode), "TLB miss queue 模式必须是 follow_tlb/global")

  private val split       = profile.TLBOrganization == "split"
  // split 的 I/D TLB 各自只有 1 个查找端口；unified TLB 可按 Kconfig
  // 打开第 2 个端口，让两个上下文同拍查找。
  private val lookupPorts =
    if (profile.TLBEnabled && !split) profile.utlbLookupPorts.max(1) else 1
  private val walkSlots   = profile.PTWOutstanding.max(1)
  private val followDepth =
    if (split) profile.itlbMissQueueEntries + profile.dtlbMissQueueEntries
    else profile.utlbMissQueueEntries
  private val missDepth   =
    if (profile.TLBMissQueueMode == "global") profile.globalMissQueueEntries
    else followDepth
  // 上下文表同时容纳“正在 walk”的请求和“在 miss queue 中等待”的请求。
  // 关闭 TLB 时没有 miss queue，上下文数保持原来的 PTW 在途数。
  private val contexts    =
    if (!profile.TLBEnabled) walkSlots else walkSlots + missDepth
  require(contexts >= 1 && contexts <= 64, s"MMU 上下文数($contexts) 必须在 1..64；请减小 PTW_OUTSTANDING 或 miss queue 深度")
  private val contextBits     = log2Ceil(contexts.max(2))
  private val tableSize   = 1 << log2Ceil((contexts * 2).max(2))
  private val tableBits   = log2Ceil(tableSize)
  private val seqBits     = 16
  private val walkTagBits = log2Ceil(walkSlots.max(2))

  val io = IO(new Bundle {
    val request   = Flipped(Decoupled(new ysyx_26030103_MMURequest(xlen, addressWidth)))
    val response  = Decoupled(new ysyx_26030103_MMUResponse(xlen, addressWidth))
    val bus   = new ysyx_26030103_PTEBusIO(addressWidth)
    val flush = Input(Bool())
    val perf_itlb_hit = Output(Bool())
    val perf_itlb_miss = Output(Bool())
    val perf_dtlb_hit = Output(Bool())
    val perf_dtlb_miss = Output(Bool())
    val perf_tlb_refill = Output(Bool())
    val perf_ptw_walk = Output(Bool())
    val perf_ptw_walk_cycle = Output(Bool())
    val perf_page_fault = Output(Bool())
    val perf_superpage_hit = Output(Bool())
    val perf_asid_hit = Output(Bool())
  })

  val contextFree :: contextLookup :: contextMissWait :: contextWalk :: Nil = Enum(4)
  class Context extends Bundle {
    val request = new ysyx_26030103_MMURequest(xlen, addressWidth)
  }
  val context = RegInit(VecInit(Seq.fill(contexts)(0.U.asTypeOf(new Context))))
  val contextValid = RegInit(VecInit(Seq.fill(contexts)(false.B)))
  val contextState = RegInit(VecInit(Seq.fill(contexts)(contextFree)))
  val contextSeq   = RegInit(VecInit(Seq.fill(contexts)(0.U(seqBits.W))))

  // walk 槽与上下文解耦：PTW 事务 ID 只需在 0..walkSlots-1 内唯一。
  val walkValid = RegInit(VecInit(Seq.fill(walkSlots)(false.B)))
  val walkContext   = RegInit(VecInit(Seq.fill(walkSlots)(0.U(contextBits.W))))

  val nextSeq   = RegInit(0.U(seqBits.W))
  val headSeq   = RegInit(0.U(seqBits.W))
  val responseValid = RegInit(VecInit(Seq.fill(tableSize)(false.B)))
  val responseData  = RegInit(VecInit(Seq.fill(tableSize)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, addressWidth)))))

  private def makeMissQueue(entries: Int): ysyx_26030103_TLBMissQueue =
    Module(new ysyx_26030103_TLBMissQueue(entries = entries, width = contextBits))

  // follow_tlb：split 下 I/D 各一条队列；unified 下只有 UTLB 队列。
  // global：不区分访问类型，共用一条队列。
  val itlbMissQueueOpt   =
    if (profile.TLBEnabled && split && profile.TLBMissQueueMode == "follow_tlb")
      Some(makeMissQueue(profile.itlbMissQueueEntries))
    else None
  val dtlbMissQueueOpt   =
    if (profile.TLBEnabled && split && profile.TLBMissQueueMode == "follow_tlb")
      Some(makeMissQueue(profile.dtlbMissQueueEntries))
    else None
  val utlbMissQueueOpt   =
    if (profile.TLBEnabled && !split && profile.TLBMissQueueMode == "follow_tlb")
      Some(makeMissQueue(profile.utlbMissQueueEntries))
    else None
  val globalMissQueueOpt =
    if (profile.TLBEnabled && profile.TLBMissQueueMode == "global")
      Some(makeMissQueue(profile.globalMissQueueEntries))
    else None

  val requestQueue = Module(
    new Queue(
      new ysyx_26030103_MMURequest(xlen, addressWidth),
      contexts.max(2),
      pipe = false,
      flow = false
    )
  )
  // flush 时队列里可能还有已入队但尚未分配上下文的请求。直接用 flush
  // 清空会丢失“这些请求曾经属于旧 satp”的事实；若保留又会在新地址空间
  // 下继续翻译。这里用 flushDrain 逐拍把旧请求 deq 掉但不分配上下文，
  // 排空之前也不接收任何新请求。
  val flushDrain = RegInit(false.B)
  requestQueue.io.enq.valid := io.request.valid && !io.flush && !flushDrain
  requestQueue.io.enq.bits  := io.request.bits
  io.request.ready          := requestQueue.io.enq.ready && !io.flush && !flushDrain

  def makeTLB(entries: Int, ways: Int) = Module(
    new ysyx_26030103_TLB(
      entries = entries,
      ways = ways,
      ASIDBits = profile.TLBASIDBits,
      globalEnable = profile.TLBGlobal,
      replacement = profile.TLBReplacement,
      adMode = profile.adMode,
      storage = profile.TLBStorage,
      superpage = profile.TLBSuperpage || profile.superpageSupported,
      pageBits = profile.pageBits,
      levels = profile.levels,
      indexBits = profile.indexBitsPerLevel,
      refillPorts = profile.refillPorts,
      superpageEntries = profile.TLBSuperpageEntries,
      hasU = profile.hasU,
      lookupPorts = lookupPorts,
      XLEN = xlen,
      addressWidth = addressWidth,
      vaBits = vaBits,
      PTEWidth = PTEWidth,
      PTEPPNLow = profile.PTEConfig.PPNLow,
      PTEPPNHigh = profile.PTEConfig.PPNHigh
    )
  )

  val itlbOpt =
    if (profile.TLBEnabled && split) Some(makeTLB(profile.itlbEntries, profile.itlbWays))
    else None
  val dtlbOpt =
    if (profile.TLBEnabled && split) Some(makeTLB(profile.dtlbEntries, profile.dtlbWays))
    else None
  val utlbOpt =
    if (profile.TLBEnabled && !split) Some(makeTLB(profile.utlbEntries, profile.utlbWays))
    else None

  private def MakePTW(pipelined: Boolean): ysyx_26030103_PTWBackend =
    if (pipelined) {
      Module(
        new ysyx_26030103_PTWPipeline(
          outstanding = profile.PTWOutstanding,
          busOutstanding = profile.PTWBusOutstanding,
          adMode = profile.adMode,
          cacheEnable = profile.PTWCache,
          cacheEntries = profile.PTWCacheEntries,
          cacheWays = profile.PTWCacheWays,
          superpage = profile.superpageSupported,
          PTEConfig = profile.PTEConfig,
          pageBits = profile.pageBits,
          levels = profile.levels,
          tableEntries = profile.tableEntries,
          fillSuperpage = profile.TLBSuperpage || profile.superpageSupported,
          PTESizeBytes = profile.PTESizeBytes,
          inOrder = profile.PTWOrder == "in_order",
          ResponsePorts = profile.refillPorts,
          hasU = profile.hasU,
          XLEN = xlen,
          addressWidth = addressWidth,
          vaBits = vaBits
        )
      )
    } else {
      Module(
        new ysyx_26030103_PTWCluster(
          outstanding = profile.PTWOutstanding,
          busOutstanding = profile.PTWBusOutstanding,
          adMode = profile.adMode,
          cacheEnable = profile.PTWCache,
          cacheEntries = profile.PTWCacheEntries,
          cacheWays = profile.PTWCacheWays,
          superpage = profile.superpageSupported,
          PTEConfig = profile.PTEConfig,
          pageBits = profile.pageBits,
          levels = profile.levels,
          tableEntries = profile.tableEntries,
          fillSuperpage = profile.TLBSuperpage || profile.superpageSupported,
          PTESizeBytes = profile.PTESizeBytes,
          inOrder = profile.PTWOrder == "in_order",
          ResponsePorts = profile.refillPorts,
          hasU = profile.hasU,
          XLEN = xlen,
          addressWidth = addressWidth,
          vaBits = vaBits
        )
      )
    }

  private val PTW: ysyx_26030103_PTWBackend =
    MakePTW(profile.PTWArch == "pipelined")

  // 每拍为 unified TLB 选择最多两个不同的 contextLookup 上下文：端口 0 选
  // 编号最小的候选，端口 1 在剩余候选中再选一个。split 只有一个端口。
  val lookupValid      = Wire(Vec(lookupPorts, Bool()))
  val lookupIndex      = Wire(Vec(lookupPorts, UInt(contextBits.W)))
  val lookupRequest        = Wire(Vec(lookupPorts,
    new ysyx_26030103_MMURequest(xlen, addressWidth)))
  val lookupCandidates = VecInit((0 until contexts).map { i =>
    contextValid(i) && contextState(i) === contextLookup
  })
  for (port <- 0 until lookupPorts) {
    lookupRequest(port)   := 0.U.asTypeOf(new ysyx_26030103_MMURequest(xlen, addressWidth))
  }
  lookupValid(0) := lookupCandidates.reduce(_ || _)
  lookupIndex(0) := PriorityEncoder(lookupCandidates)
  if (lookupPorts > 1) {
    val remaining = VecInit((0 until contexts).map { i =>
      lookupCandidates(i) && lookupIndex(0) =/= i.U
    })
    lookupValid(1) := remaining.reduce(_ || _)
    lookupIndex(1) := PriorityEncoder(remaining)
  }
  for (port <- 0 until lookupPorts) {
    when(lookupValid(port)) { lookupRequest(port) := context(lookupIndex(port)).request }
  }

  def connectTLB(TLB: ysyx_26030103_TLB):                          Unit = {
    for (port <- 0 until lookupPorts) {
      TLB.io.lookup(port).vaddr     := lookupRequest(port).vaddr
      TLB.io.lookup(port).ASID      := ysyx_26030103_RISCVFields.SatpASID(lookupRequest(port).satp, xlen)
      TLB.io.lookup(port).privilege := lookupRequest(port).privilege
      TLB.io.lookup(port).access    := lookupRequest(port).access
      TLB.io.lookup(port).sum       :=
        (if (profile.hasU) lookupRequest(port).sum else false.B)
      TLB.io.lookup(port).mxr       := lookupRequest(port).mxr
    }
    TLB.io.flush := io.flush
  }
  def connectFill(TLB: ysyx_26030103_TLB, port: Int, valid: Bool): Unit = {
    val virtualAddress = PTWResponsePayloadVec(port).FillVirtualAddress
    val ASID  = PTWResponsePayloadVec(port).FillASID
    val PTE   = PTWResponsePayloadVec(port).FillPTE
    val level = PTWResponsePayloadVec(port).FillLevel
    val napot = PTWResponsePayloadVec(port).FillNapot
    if (port == 0) {
      TLB.io.FillValid := valid
      TLB.io.FillVirtualAddress := virtualAddress
      TLB.io.FillASID  := ASID
      TLB.io.FillPTE   := PTE
      TLB.io.FillLevel := level
      TLB.io.FillNapot := napot
    } else {
      TLB.io.FillExtra.get(port - 1).valid := valid
      TLB.io.FillExtra.get(port - 1).VirtualAddress := virtualAddress
      TLB.io.FillExtra.get(port - 1).ASID  := ASID
      TLB.io.FillExtra.get(port - 1).PTE   := PTE
      TLB.io.FillExtra.get(port - 1).level := level
      TLB.io.FillExtra.get(port - 1).napot := napot
    }
  }

  itlbOpt.foreach(connectTLB)
  dtlbOpt.foreach(connectTLB)
  utlbOpt.foreach(connectTLB)

  def OptField[T <: Data](opt: Option[ysyx_26030103_TLB],
      field: ysyx_26030103_TLB => T, default: T): T =
    opt.map(field).getOrElse(default)
  def LaneField[T <: Data](port: Int, default: T)(
      fetchField: ysyx_26030103_TLB => T,
      dataField: ysyx_26030103_TLB => T,
      unifiedField: ysyx_26030103_TLB => T): T =
    if (!profile.TLBEnabled) default
    else if (split)
      Mux(
        lookupRequest(port).access === ysyx_26030103_AccessType.Fetch,
        OptField(itlbOpt, fetchField, default),
        OptField(dtlbOpt, dataField, default)
      )
    else OptField(utlbOpt, unifiedField, default)

  // 响应顺序：每个请求分配一个序号，完成结果写入按序号索引的表，
  // 只有队首完成时才向软件返回，后面的尽早完成也不会越过前面。
  val headIndex = headSeq(tableBits - 1, 0)
  io.response.valid := responseValid(headIndex)
  io.response.bits  := responseData(headIndex)

  // split 下两个 TLB 都只有一个查找端口；unified 的端口 1 只在
  // lookupPorts=2 时存在，下面按端口号从对应 TLB 实例取结果。
  def laneResultValid(port: Int): Bool =
    LaneField(port, true.B)(_.io.result(0).resultValid, _.io.result(0).resultValid,
      _.io.result(port).resultValid)
  def laneHit(port: Int):         Bool =
    LaneField(port, false.B)(_.io.result(0).hit, _.io.result(0).hit,
      _.io.result(port).hit)
  def laneFault(port: Int):       Bool =
    LaneField(port, false.B)(_.io.result(0).fault, _.io.result(0).fault,
      _.io.result(port).fault)
  def laneSuperpage(port: Int):   Bool =
    LaneField(port, false.B)(_.io.result(0).superpage, _.io.result(0).superpage,
      _.io.result(port).superpage)
  def laneASIDHit(port: Int):     Bool =
    LaneField(port, false.B)(_.io.result(0).ASIDHit, _.io.result(0).ASIDHit,
      _.io.result(port).ASIDHit)
  def lanePhysicalAddress(port: Int):       UInt =
    LaneField(port, lookupRequest(port).vaddr)(_.io.result(0).paddr,
      _.io.result(0).paddr, _.io.result(port).paddr)
  def laneCause(port: Int):       UInt =
    LaneField(port, 0.U)(_.io.result(0).cause, _.io.result(0).cause,
      _.io.result(port).cause)

  // Sv39/Sv48 非规范虚拟地址必须在 TLB 之前就判定为页错误。
  val LaneTLBValid = VecInit((0 until lookupPorts).map(laneResultValid))
  val LaneTLBHit   = VecInit((0 until lookupPorts).map(laneHit))
  val LaneTLBFault = VecInit((0 until lookupPorts).map(laneFault))
  val lanePhysicalAddressVec = VecInit((0 until lookupPorts).map(lanePhysicalAddress))
  val laneCauseVec = VecInit((0 until lookupPorts).map(laneCause))
  val laneBypass   = VecInit((0 until lookupPorts).map { port =>
    !profile.enabled.B || ysyx_26030103_RISCVFields.SatpMode(lookupRequest(port).satp, xlen) === 0.U ||
    lookupRequest(port).privilege === 3.U
  })
  // 只有配置对应的 MODE 才允许翻译。CSR 已对 satp.MODE 做 WARL 收敛，
  // 这里再检查一次直接驱动 MMU 的场景：CUSTOM 下写入标准 8/9，或
  // STANDARD 下写入另一标准模式，都必须产生页错误，不能拿另一种页表
  // 几何继续翻译。
  val laneModeInvalid = VecInit((0 until lookupPorts).map { port =>
    val mode = ysyx_26030103_RISCVFields.SatpMode(lookupRequest(port).satp, xlen)
    !laneBypass(port) && mode =/= profile.satpMode.U
  })
  val laneNonCanonical = VecInit((0 until lookupPorts).map { port =>
    !laneBypass(port) &&
      !ysyx_26030103_RISCVFields.CanonicalOk(lookupRequest(port).vaddr, vaBits, xlen)
  })
  // 模式错误优先于非规范地址：两者都只产生页错误，不会访问页表。
  val laneAddressFault = VecInit((0 until lookupPorts).map { port =>
    laneModeInvalid(port) || laneNonCanonical(port)
  })

  val PTWDoneVec       = VecInit((0 until profile.refillPorts).map(port => PTW.io.response(port).valid))
  // PTW 响应携带的是 walk 槽编号；先映射回上下文，再做状态/有效检查。
  val PTWTagVec        = VecInit((0 until profile.refillPorts).map(port => PTW.io.response(port).bits.ID(walkTagBits - 1, 0)))
  val PTWTagInRangeVec = VecInit((0 until profile.refillPorts).map(port => PTW.io.response(port).bits.ID < walkSlots.U))
  // 越界 ID 用安全索引 0 读取，最终由 PTWTagInRangeVec/状态检查丢弃。
  val PTWSafeTagVec    = VecInit(
    (0 until profile.refillPorts).map(port => Mux(PTWTagInRangeVec(port), PTWTagVec(port), 0.U(walkTagBits.W)))
  )
  // walkSlots=1 时 Vec 动态索引位宽必须为 0，避免多余的一位的无效索引。
  def walkIndex(tag: UInt): UInt =
    if (walkSlots == 1) 0.U(0.W) else tag
  val PTWContextIndexVec   = VecInit((0 until profile.refillPorts).map(port => walkContext(walkIndex(PTWSafeTagVec(port)))))
  val PTWContextValidVec = VecInit(
    (0 until profile.refillPorts).map(port =>
      PTWDoneVec(port) && PTWTagInRangeVec(port) && walkValid(walkIndex(PTWSafeTagVec(port))) &&
        contextValid(PTWContextIndexVec(port)) &&
        contextState(PTWContextIndexVec(port)) === contextWalk
    )
  )
  val PTWResponsePayloadVec     = VecInit((0 until profile.refillPorts).map(port => PTW.io.response(port).bits))
  val AnyPTWDone     = PTWDoneVec.reduce(_ || _)

  // PTW 响应优先写回响应表；同一拍发生 PTW 响应时，本拍所有 TLB 命中
  // 都延迟到下一拍，避免 TLB 和 PTW 争用响应表写端口。
  val laneHitDone    = VecInit((0 until lookupPorts).map { port =>
    !AnyPTWDone && lookupValid(port) && profile.TLBEnabled.B &&
    !laneBypass(port) && !laneAddressFault(port) &&
    LaneTLBValid(port) && LaneTLBHit(port)
  })
  val laneAddressFaultDone = VecInit((0 until lookupPorts).map { port =>
    !AnyPTWDone && lookupValid(port) && !laneBypass(port) && laneAddressFault(port)
  })
  val laneBypassDone = VecInit((0 until lookupPorts).map { port =>
    !AnyPTWDone && lookupValid(port) && laneBypass(port)
  })

  def writeResponse(valid: Bool, index: UInt, seq: UInt, response: ysyx_26030103_MMUResponse): Unit = {
    when(valid) {
      responseValid(seq(tableBits - 1, 0)) := true.B
      responseData(seq(tableBits - 1, 0))  := response
      contextValid(index)                  := false.B
      contextState(index)                  := contextFree
    }
  }

  val PTWResponseVec = WireInit(VecInit(Seq.fill(profile.refillPorts)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, addressWidth)))))
  for (port <- 0 until profile.refillPorts) {
    PTWResponseVec(port).ID    := context(PTWContextIndexVec(port)).request.ID
    PTWResponseVec(port).paddr := PTWResponsePayloadVec(port).paddr
    PTWResponseVec(port).fault := PTWResponsePayloadVec(port).fault
    PTWResponseVec(port).cause := PTWResponsePayloadVec(port).cause
    PTWResponseVec(port).tval  := PTWResponsePayloadVec(port).tval
  }

  def BuildResponse(responseID: UInt, physicalAddress: UInt, fault: Bool,
      cause: UInt, trapValue: UInt): ysyx_26030103_MMUResponse = {
    val response = WireInit(0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, addressWidth)))
    response.ID    := responseID
    response.paddr := physicalAddress
    response.fault := fault
    response.cause := cause
    response.tval  := trapValue
    response
  }

  val laneHitResponse    = WireInit(VecInit(Seq.fill(lookupPorts)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, addressWidth)))))
  val laneBypassResponse = WireInit(VecInit(Seq.fill(lookupPorts)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, addressWidth)))))
  val laneAddressFaultResponse = WireInit(VecInit(Seq.fill(lookupPorts)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, addressWidth)))))
  for (port <- 0 until lookupPorts) {
    laneHitResponse(port) := BuildResponse(
      lookupRequest(port).ID, lanePhysicalAddressVec(port), LaneTLBFault(port),
      laneCauseVec(port), lookupRequest(port).vaddr)

    laneBypassResponse(port) := BuildResponse(
      lookupRequest(port).ID, lookupRequest(port).vaddr, false.B, 0.U,
      lookupRequest(port).vaddr)

    laneAddressFaultResponse(port) := BuildResponse(
      lookupRequest(port).ID, 0.U, true.B,
      ysyx_26030103_RISCVFields.PageFaultCause(
        lookupRequest(port).access === ysyx_26030103_AccessType.Fetch,
        lookupRequest(port).access === ysyx_26030103_AccessType.Store),
      lookupRequest(port).vaddr)
  }

  // 两个查找端口可以在同一拍各写一个响应表项；表项按请求序号索引，
  // 因此同拍完成不会破坏向软件的响应顺序。
  for (port <- 0 until profile.refillPorts) {
    when(PTWContextValidVec(port)) {
      writeResponse(true.B, PTWContextIndexVec(port), contextSeq(PTWContextIndexVec(port)), PTWResponseVec(port))
      // 响应被接收后释放对应 walk 槽。PTW 响应 ready 恒为有效响应，
      // 因而与 PTW 内部上下文释放保持同拍。
      walkValid(walkIndex(PTWSafeTagVec(port))) := false.B
    }
  }
  for (port <- 0 until lookupPorts) {
    when(laneAddressFaultDone(port)) {
      writeResponse(true.B, lookupIndex(port), contextSeq(lookupIndex(port)),
        laneAddressFaultResponse(port))
    }.elsewhen(laneHitDone(port)) {
      writeResponse(true.B, lookupIndex(port), contextSeq(lookupIndex(port)), laneHitResponse(port))
    }.elsewhen(laneBypassDone(port)) {
      writeResponse(true.B, lookupIndex(port), contextSeq(lookupIndex(port)), laneBypassResponse(port))
    }
  }

  // TLB miss 入队
  // 只有“TLB 结果有效且确实缺失”的查找才会进入队列；SRAM TLB 结果
  // 未就绪时上下文留在 contextLookup，下一拍继续查找。
  val laneMiss = VecInit((0 until lookupPorts).map { port =>
    lookupValid(port) && profile.TLBEnabled.B && !laneBypass(port) &&
    !laneAddressFault(port) && LaneTLBValid(port) && !LaneTLBHit(port)
  })
  def laneFetch(port: Int): Bool =
    lookupRequest(port).access === ysyx_26030103_AccessType.Fetch

  def enqueueMiss(missQueue: ysyx_26030103_TLBMissQueue, candidates: Seq[Bool]):       Unit = {
    val candVec = VecInit(candidates)
    val any     = candVec.reduce(_ || _)
    val selected     = PriorityEncoder(candVec)
    missQueue.io.enq.valid := any
    missQueue.io.enq.bits  := Mux(any, lookupIndex(selected), 0.U)
    missQueue.io.flush     := io.flush
  }
  def commitMissEnqueue(missQueue: ysyx_26030103_TLBMissQueue, candidates: Seq[Bool]): Unit = {
    val candVec = VecInit(candidates)
    val selected     = PriorityEncoder(candVec)
    when(missQueue.io.enq.fire) {
      contextState(lookupIndex(selected)) := contextMissWait
    }
  }

  if (profile.TLBEnabled && profile.TLBMissQueueMode == "follow_tlb" && split) {
    val instructionCandidates = (0 until lookupPorts).map(port => laneMiss(port) && laneFetch(port))
    val dataCandidates = (0 until lookupPorts).map(port => laneMiss(port) && !laneFetch(port))
    enqueueMiss(itlbMissQueueOpt.get, instructionCandidates)
    enqueueMiss(dtlbMissQueueOpt.get, dataCandidates)
    commitMissEnqueue(itlbMissQueueOpt.get, instructionCandidates)
    commitMissEnqueue(dtlbMissQueueOpt.get, dataCandidates)
  } else if (profile.TLBEnabled && profile.TLBMissQueueMode == "follow_tlb") {
    val unifiedCandidates = (0 until lookupPorts).map(port => laneMiss(port))
    enqueueMiss(utlbMissQueueOpt.get, unifiedCandidates)
    commitMissEnqueue(utlbMissQueueOpt.get, unifiedCandidates)
  } else if (profile.TLBEnabled) {
    val globalCandidates = (0 until lookupPorts).map(port => laneMiss(port))
    enqueueMiss(globalMissQueueOpt.get, globalCandidates)
    commitMissEnqueue(globalMissQueueOpt.get, globalCandidates)
  }

  // miss queue -> PTW 调度
  // PTW 只有一个请求端口，每拍最多启动一笔 walk。split follow_tlb 下
  // I/D 队列用一位 round-robin 防止一侧长期饿死；global/unified 只有
  // 一条队列，直接取队首。
  val freeWalkVec = VecInit((0 until walkSlots).map(i => !walkValid(i)))
  val anyFreeWalk = freeWalkVec.reduce(_ || _)
  val freeWalkIndex =
    if (walkSlots == 1) 0.U(walkTagBits.W)
    else PriorityEncoder(freeWalkVec)
  val PTWCanIssue = anyFreeWalk && !AnyPTWDone && !io.flush
  val roundRobinITLB      = RegInit(true.B)

  val queueIssue = Wire(Bool())
  val queueContext   = Wire(UInt(contextBits.W))
  queueIssue := false.B
  queueContext   := 0.U

  if (profile.TLBEnabled && profile.TLBMissQueueMode == "follow_tlb" && split) {
    val iValid  = itlbMissQueueOpt.get.io.deq.valid
    val dValid  = dtlbMissQueueOpt.get.io.deq.valid
    val chooseInstruction = iValid && (!dValid || roundRobinITLB)
    val chooseData = dValid && !chooseInstruction
    queueIssue                    := (chooseInstruction || chooseData) && PTWCanIssue && PTW.io.request.ready
    queueContext                      := Mux(chooseInstruction, itlbMissQueueOpt.get.io.deq.bits, dtlbMissQueueOpt.get.io.deq.bits)
    itlbMissQueueOpt.get.io.deq.ready := queueIssue && chooseInstruction
    dtlbMissQueueOpt.get.io.deq.ready := queueIssue && chooseData
    when(queueIssue && chooseInstruction) { roundRobinITLB := false.B }
    when(queueIssue && chooseData) { roundRobinITLB := true.B }
  } else if (profile.TLBEnabled && profile.TLBMissQueueMode == "follow_tlb") {
    queueIssue                    := utlbMissQueueOpt.get.io.deq.valid && PTWCanIssue &&
      PTW.io.request.ready
    queueContext                      := utlbMissQueueOpt.get.io.deq.bits
    utlbMissQueueOpt.get.io.deq.ready := queueIssue
  } else if (profile.TLBEnabled) {
    queueIssue                      := globalMissQueueOpt.get.io.deq.valid && PTWCanIssue &&
      PTW.io.request.ready
    queueContext                        := globalMissQueueOpt.get.io.deq.bits
    globalMissQueueOpt.get.io.deq.ready := queueIssue
  }

  // 无 TLB 时没有 miss queue，保留原行为：contextLookup 直接占用 PTW。
  val directIssueNeeded =
    !profile.TLBEnabled.B && lookupValid(0) && !laneBypass(0) &&
      !laneAddressFault(0) && !AnyPTWDone && anyFreeWalk && !io.flush
  val issueContext          = Mux(directIssueNeeded, lookupIndex(0), queueContext)

  def requestPTW(contextIndex: UInt): Unit = {
    PTW.io.request.valid          := true.B
    PTW.io.request.bits.ID        := freeWalkIndex.pad(4)
    PTW.io.request.bits.vaddr     := context(contextIndex).request.vaddr
    PTW.io.request.bits.satp      := context(contextIndex).request.satp
    PTW.io.request.bits.privilege := context(contextIndex).request.privilege
    PTW.io.request.bits.access    := context(contextIndex).request.access
    PTW.io.request.bits.sum       := (if (profile.hasU) context(contextIndex).request.sum else false.B)
    PTW.io.request.bits.mxr       := context(contextIndex).request.mxr
    when(PTW.io.request.fire) {
      contextState(contextIndex)     := contextWalk
      walkValid(freeWalkIndex) := true.B
      walkContext(freeWalkIndex)   := contextIndex
    }
  }

  PTW.io.request.valid := directIssueNeeded || queueIssue
  PTW.io.request.bits  := 0.U.asTypeOf(
    new ysyx_26030103_PTWRequest(xlen, addressWidth))
  when(directIssueNeeded || queueIssue) { requestPTW(issueContext) }
  for (port <- 0 until profile.refillPorts) {
    PTW.io.response(port).ready := PTWDoneVec(port)
  }
  PTW.io.flush := io.flush

  // PTW 回填 TLB。多个上下文可以并行 walk，但页表总线仍由 PTW 仲裁。
  val fillFetch = VecInit(
    (0 until profile.refillPorts).map(port => context(PTWContextIndexVec(port)).request.access === ysyx_26030103_AccessType.Fetch)
  )
  if (split) {
    itlbOpt.foreach { TLB =>
      for (port <- 0 until profile.refillPorts) {
        connectFill(TLB, port, PTWContextValidVec(port) && PTWResponsePayloadVec(port).FillValid && fillFetch(port))
      }
    }
    dtlbOpt.foreach { TLB =>
      for (port <- 0 until profile.refillPorts) {
        connectFill(TLB, port, PTWContextValidVec(port) && PTWResponsePayloadVec(port).FillValid && !fillFetch(port))
      }
    }
  } else {
    utlbOpt.foreach { TLB =>
      for (port <- 0 until profile.refillPorts) {
        connectFill(TLB, port, PTWContextValidVec(port) && PTWResponsePayloadVec(port).FillValid)
      }
    }
  }

  // 接收新请求：空上下文槽 + 请求队列非空。
  val freeCandidates = VecInit((0 until contexts).map(i => !contextValid(i)))
  val anyFree        = freeCandidates.reduce(_ || _)
  val freeIndex      = PriorityEncoder(freeCandidates)
  requestQueue.io.deq.ready := flushDrain || (anyFree && !io.flush)
  when(requestQueue.io.deq.fire && !flushDrain) {
    context(freeIndex).request  := requestQueue.io.deq.bits
    contextSeq(freeIndex)   := nextSeq
    contextValid(freeIndex) := true.B
    contextState(freeIndex) := contextLookup
    nextSeq             := nextSeq + 1.U
  }
  // io.flush 当拍置位，队列真正排空后撤销。队列空时 deq.ready 仍保持
  // 高电平，但 deq.valid 为低，不会产生虚假握手。
  when(io.flush) {
    flushDrain := true.B
  }.elsewhen(requestQueue.io.count === 0.U) {
    flushDrain := false.B
  }

  // 响应表队首出队。
  when(io.response.fire) {
    responseValid(headIndex) := false.B
    headSeq            := headSeq + 1.U
  }

  when(io.flush) {
    for (i <- 0 until contexts) {
      contextValid(i) := false.B
      contextState(i) := contextFree
    }
    for (i <- 0 until walkSlots) { walkValid(i) := false.B }
    for (i <- 0 until tableSize) { responseValid(i) := false.B }
    headSeq := 0.U
    nextSeq := 0.U
    roundRobinITLB  := true.B
  }

  val perfFetchHit = VecInit((0 until lookupPorts).map(port =>
    lookupValid(port) && lookupRequest(port).access === ysyx_26030103_AccessType.Fetch &&
      !laneBypass(port) && LaneTLBHit(port)
  )).reduce(_ || _)
  val perfFetchMiss = VecInit((0 until lookupPorts).map(port =>
    lookupValid(port) && lookupRequest(port).access === ysyx_26030103_AccessType.Fetch &&
      profile.TLBEnabled.B && !laneBypass(port) && !LaneTLBHit(port)
  )).reduce(_ || _)
  val perfDataHit = VecInit((0 until lookupPorts).map(port =>
    lookupValid(port) && lookupRequest(port).access =/= ysyx_26030103_AccessType.Fetch &&
      !laneBypass(port) && LaneTLBHit(port)
  )).reduce(_ || _)
  val perfDataMiss = VecInit((0 until lookupPorts).map(port =>
    lookupValid(port) && lookupRequest(port).access =/= ysyx_26030103_AccessType.Fetch &&
      profile.TLBEnabled.B && !laneBypass(port) && !LaneTLBHit(port)
  )).reduce(_ || _)
  val perfTLBRefill = VecInit((0 until profile.refillPorts).map(port =>
    PTWContextValidVec(port) && PTWResponsePayloadVec(port).FillValid
  )).reduce(_ || _)
  io.perf_itlb_hit := perfFetchHit
  io.perf_itlb_miss := perfFetchMiss
  io.perf_dtlb_hit := perfDataHit
  io.perf_dtlb_miss := perfDataMiss
  io.perf_tlb_refill := perfTLBRefill
  io.perf_ptw_walk := directIssueNeeded || queueIssue
  io.perf_ptw_walk_cycle := walkValid.reduce(_ || _)
  io.perf_page_fault :=
    responseValid(headIndex) && io.response.ready && responseData(headIndex).fault
  io.perf_superpage_hit := VecInit((0 until lookupPorts).map(port =>
    lookupValid(port) && !laneBypass(port) && laneHit(port) && laneSuperpage(port)
  )).reduce(_ || _)
  io.perf_asid_hit := VecInit((0 until lookupPorts).map(port =>
    lookupValid(port) && !laneBypass(port) && laneHit(port) && laneASIDHit(port)
  )).reduce(_ || _)

  PTW.io.bus.request <> io.bus.request
  PTW.io.bus.response <> io.bus.response
}
