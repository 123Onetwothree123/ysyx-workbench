package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile

// Sv32 MMU：TLB 查找、miss queue 排队、PTW walk 三级解耦；命中/旁路直接
// 响应，缺失进入 ITLB/DTLB/UTLB 各自的 miss queue（follow_tlb）或共享的
// 全局 miss queue（global），只有队首占用 walk 槽。
// PTW ID 使用独立的 walk 槽编号（0..ptwOutstanding-1）而非上下文编号，
// 上下文数可大于 16 且仍满足 4 位 ID；完成结果按进入序号写回响应表，
// 多队列/多 walker 的乱序完成不会破坏软件可见的响应顺序。
class ysyx_26030103_MMU(
  profile: ysyx_26030103_MMUProfile = ysyx_26030103_MMUProfile())
    extends Module {
  require(profile.pteSizeBytes == 4 || profile.pteSizeBytes == 8, "PTE 大小只支持 4/8 字节")
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
  private val pteWidth = profile.pteSizeBytes * 8
  // TLB 超级页的 shift/tag 由 profile 几何推导，fillLevel 使用统一
  // 3 位编码，因此不再限制 4 级；上限由 profile 的 8 级约束保证。
  require(!profile.tlbSuperpage || profile.superpage, "TLB_SUPERPAGE 需要 MMU_SUPERPAGE")
  require(profile.ptwOutstanding >= 1 && profile.ptwOutstanding <= 16, "MMU_PTW_OUTSTANDING 必须在 1..16")
  require(profile.ptwBusOutstanding >= 0 && profile.ptwBusOutstanding <= 16, "MMU_PTW_BUS_OUTSTANDING 必须在 0..16")
  require(profile.refillPorts >= 1, "TLB refill 端口数必须 >= 1")
  require(Set("follow_tlb", "global").contains(profile.tlbMissQueueMode), "TLB miss queue 模式必须是 follow_tlb/global")

  private val split       = profile.tlbOrganization == "split"
  // split 的 I/D TLB 各自只有 1 个查找端口；unified TLB 可按 Kconfig
  // 打开第 2 个端口，让两个上下文同拍查找。
  private val lookupPorts =
    if (profile.tlbEnabled && !split) profile.utlbLookupPorts.max(1) else 1
  private val walkSlots   = profile.ptwOutstanding.max(1)
  private val followDepth =
    if (split) profile.itlbMissQueueEntries + profile.dtlbMissQueueEntries
    else profile.utlbMissQueueEntries
  private val missDepth   =
    if (profile.tlbMissQueueMode == "global") profile.globalMissQueueEntries
    else followDepth
  // 上下文表同时容纳“正在 walk”的请求和“在 miss queue 中等待”的请求。
  // 关闭 TLB 时没有 miss queue，上下文数保持原来的 PTW 在途数。
  private val contexts    =
    if (!profile.tlbEnabled) walkSlots else walkSlots + missDepth
  require(contexts >= 1 && contexts <= 64, s"MMU 上下文数($contexts) 必须在 1..64；请减小 PTW_OUTSTANDING 或 miss queue 深度")
  private val contextBits     = log2Ceil(contexts.max(2))
  private val tableSize   = 1 << log2Ceil((contexts * 2).max(2))
  private val tableBits   = log2Ceil(tableSize)
  private val seqBits     = 16
  private val walkTagBits = log2Ceil(walkSlots.max(2))

  val io = IO(new Bundle {
    val req   = Flipped(Decoupled(new ysyx_26030103_MMURequest(xlen, addressWidth)))
    val resp  = Decoupled(new ysyx_26030103_MMUResponse(xlen, addressWidth))
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
    val req = new ysyx_26030103_MMURequest(xlen, addressWidth)
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
    if (profile.tlbEnabled && split && profile.tlbMissQueueMode == "follow_tlb")
      Some(makeMissQueue(profile.itlbMissQueueEntries))
    else None
  val dtlbMissQueueOpt   =
    if (profile.tlbEnabled && split && profile.tlbMissQueueMode == "follow_tlb")
      Some(makeMissQueue(profile.dtlbMissQueueEntries))
    else None
  val utlbMissQueueOpt   =
    if (profile.tlbEnabled && !split && profile.tlbMissQueueMode == "follow_tlb")
      Some(makeMissQueue(profile.utlbMissQueueEntries))
    else None
  val globalMissQueueOpt =
    if (profile.tlbEnabled && profile.tlbMissQueueMode == "global")
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
  requestQueue.io.enq.valid := io.req.valid && !io.flush && !flushDrain
  requestQueue.io.enq.bits  := io.req.bits
  io.req.ready          := requestQueue.io.enq.ready && !io.flush && !flushDrain

  def makeTLB(entries: Int, ways: Int) = Module(
    new ysyx_26030103_TLB(
      entries = entries,
      ways = ways,
      asidBits = profile.tlbASIDBits,
      globalEnable = profile.tlbGlobal,
      replacement = profile.tlbReplacement,
      adMode = profile.adMode,
      storage = profile.tlbStorage,
      superpage = profile.tlbSuperpage,
      pageBits = profile.pageBits,
      levels = profile.levels,
      indexBits = profile.indexBitsPerLevel,
      refillPorts = profile.refillPorts,
      superpageEntries = profile.tlbSuperpageEntries,
      hasU = profile.hasU,
      lookupPorts = lookupPorts,
      XLEN = xlen,
      addressWidth = addressWidth,
      vaBits = vaBits,
      pteWidth = pteWidth,
      ptePPNLow = profile.pteConfig.ppnLow,
      ptePPNHigh = profile.pteConfig.ppnHigh
    )
  )

  val itlbOpt =
    if (profile.tlbEnabled && split) Some(makeTLB(profile.itlbEntries, profile.itlbWays))
    else None
  val dtlbOpt =
    if (profile.tlbEnabled && split) Some(makeTLB(profile.dtlbEntries, profile.dtlbWays))
    else None
  val utlbOpt =
    if (profile.tlbEnabled && !split) Some(makeTLB(profile.utlbEntries, profile.utlbWays))
    else None

  private val ptw: ysyx_26030103_PTWBackend =
    if (profile.ptwArch == "pipelined") {
      Module(
        new ysyx_26030103_PTWPipeline(
          outstanding = profile.ptwOutstanding,
          busOutstanding = profile.ptwBusOutstanding,
          adMode = profile.adMode,
          cacheEnable = profile.ptwCache,
          cacheEntries = profile.ptwCacheEntries,
          cacheWays = profile.ptwCacheWays,
          superpage = profile.superpage,
          pteConfig = profile.pteConfig,
          pageBits = profile.pageBits,
          levels = profile.levels,
          tableEntries = profile.tableEntries,
          fillSuperpage = profile.tlbSuperpage,
          pteSizeBytes = profile.pteSizeBytes,
          inOrder = profile.ptwOrder == "in_order",
          respPorts = profile.refillPorts,
          hasU = profile.hasU,
          XLEN = xlen,
          addressWidth = addressWidth,
          vaBits = vaBits
        )
      )
    } else {
      Module(
        new ysyx_26030103_PTWCluster(
          outstanding = profile.ptwOutstanding,
          busOutstanding = profile.ptwBusOutstanding,
          adMode = profile.adMode,
          cacheEnable = profile.ptwCache,
          cacheEntries = profile.ptwCacheEntries,
          cacheWays = profile.ptwCacheWays,
          superpage = profile.superpage,
          pteConfig = profile.pteConfig,
          pageBits = profile.pageBits,
          levels = profile.levels,
          tableEntries = profile.tableEntries,
          fillSuperpage = profile.tlbSuperpage,
          pteSizeBytes = profile.pteSizeBytes,
          inOrder = profile.ptwOrder == "in_order",
          respPorts = profile.refillPorts,
          hasU = profile.hasU,
          XLEN = xlen,
          addressWidth = addressWidth,
          vaBits = vaBits
        )
      )
    }

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
    lookupValid(port) := false.B
    lookupIndex(port) := 0.U
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
    when(lookupValid(port)) { lookupRequest(port) := context(lookupIndex(port)).req }
  }

  def connectTLB(tlb: ysyx_26030103_TLB):                          Unit = {
    for (port <- 0 until lookupPorts) {
      tlb.io.lookup(port).vaddr     := lookupRequest(port).vaddr
      tlb.io.lookup(port).asid      := satpASID(lookupRequest(port).satp)
      tlb.io.lookup(port).privilege := lookupRequest(port).privilege
      tlb.io.lookup(port).access    := lookupRequest(port).access
      tlb.io.lookup(port).sum       :=
        (if (profile.hasU) lookupRequest(port).sum else false.B)
      tlb.io.lookup(port).mxr       := lookupRequest(port).mxr
    }
    tlb.io.flush := io.flush
    tlb.io.fillValid := false.B
    tlb.io.fillVirtualAddress := 0.U
    tlb.io.fillASID  := 0.U
    tlb.io.fillPTE   := 0.U
    tlb.io.fillLevel := 0.U
    tlb.io.fillExtra.foreach { ports =>
      for (port <- ports) {
        port.valid := false.B
        port.vaddr := 0.U
        port.asid  := 0.U
        port.pte   := 0.U
        port.level := 0.U
      }
    }
  }
  def connectFill(tlb: ysyx_26030103_TLB, port: Int, valid: Bool): Unit = {
    val virtualAddress = ptwResponsePayloadVec(port).fillVirtualAddress
    val asid  = ptwResponsePayloadVec(port).fillASID
    val pte   = ptwResponsePayloadVec(port).fillPTE
    val level = ptwResponsePayloadVec(port).fillLevel
    if (port == 0) {
      tlb.io.fillValid := valid
      tlb.io.fillVirtualAddress := virtualAddress
      tlb.io.fillASID  := asid
      tlb.io.fillPTE   := pte
      tlb.io.fillLevel := level
    } else {
      tlb.io.fillExtra.get(port - 1).valid := valid
      tlb.io.fillExtra.get(port - 1).vaddr := virtualAddress
      tlb.io.fillExtra.get(port - 1).asid  := asid
      tlb.io.fillExtra.get(port - 1).pte   := pte
      tlb.io.fillExtra.get(port - 1).level := level
    }
  }

  itlbOpt.foreach(connectTLB)
  dtlbOpt.foreach(connectTLB)
  utlbOpt.foreach(connectTLB)

  def optBool(opt: Option[ysyx_26030103_TLB], f: ysyx_26030103_TLB => Bool): Bool =
    opt.map(f).getOrElse(false.B)
  def optUInt(opt: Option[ysyx_26030103_TLB], f: ysyx_26030103_TLB => UInt): UInt =
    opt.map(f).getOrElse(0.U)

  // 响应顺序：每个请求分配一个序号，完成结果写入按序号索引的表，
  // 只有队首完成时才向软件返回，后面的尽早完成也不会越过前面。
  val headIndex = headSeq(tableBits - 1, 0)
  io.resp.valid := responseValid(headIndex)
  io.resp.bits  := responseData(headIndex)

  // split 下两个 TLB 都只有一个查找端口；unified 的端口 1 只在
  // lookupPorts=2 时存在，下面按端口号从对应 TLB 实例取结果。
  def laneResultValid(port: Int): Bool =
    if (!profile.tlbEnabled) true.B
    else if (split)
      Mux(
        lookupRequest(port).access === ysyx_26030103_AccessType.Fetch,
        optBool(itlbOpt, _.io.result(0).resultValid),
        optBool(dtlbOpt, _.io.result(0).resultValid)
      )
    else optBool(utlbOpt, _.io.result(port).resultValid)
  def laneHit(port: Int):         Bool =
    if (!profile.tlbEnabled) false.B
    else if (split)
      Mux(
        lookupRequest(port).access === ysyx_26030103_AccessType.Fetch,
        optBool(itlbOpt, _.io.result(0).hit),
        optBool(dtlbOpt, _.io.result(0).hit)
      )
    else optBool(utlbOpt, _.io.result(port).hit)
  def laneFault(port: Int):       Bool =
    if (!profile.tlbEnabled) false.B
    else if (split)
      Mux(
        lookupRequest(port).access === ysyx_26030103_AccessType.Fetch,
        optBool(itlbOpt, _.io.result(0).fault),
        optBool(dtlbOpt, _.io.result(0).fault)
      )
    else optBool(utlbOpt, _.io.result(port).fault)
  def laneSuperpage(port: Int):   Bool =
    if (!profile.tlbEnabled) false.B
    else if (split)
      Mux(
        lookupRequest(port).access === ysyx_26030103_AccessType.Fetch,
        optBool(itlbOpt, _.io.result(0).superpage),
        optBool(dtlbOpt, _.io.result(0).superpage)
      )
    else optBool(utlbOpt, _.io.result(port).superpage)
  def laneASIDHit(port: Int):     Bool =
    if (!profile.tlbEnabled) false.B
    else if (split)
      Mux(
        lookupRequest(port).access === ysyx_26030103_AccessType.Fetch,
        optBool(itlbOpt, _.io.result(0).asidHit),
        optBool(dtlbOpt, _.io.result(0).asidHit)
      )
    else optBool(utlbOpt, _.io.result(port).asidHit)
  def lanePhysicalAddress(port: Int):       UInt =
    if (!profile.tlbEnabled) lookupRequest(port).vaddr
    else if (split)
      Mux(
        lookupRequest(port).access === ysyx_26030103_AccessType.Fetch,
        optUInt(itlbOpt, _.io.result(0).paddr),
        optUInt(dtlbOpt, _.io.result(0).paddr)
      )
    else optUInt(utlbOpt, _.io.result(port).paddr)
  def laneCause(port: Int):       UInt =
    if (!profile.tlbEnabled) 0.U
    else if (split)
      Mux(
        lookupRequest(port).access === ysyx_26030103_AccessType.Fetch,
        optUInt(itlbOpt, _.io.result(0).cause),
        optUInt(dtlbOpt, _.io.result(0).cause)
      )
    else optUInt(utlbOpt, _.io.result(port).cause)

  // satp 字段位置按 XLEN 选择：RV32 mode=31, asid=30:22, ppn=21:0；
  // RV64 mode=63:60, asid=59:44, ppn=43:0。
  def satpModeOf(satp: UInt): UInt =
    if (xlen == 32) satp(31) else satp(63, 60)
  def satpASIDOf(satp: UInt): UInt =
    if (xlen == 32) satp(30, 22) else satp(59, 44)
  def satpASID(satp: UInt): UInt = satpASIDOf(satp)
  // Sv39/Sv48 非规范虚拟地址必须在 TLB 之前就判定为页错误。
  def canonicalOk(virtualAddress: UInt): Bool = {
    if (vaBits == xlen) true.B
    else {
      val upper = virtualAddress(xlen - 1, vaBits)
      val sign = Fill(xlen - vaBits, virtualAddress(vaBits - 1))
      upper === sign
    }
  }

  val laneTLBValid = VecInit((0 until lookupPorts).map(laneResultValid))
  val laneTLBHit   = VecInit((0 until lookupPorts).map(laneHit))
  val laneTLBFault = VecInit((0 until lookupPorts).map(laneFault))
  val lanePhysicalAddressVec = VecInit((0 until lookupPorts).map(lanePhysicalAddress))
  val laneCauseVec = VecInit((0 until lookupPorts).map(laneCause))
  val laneBypass   = VecInit((0 until lookupPorts).map { port =>
    !profile.enabled.B || satpModeOf(lookupRequest(port).satp) === 0.U ||
    lookupRequest(port).privilege === 3.U
  })
  // 只有配置对应的 MODE 才允许翻译。CSR 已对 satp.MODE 做 WARL 收敛，
  // 这里再检查一次直接驱动 MMU 的场景：CUSTOM 下写入标准 8/9，或
  // STANDARD 下写入另一标准模式，都必须产生页错误，不能拿另一种页表
  // 几何继续翻译。
  val laneModeInvalid = VecInit((0 until lookupPorts).map { port =>
    !laneBypass(port) && satpModeOf(lookupRequest(port).satp) =/= profile.satpMode.U
  })
  val laneNonCanonical = VecInit((0 until lookupPorts).map { port =>
    !laneBypass(port) && !canonicalOk(lookupRequest(port).vaddr)
  })
  // 模式错误优先于非规范地址：两者都只产生页错误，不会访问页表。
  val laneAddressFault = VecInit((0 until lookupPorts).map { port =>
    laneModeInvalid(port) || laneNonCanonical(port)
  })

  val ptwDoneVec       = VecInit((0 until profile.refillPorts).map(port => ptw.io.resp(port).valid))
  // PTW 响应携带的是 walk 槽编号；先映射回上下文，再做状态/有效检查。
  val ptwTagVec        = VecInit((0 until profile.refillPorts).map(port => ptw.io.resp(port).bits.id(walkTagBits - 1, 0)))
  val ptwTagInRangeVec = VecInit((0 until profile.refillPorts).map(port => ptw.io.resp(port).bits.id < walkSlots.U))
  // 越界 ID 用安全索引 0 读取，最终由 ptwTagInRangeVec/状态检查丢弃。
  val ptwSafeTagVec    = VecInit(
    (0 until profile.refillPorts).map(port => Mux(ptwTagInRangeVec(port), ptwTagVec(port), 0.U(walkTagBits.W)))
  )
  // walkSlots=1 时 Vec 动态索引位宽必须为 0，避免多余的一位的无效索引。
  def walkIndex(tag: UInt): UInt =
    if (walkSlots == 1) 0.U(0.W) else tag
  val ptwContextIndexVec   = VecInit((0 until profile.refillPorts).map(port => walkContext(walkIndex(ptwSafeTagVec(port)))))
  val ptwContextValidVec = VecInit(
    (0 until profile.refillPorts).map(port =>
      ptwDoneVec(port) && ptwTagInRangeVec(port) && walkValid(walkIndex(ptwSafeTagVec(port))) &&
        contextValid(ptwContextIndexVec(port)) &&
        contextState(ptwContextIndexVec(port)) === contextWalk
    )
  )
  val ptwResponsePayloadVec     = VecInit((0 until profile.refillPorts).map(port => ptw.io.resp(port).bits))
  val anyPTWDone     = ptwDoneVec.reduce(_ || _)

  // PTW 响应优先写回响应表；同一拍发生 PTW 响应时，本拍所有 TLB 命中
  // 都延迟到下一拍，避免 TLB 和 PTW 争用响应表写端口。
  val laneHitDone    = VecInit((0 until lookupPorts).map { port =>
    !anyPTWDone && lookupValid(port) && profile.tlbEnabled.B &&
    !laneBypass(port) && !laneAddressFault(port) &&
    laneTLBValid(port) && laneTLBHit(port)
  })
  val laneAddressFaultDone = VecInit((0 until lookupPorts).map { port =>
    !anyPTWDone && lookupValid(port) && !laneBypass(port) && laneAddressFault(port)
  })
  val laneBypassDone = VecInit((0 until lookupPorts).map { port =>
    !anyPTWDone && lookupValid(port) && laneBypass(port)
  })

  def writeResponse(valid: Bool, index: UInt, seq: UInt, response: ysyx_26030103_MMUResponse): Unit = {
    when(valid) {
      responseValid(seq(tableBits - 1, 0)) := true.B
      responseData(seq(tableBits - 1, 0))  := response
      contextValid(index)                  := false.B
      contextState(index)                  := contextFree
    }
  }

  val ptwResponseVec = WireInit(VecInit(Seq.fill(profile.refillPorts)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, addressWidth)))))
  for (port <- 0 until profile.refillPorts) {
    ptwResponseVec(port).id    := context(ptwContextIndexVec(port)).req.id
    ptwResponseVec(port).paddr := ptwResponsePayloadVec(port).paddr
    ptwResponseVec(port).fault := ptwResponsePayloadVec(port).fault
    ptwResponseVec(port).cause := ptwResponsePayloadVec(port).cause
    ptwResponseVec(port).tval  := ptwResponsePayloadVec(port).tval
  }

  val laneHitResponse    = WireInit(VecInit(Seq.fill(lookupPorts)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, addressWidth)))))
  val laneBypassResponse = WireInit(VecInit(Seq.fill(lookupPorts)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, addressWidth)))))
  val laneAddressFaultResponse = WireInit(VecInit(Seq.fill(lookupPorts)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, addressWidth)))))
  for (port <- 0 until lookupPorts) {
    laneHitResponse(port).id    := lookupRequest(port).id
    laneHitResponse(port).paddr := lanePhysicalAddressVec(port)
    laneHitResponse(port).fault := laneTLBFault(port)
    laneHitResponse(port).cause := laneCauseVec(port)
    laneHitResponse(port).tval  := lookupRequest(port).vaddr

    laneBypassResponse(port).id    := lookupRequest(port).id
    laneBypassResponse(port).paddr := lookupRequest(port).vaddr
    laneBypassResponse(port).fault := false.B
    laneBypassResponse(port).cause := 0.U
    laneBypassResponse(port).tval  := lookupRequest(port).vaddr

    laneAddressFaultResponse(port).id    := lookupRequest(port).id
    laneAddressFaultResponse(port).paddr := 0.U
    laneAddressFaultResponse(port).fault := true.B
    laneAddressFaultResponse(port).cause := Mux(
      lookupRequest(port).access === ysyx_26030103_AccessType.Fetch, 12.U,
      Mux(lookupRequest(port).access === ysyx_26030103_AccessType.Store, 15.U, 13.U))
    laneAddressFaultResponse(port).tval  := lookupRequest(port).vaddr
  }

  // 两个查找端口可以在同一拍各写一个响应表项；表项按请求序号索引，
  // 因此同拍完成不会破坏向软件的响应顺序。
  for (port <- 0 until profile.refillPorts) {
    when(ptwContextValidVec(port)) {
      writeResponse(true.B, ptwContextIndexVec(port), contextSeq(ptwContextIndexVec(port)), ptwResponseVec(port))
      // 响应被接收后释放对应 walk 槽。PTW 响应 ready 恒为有效响应，
      // 因而与 PTW 内部上下文释放保持同拍。
      walkValid(walkIndex(ptwSafeTagVec(port))) := false.B
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
    lookupValid(port) && profile.tlbEnabled.B && !laneBypass(port) &&
    !laneAddressFault(port) && laneTLBValid(port) && !laneTLBHit(port)
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

  if (profile.tlbEnabled && profile.tlbMissQueueMode == "follow_tlb" && split) {
    val instructionCandidates = (0 until lookupPorts).map(port => laneMiss(port) && laneFetch(port))
    val dataCandidates = (0 until lookupPorts).map(port => laneMiss(port) && !laneFetch(port))
    enqueueMiss(itlbMissQueueOpt.get, instructionCandidates)
    enqueueMiss(dtlbMissQueueOpt.get, dataCandidates)
    commitMissEnqueue(itlbMissQueueOpt.get, instructionCandidates)
    commitMissEnqueue(dtlbMissQueueOpt.get, dataCandidates)
  } else if (profile.tlbEnabled && profile.tlbMissQueueMode == "follow_tlb") {
    val unifiedCandidates = (0 until lookupPorts).map(port => laneMiss(port))
    enqueueMiss(utlbMissQueueOpt.get, unifiedCandidates)
    commitMissEnqueue(utlbMissQueueOpt.get, unifiedCandidates)
  } else if (profile.tlbEnabled) {
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
  val ptwCanIssue = anyFreeWalk && !anyPTWDone && !io.flush
  val roundRobinITLB      = RegInit(true.B)

  val queueIssue = Wire(Bool())
  val queueContext   = Wire(UInt(contextBits.W))
  queueIssue := false.B
  queueContext   := 0.U

  if (profile.tlbEnabled && profile.tlbMissQueueMode == "follow_tlb" && split) {
    val iValid  = itlbMissQueueOpt.get.io.deq.valid
    val dValid  = dtlbMissQueueOpt.get.io.deq.valid
    val chooseInstruction = iValid && (!dValid || roundRobinITLB)
    val chooseData = dValid && !chooseInstruction
    queueIssue                    := (chooseInstruction || chooseData) && ptwCanIssue && ptw.io.req.ready
    queueContext                      := Mux(chooseInstruction, itlbMissQueueOpt.get.io.deq.bits, dtlbMissQueueOpt.get.io.deq.bits)
    itlbMissQueueOpt.get.io.deq.ready := queueIssue && chooseInstruction
    dtlbMissQueueOpt.get.io.deq.ready := queueIssue && chooseData
    when(queueIssue && chooseInstruction) { roundRobinITLB := false.B }
    when(queueIssue && chooseData) { roundRobinITLB := true.B }
  } else if (profile.tlbEnabled && profile.tlbMissQueueMode == "follow_tlb") {
    queueIssue                    := utlbMissQueueOpt.get.io.deq.valid && ptwCanIssue &&
      ptw.io.req.ready
    queueContext                      := utlbMissQueueOpt.get.io.deq.bits
    utlbMissQueueOpt.get.io.deq.ready := queueIssue
  } else if (profile.tlbEnabled) {
    queueIssue                      := globalMissQueueOpt.get.io.deq.valid && ptwCanIssue &&
      ptw.io.req.ready
    queueContext                        := globalMissQueueOpt.get.io.deq.bits
    globalMissQueueOpt.get.io.deq.ready := queueIssue
  }

  // 无 TLB 时没有 miss queue，保留原行为：contextLookup 直接占用 PTW。
  val directIssueNeeded =
    !profile.tlbEnabled.B && lookupValid(0) && !laneBypass(0) &&
      !laneAddressFault(0) && !anyPTWDone && anyFreeWalk && !io.flush
  val issueContext          = Mux(directIssueNeeded, lookupIndex(0), queueContext)

  def requestPTW(contextIndex: UInt): Unit = {
    ptw.io.req.valid          := true.B
    ptw.io.req.bits.id        := freeWalkIndex.pad(4)
    ptw.io.req.bits.vaddr     := context(contextIndex).req.vaddr
    ptw.io.req.bits.satp      := context(contextIndex).req.satp
    ptw.io.req.bits.privilege := context(contextIndex).req.privilege
    ptw.io.req.bits.access    := context(contextIndex).req.access
    ptw.io.req.bits.sum       := (if (profile.hasU) context(contextIndex).req.sum else false.B)
    ptw.io.req.bits.mxr       := context(contextIndex).req.mxr
    when(ptw.io.req.fire) {
      contextState(contextIndex)     := contextWalk
      walkValid(freeWalkIndex) := true.B
      walkContext(freeWalkIndex)   := contextIndex
    }
  }

  ptw.io.req.valid := directIssueNeeded || queueIssue
  ptw.io.req.bits  := 0.U.asTypeOf(
    new ysyx_26030103_PTWRequest(xlen, addressWidth))
  when(directIssueNeeded || queueIssue) { requestPTW(issueContext) }
  for (port <- 0 until profile.refillPorts) {
    ptw.io.resp(port).ready := ptwDoneVec(port)
  }
  ptw.io.flush := io.flush

  // PTW 回填 TLB。多个上下文可以并行 walk，但页表总线仍由 PTW 仲裁。
  val fillFetch = VecInit(
    (0 until profile.refillPorts).map(port => context(ptwContextIndexVec(port)).req.access === ysyx_26030103_AccessType.Fetch)
  )
  if (split) {
    itlbOpt.foreach { tlb =>
      for (port <- 0 until profile.refillPorts) {
        connectFill(tlb, port, ptwContextValidVec(port) && ptwResponsePayloadVec(port).fillValid && fillFetch(port))
      }
    }
    dtlbOpt.foreach { tlb =>
      for (port <- 0 until profile.refillPorts) {
        connectFill(tlb, port, ptwContextValidVec(port) && ptwResponsePayloadVec(port).fillValid && !fillFetch(port))
      }
    }
  } else {
    utlbOpt.foreach { tlb =>
      for (port <- 0 until profile.refillPorts) {
        connectFill(tlb, port, ptwContextValidVec(port) && ptwResponsePayloadVec(port).fillValid)
      }
    }
  }

  // 接收新请求：空上下文槽 + 请求队列非空。
  val freeCandidates = VecInit((0 until contexts).map(i => !contextValid(i)))
  val anyFree        = freeCandidates.reduce(_ || _)
  val freeIndex      = PriorityEncoder(freeCandidates)
  requestQueue.io.deq.ready := flushDrain || (anyFree && !io.flush)
  when(requestQueue.io.deq.fire && !flushDrain) {
    context(freeIndex).req  := requestQueue.io.deq.bits
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
  when(io.resp.fire) {
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
      !laneBypass(port) && laneTLBHit(port)
  )).reduce(_ || _)
  val perfFetchMiss = VecInit((0 until lookupPorts).map(port =>
    lookupValid(port) && lookupRequest(port).access === ysyx_26030103_AccessType.Fetch &&
      profile.tlbEnabled.B && !laneBypass(port) && !laneTLBHit(port)
  )).reduce(_ || _)
  val perfDataHit = VecInit((0 until lookupPorts).map(port =>
    lookupValid(port) && lookupRequest(port).access =/= ysyx_26030103_AccessType.Fetch &&
      !laneBypass(port) && laneTLBHit(port)
  )).reduce(_ || _)
  val perfDataMiss = VecInit((0 until lookupPorts).map(port =>
    lookupValid(port) && lookupRequest(port).access =/= ysyx_26030103_AccessType.Fetch &&
      profile.tlbEnabled.B && !laneBypass(port) && !laneTLBHit(port)
  )).reduce(_ || _)
  val perfTLBRefill = VecInit((0 until profile.refillPorts).map(port =>
    ptwContextValidVec(port) && ptwResponsePayloadVec(port).fillValid
  )).reduce(_ || _)
  io.perf_itlb_hit := perfFetchHit
  io.perf_itlb_miss := perfFetchMiss
  io.perf_dtlb_hit := perfDataHit
  io.perf_dtlb_miss := perfDataMiss
  io.perf_tlb_refill := perfTLBRefill
  io.perf_ptw_walk := directIssueNeeded || queueIssue
  io.perf_ptw_walk_cycle := walkValid.reduce(_ || _)
  io.perf_page_fault :=
    responseValid(headIndex) && io.resp.ready && responseData(headIndex).fault
  io.perf_superpage_hit := VecInit((0 until lookupPorts).map(port =>
    lookupValid(port) && !laneBypass(port) && laneHit(port) && laneSuperpage(port)
  )).reduce(_ || _)
  io.perf_asid_hit := VecInit((0 until lookupPorts).map(port =>
    lookupValid(port) && !laneBypass(port) && laneHit(port) && laneASIDHit(port)
  )).reduce(_ || _)

  ptw.io.bus.req <> io.bus.req
  ptw.io.bus.resp <> io.bus.resp
}
