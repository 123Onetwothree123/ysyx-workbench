package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile

/** Sv32 MMU：请求接收 + TLB 查找 + 真实 TLB miss queue + 多上下文 PTW。
  *
  * 请求先在上下文中做 TLB 查找；命中/旁路直接生成响应，缺失则进入 ITLB/DTLB/UTLB 各自的 miss queue（follow_tlb）或共享的全局 miss queue （global）。只有 miss queue
  * 队首才会占用 PTW walk 槽，因此 TLB 查找、 miss 排队和页表 walk 是三个解耦的阶段。
  *
  * PTW 请求 ID 使用独立的 walk 槽编号（0..ptwOutstanding-1），不再直接 复用上下文编号；上下文数可以大于 16，同时仍满足 PTW 事务 ID 的 4 位 限制。每个请求在进入 MMU
  * 时分配序号，完成结果按序号写入响应表， 因此多队列/多 walker 的乱序完成不会破坏软件可见的响应顺序。
  */
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
  private val ctxBits     = log2Ceil(contexts.max(2))
  private val tableSize   = 1 << log2Ceil((contexts * 2).max(2))
  private val tableBits   = log2Ceil(tableSize)
  private val seqBits     = 16
  private val walkTagBits = log2Ceil(walkSlots.max(2))

  val io = IO(new Bundle {
    val req   = Flipped(Decoupled(new ysyx_26030103_MMURequest(xlen, addressWidth)))
    val resp  = Decoupled(new ysyx_26030103_MMUResponse(xlen, addressWidth))
    val bus   = new ysyx_26030103_PTEBusIO(addressWidth)
    val flush = Input(Bool())
  })

  val ctxFree :: ctxLookup :: ctxMissWait :: ctxWalk :: Nil = Enum(4)
  class Context extends Bundle {
    val req = new ysyx_26030103_MMURequest(xlen, addressWidth)
    val seq = UInt(seqBits.W)
  }
  val ctx = RegInit(VecInit(Seq.fill(contexts)(0.U.asTypeOf(new Context))))
  val ctxValid = RegInit(VecInit(Seq.fill(contexts)(false.B)))
  val ctxState = RegInit(VecInit(Seq.fill(contexts)(ctxFree)))
  val ctxSeq   = RegInit(VecInit(Seq.fill(contexts)(0.U(seqBits.W))))

  // walk 槽与上下文解耦：PTW 事务 ID 只需在 0..walkSlots-1 内唯一。
  val walkValid = RegInit(VecInit(Seq.fill(walkSlots)(false.B)))
  val walkCtx   = RegInit(VecInit(Seq.fill(walkSlots)(0.U(ctxBits.W))))

  val nextSeq   = RegInit(0.U(seqBits.W))
  val headSeq   = RegInit(0.U(seqBits.W))
  val respValid = RegInit(VecInit(Seq.fill(tableSize)(false.B)))
  val respData  = RegInit(VecInit(Seq.fill(tableSize)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, addressWidth)))))

  private def makeMissQueue(entries: Int): ysyx_26030103_TLBMissQueue =
    Module(new ysyx_26030103_TLBMissQueue(entries = entries, width = ctxBits))

  // follow_tlb：split 下 I/D 各一条队列；unified 下只有 UTLB 队列。
  // global：不区分访问类型，共用一条队列。
  val itlbMissQOpt   =
    if (profile.tlbEnabled && split && profile.tlbMissQueueMode == "follow_tlb")
      Some(makeMissQueue(profile.itlbMissQueueEntries))
    else None
  val dtlbMissQOpt   =
    if (profile.tlbEnabled && split && profile.tlbMissQueueMode == "follow_tlb")
      Some(makeMissQueue(profile.dtlbMissQueueEntries))
    else None
  val utlbMissQOpt   =
    if (profile.tlbEnabled && !split && profile.tlbMissQueueMode == "follow_tlb")
      Some(makeMissQueue(profile.utlbMissQueueEntries))
    else None
  val globalMissQOpt =
    if (profile.tlbEnabled && profile.tlbMissQueueMode == "global")
      Some(makeMissQueue(profile.globalMissQueueEntries))
    else None

  val reqQueue = Module(
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
  reqQueue.io.enq.valid := io.req.valid && !io.flush && !flushDrain
  reqQueue.io.enq.bits  := io.req.bits
  io.req.ready          := reqQueue.io.enq.ready && !io.flush && !flushDrain

  def makeTlb(entries: Int, ways: Int) = Module(
    new ysyx_26030103_TLB(
      entries = entries,
      ways = ways,
      asidBits = profile.tlbAsidBits,
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
      ptePpnLow = profile.pteConfig.ppnLow,
      ptePpnHigh = profile.pteConfig.ppnHigh
    )
  )

  val itlbOpt =
    if (profile.tlbEnabled && split) Some(makeTlb(profile.itlbEntries, profile.itlbWays))
    else None
  val dtlbOpt =
    if (profile.tlbEnabled && split) Some(makeTlb(profile.dtlbEntries, profile.dtlbWays))
    else None
  val utlbOpt =
    if (profile.tlbEnabled && !split) Some(makeTlb(profile.utlbEntries, profile.utlbWays))
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

  // 每拍为 unified TLB 选择最多两个不同的 ctxLookup 上下文：端口 0 选
  // 编号最小的候选，端口 1 在剩余候选中再选一个。split 只有一个端口。
  val lookupValid      = Wire(Vec(lookupPorts, Bool()))
  val lookupIndex      = Wire(Vec(lookupPorts, UInt(ctxBits.W)))
  val lookupReq        = Wire(Vec(lookupPorts,
    new ysyx_26030103_MMURequest(xlen, addressWidth)))
  val lookupCandidates = VecInit((0 until contexts).map { i =>
    ctxValid(i) && ctxState(i) === ctxLookup
  })
  for (p <- 0 until lookupPorts) {
    lookupValid(p) := false.B
    lookupIndex(p) := 0.U
    lookupReq(p)   := 0.U.asTypeOf(new ysyx_26030103_MMURequest(xlen, addressWidth))
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
  for (p <- 0 until lookupPorts) {
    when(lookupValid(p)) { lookupReq(p) := ctx(lookupIndex(p)).req }
  }

  def connectTlb(tlb: ysyx_26030103_TLB):                          Unit = {
    for (p <- 0 until lookupPorts) {
      tlb.io.lookup(p).vaddr     := lookupReq(p).vaddr
      tlb.io.lookup(p).asid      := satpAsid(lookupReq(p).satp)
      tlb.io.lookup(p).privilege := lookupReq(p).privilege
      tlb.io.lookup(p).access    := lookupReq(p).access
      tlb.io.lookup(p).sum       :=
        (if (profile.hasU) lookupReq(p).sum else false.B)
      tlb.io.lookup(p).mxr       := lookupReq(p).mxr
    }
    tlb.io.flush := io.flush
    tlb.io.fillValid := false.B
    tlb.io.fillVaddr := 0.U
    tlb.io.fillAsid  := 0.U
    tlb.io.fillPte   := 0.U
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
    val vaddr = ptwRespVec(port).fillVaddr
    val asid  = ptwRespVec(port).fillAsid
    val pte   = ptwRespVec(port).fillPte
    val level = ptwRespVec(port).fillLevel
    if (port == 0) {
      tlb.io.fillValid := valid
      tlb.io.fillVaddr := vaddr
      tlb.io.fillAsid  := asid
      tlb.io.fillPte   := pte
      tlb.io.fillLevel := level
    } else {
      tlb.io.fillExtra.get(port - 1).valid := valid
      tlb.io.fillExtra.get(port - 1).vaddr := vaddr
      tlb.io.fillExtra.get(port - 1).asid  := asid
      tlb.io.fillExtra.get(port - 1).pte   := pte
      tlb.io.fillExtra.get(port - 1).level := level
    }
  }

  itlbOpt.foreach(connectTlb)
  dtlbOpt.foreach(connectTlb)
  utlbOpt.foreach(connectTlb)

  def optBool(opt: Option[ysyx_26030103_TLB], f: ysyx_26030103_TLB => Bool): Bool =
    opt.map(f).getOrElse(false.B)
  def optUInt(opt: Option[ysyx_26030103_TLB], f: ysyx_26030103_TLB => UInt): UInt =
    opt.map(f).getOrElse(0.U)

  // 响应顺序：每个请求分配一个序号，完成结果写入按序号索引的表，
  // 只有队首完成时才向软件返回，后面的尽早完成也不会越过前面。
  val headIdx = headSeq(tableBits - 1, 0)
  io.resp.valid := respValid(headIdx)
  io.resp.bits  := respData(headIdx)

  // split 下两个 TLB 都只有一个查找端口；unified 的端口 1 只在
  // lookupPorts=2 时存在，下面按端口号从对应 TLB 实例取结果。
  def laneResultValid(p: Int): Bool =
    if (!profile.tlbEnabled) true.B
    else if (split)
      Mux(
        lookupReq(p).access === ysyx_26030103_AccessType.Fetch,
        optBool(itlbOpt, _.io.result(0).resultValid),
        optBool(dtlbOpt, _.io.result(0).resultValid)
      )
    else optBool(utlbOpt, _.io.result(p).resultValid)
  def laneHit(p: Int):         Bool =
    if (!profile.tlbEnabled) false.B
    else if (split)
      Mux(
        lookupReq(p).access === ysyx_26030103_AccessType.Fetch,
        optBool(itlbOpt, _.io.result(0).hit),
        optBool(dtlbOpt, _.io.result(0).hit)
      )
    else optBool(utlbOpt, _.io.result(p).hit)
  def laneFault(p: Int):       Bool =
    if (!profile.tlbEnabled) false.B
    else if (split)
      Mux(
        lookupReq(p).access === ysyx_26030103_AccessType.Fetch,
        optBool(itlbOpt, _.io.result(0).fault),
        optBool(dtlbOpt, _.io.result(0).fault)
      )
    else optBool(utlbOpt, _.io.result(p).fault)
  def lanePaddr(p: Int):       UInt =
    if (!profile.tlbEnabled) lookupReq(p).vaddr
    else if (split)
      Mux(
        lookupReq(p).access === ysyx_26030103_AccessType.Fetch,
        optUInt(itlbOpt, _.io.result(0).paddr),
        optUInt(dtlbOpt, _.io.result(0).paddr)
      )
    else optUInt(utlbOpt, _.io.result(p).paddr)
  def laneCause(p: Int):       UInt =
    if (!profile.tlbEnabled) 0.U
    else if (split)
      Mux(
        lookupReq(p).access === ysyx_26030103_AccessType.Fetch,
        optUInt(itlbOpt, _.io.result(0).cause),
        optUInt(dtlbOpt, _.io.result(0).cause)
      )
    else optUInt(utlbOpt, _.io.result(p).cause)

  // satp 字段位置按 XLEN 选择：RV32 mode=31, asid=30:22, ppn=21:0；
  // RV64 mode=63:60, asid=59:44, ppn=43:0。
  def satpModeOf(satp: UInt): UInt =
    if (xlen == 32) satp(31) else satp(63, 60)
  def satpAsidOf(satp: UInt): UInt =
    if (xlen == 32) satp(30, 22) else satp(59, 44)
  def satpAsid(satp: UInt): UInt = satpAsidOf(satp)
  /** Sv39/Sv48 非规范虚拟地址必须在 TLB 之前就判定为页错误。 */
  def canonicalOk(vaddr: UInt): Bool = {
    if (vaBits == xlen) true.B
    else {
      val upper = vaddr(xlen - 1, vaBits)
      val sign = Fill(xlen - vaBits, vaddr(vaBits - 1))
      upper === sign
    }
  }

  val laneTlbValid = VecInit((0 until lookupPorts).map(laneResultValid))
  val laneTlbHit   = VecInit((0 until lookupPorts).map(laneHit))
  val laneTlbFault = VecInit((0 until lookupPorts).map(laneFault))
  val lanePaddrVec = VecInit((0 until lookupPorts).map(lanePaddr))
  val laneCauseVec = VecInit((0 until lookupPorts).map(laneCause))
  val laneBypass   = VecInit((0 until lookupPorts).map { p =>
    !profile.enabled.B || satpModeOf(lookupReq(p).satp) === 0.U ||
    lookupReq(p).privilege === 3.U
  })
  // 只有配置对应的 MODE 才允许翻译。CSR 已对 satp.MODE 做 WARL 收敛，
  // 这里再检查一次直接驱动 MMU 的场景：CUSTOM 下写入标准 8/9，或
  // STANDARD 下写入另一标准模式，都必须产生页错误，不能拿另一种页表
  // 几何继续翻译。
  val laneModeInvalid = VecInit((0 until lookupPorts).map { p =>
    !laneBypass(p) && satpModeOf(lookupReq(p).satp) =/= profile.satpMode.U
  })
  val laneNonCanonical = VecInit((0 until lookupPorts).map { p =>
    !laneBypass(p) && !canonicalOk(lookupReq(p).vaddr)
  })
  // 模式错误优先于非规范地址：两者都只产生页错误，不会访问页表。
  val laneAddressFault = VecInit((0 until lookupPorts).map { p =>
    laneModeInvalid(p) || laneNonCanonical(p)
  })

  val ptwDoneVec       = VecInit((0 until profile.refillPorts).map(p => ptw.io.resp(p).valid))
  // PTW 响应携带的是 walk 槽编号；先映射回上下文，再做状态/有效检查。
  val ptwTagVec        = VecInit((0 until profile.refillPorts).map(p => ptw.io.resp(p).bits.id(walkTagBits - 1, 0)))
  val ptwTagInRangeVec = VecInit((0 until profile.refillPorts).map(p => ptw.io.resp(p).bits.id < walkSlots.U))
  // 越界 ID 用安全索引 0 读取，最终由 ptwTagInRangeVec/状态检查丢弃。
  val ptwSafeTagVec    = VecInit(
    (0 until profile.refillPorts).map(p => Mux(ptwTagInRangeVec(p), ptwTagVec(p), 0.U(walkTagBits.W)))
  )
  // walkSlots=1 时 Vec 动态索引位宽必须为 0，避免多余的一位的无效索引。
  def walkIndex(tag: UInt): UInt =
    if (walkSlots == 1) 0.U(0.W) else tag
  val ptwCtxIdxVec   = VecInit((0 until profile.refillPorts).map(p => walkCtx(walkIndex(ptwSafeTagVec(p)))))
  val ptwCtxValidVec = VecInit(
    (0 until profile.refillPorts).map(p =>
      ptwDoneVec(p) && ptwTagInRangeVec(p) && walkValid(walkIndex(ptwSafeTagVec(p))) &&
        ctxValid(ptwCtxIdxVec(p)) &&
        ctxState(ptwCtxIdxVec(p)) === ctxWalk
    )
  )
  val ptwRespVec     = VecInit((0 until profile.refillPorts).map(p => ptw.io.resp(p).bits))
  val anyPtwDone     = ptwDoneVec.reduce(_ || _)

  // PTW 响应优先写回响应表；同一拍发生 PTW 响应时，本拍所有 TLB 命中
  // 都延迟到下一拍，避免 TLB 和 PTW 争用响应表写端口。
  val laneHitDone    = VecInit((0 until lookupPorts).map { p =>
    !anyPtwDone && lookupValid(p) && profile.tlbEnabled.B &&
    !laneBypass(p) && !laneAddressFault(p) &&
    laneTlbValid(p) && laneTlbHit(p)
  })
  val laneAddressFaultDone = VecInit((0 until lookupPorts).map { p =>
    !anyPtwDone && lookupValid(p) && !laneBypass(p) && laneAddressFault(p)
  })
  val laneBypassDone = VecInit((0 until lookupPorts).map { p =>
    !anyPtwDone && lookupValid(p) && laneBypass(p)
  })

  def writeResponse(valid: Bool, index: UInt, seq: UInt, response: ysyx_26030103_MMUResponse): Unit = {
    when(valid) {
      respValid(seq(tableBits - 1, 0)) := true.B
      respData(seq(tableBits - 1, 0))  := response
      ctxValid(index)                  := false.B
      ctxState(index)                  := ctxFree
    }
  }

  val ptwResponseVec = WireInit(VecInit(Seq.fill(profile.refillPorts)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, addressWidth)))))
  for (p <- 0 until profile.refillPorts) {
    ptwResponseVec(p).id    := ctx(ptwCtxIdxVec(p)).req.id
    ptwResponseVec(p).paddr := ptwRespVec(p).paddr
    ptwResponseVec(p).fault := ptwRespVec(p).fault
    ptwResponseVec(p).cause := ptwRespVec(p).cause
    ptwResponseVec(p).tval  := ptwRespVec(p).tval
  }

  val laneHitResponse    = WireInit(VecInit(Seq.fill(lookupPorts)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, addressWidth)))))
  val laneBypassResponse = WireInit(VecInit(Seq.fill(lookupPorts)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, addressWidth)))))
  val laneAddressFaultResponse = WireInit(VecInit(Seq.fill(lookupPorts)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse(xlen, addressWidth)))))
  for (p <- 0 until lookupPorts) {
    laneHitResponse(p).id    := lookupReq(p).id
    laneHitResponse(p).paddr := lanePaddrVec(p)
    laneHitResponse(p).fault := laneTlbFault(p)
    laneHitResponse(p).cause := laneCauseVec(p)
    laneHitResponse(p).tval  := lookupReq(p).vaddr

    laneBypassResponse(p).id    := lookupReq(p).id
    laneBypassResponse(p).paddr := lookupReq(p).vaddr
    laneBypassResponse(p).fault := false.B
    laneBypassResponse(p).cause := 0.U
    laneBypassResponse(p).tval  := lookupReq(p).vaddr

    laneAddressFaultResponse(p).id    := lookupReq(p).id
    laneAddressFaultResponse(p).paddr := 0.U
    laneAddressFaultResponse(p).fault := true.B
    laneAddressFaultResponse(p).cause := Mux(
      lookupReq(p).access === ysyx_26030103_AccessType.Fetch, 12.U,
      Mux(lookupReq(p).access === ysyx_26030103_AccessType.Store, 15.U, 13.U))
    laneAddressFaultResponse(p).tval  := lookupReq(p).vaddr
  }

  // 两个查找端口可以在同一拍各写一个响应表项；表项按请求序号索引，
  // 因此同拍完成不会破坏向软件的响应顺序。
  for (p <- 0 until profile.refillPorts) {
    when(ptwCtxValidVec(p)) {
      writeResponse(true.B, ptwCtxIdxVec(p), ctxSeq(ptwCtxIdxVec(p)), ptwResponseVec(p))
      // 响应被接收后释放对应 walk 槽。PTW 响应 ready 恒为有效响应，
      // 因而与 PTW 内部上下文释放保持同拍。
      walkValid(walkIndex(ptwSafeTagVec(p))) := false.B
    }
  }
  for (p <- 0 until lookupPorts) {
    when(laneAddressFaultDone(p)) {
      writeResponse(true.B, lookupIndex(p), ctxSeq(lookupIndex(p)),
        laneAddressFaultResponse(p))
    }.elsewhen(laneHitDone(p)) {
      writeResponse(true.B, lookupIndex(p), ctxSeq(lookupIndex(p)), laneHitResponse(p))
    }.elsewhen(laneBypassDone(p)) {
      writeResponse(true.B, lookupIndex(p), ctxSeq(lookupIndex(p)), laneBypassResponse(p))
    }
  }

  // TLB miss 入队
  // 只有“TLB 结果有效且确实缺失”的查找才会进入队列；SRAM TLB 结果
  // 未就绪时上下文留在 ctxLookup，下一拍继续查找。
  val laneMiss = VecInit((0 until lookupPorts).map { p =>
    lookupValid(p) && profile.tlbEnabled.B && !laneBypass(p) &&
    !laneAddressFault(p) && laneTlbValid(p) && !laneTlbHit(p)
  })
  def laneFetch(p: Int): Bool =
    lookupReq(p).access === ysyx_26030103_AccessType.Fetch

  def enqueueMiss(q: ysyx_26030103_TLBMissQueue, candidates: Seq[Bool]):       Unit = {
    val candVec = VecInit(candidates)
    val any     = candVec.reduce(_ || _)
    val sel     = PriorityEncoder(candVec)
    q.io.enq.valid := any
    q.io.enq.bits  := Mux(any, lookupIndex(sel), 0.U)
    q.io.flush     := io.flush
  }
  def commitMissEnqueue(q: ysyx_26030103_TLBMissQueue, candidates: Seq[Bool]): Unit = {
    val candVec = VecInit(candidates)
    val any     = candVec.reduce(_ || _)
    val sel     = PriorityEncoder(candVec)
    when(q.io.enq.fire) {
      ctxState(lookupIndex(sel)) := ctxMissWait
    }
  }

  if (profile.tlbEnabled && profile.tlbMissQueueMode == "follow_tlb" && split) {
    val iCands = (0 until lookupPorts).map(p => laneMiss(p) && laneFetch(p))
    val dCands = (0 until lookupPorts).map(p => laneMiss(p) && !laneFetch(p))
    enqueueMiss(itlbMissQOpt.get, iCands)
    enqueueMiss(dtlbMissQOpt.get, dCands)
    commitMissEnqueue(itlbMissQOpt.get, iCands)
    commitMissEnqueue(dtlbMissQOpt.get, dCands)
  } else if (profile.tlbEnabled && profile.tlbMissQueueMode == "follow_tlb") {
    val uCands = (0 until lookupPorts).map(p => laneMiss(p))
    enqueueMiss(utlbMissQOpt.get, uCands)
    commitMissEnqueue(utlbMissQOpt.get, uCands)
  } else if (profile.tlbEnabled) {
    val gCands = (0 until lookupPorts).map(p => laneMiss(p))
    enqueueMiss(globalMissQOpt.get, gCands)
    commitMissEnqueue(globalMissQOpt.get, gCands)
  }

  // miss queue -> PTW 调度
  // PTW 只有一个请求端口，每拍最多启动一笔 walk。split follow_tlb 下
  // I/D 队列用一位 round-robin 防止一侧长期饿死；global/unified 只有
  // 一条队列，直接取队首。
  val freeWalkVec = VecInit((0 until walkSlots).map(i => !walkValid(i)))
  val anyFreeWalk = freeWalkVec.reduce(_ || _)
  val freeWalkIdx =
    if (walkSlots == 1) 0.U(walkTagBits.W)
    else PriorityEncoder(freeWalkVec)
  val ptwCanIssue = anyFreeWalk && !anyPtwDone && !io.flush
  val rrITLB      = RegInit(true.B)

  val queueIssue = Wire(Bool())
  val queueCtx   = Wire(UInt(ctxBits.W))
  queueIssue := false.B
  queueCtx   := 0.U

  if (profile.tlbEnabled && profile.tlbMissQueueMode == "follow_tlb" && split) {
    val iValid  = itlbMissQOpt.get.io.deq.valid
    val dValid  = dtlbMissQOpt.get.io.deq.valid
    val chooseI = iValid && (!dValid || rrITLB)
    val chooseD = dValid && !chooseI
    queueIssue                    := (chooseI || chooseD) && ptwCanIssue && ptw.io.req.ready
    queueCtx                      := Mux(chooseI, itlbMissQOpt.get.io.deq.bits, dtlbMissQOpt.get.io.deq.bits)
    itlbMissQOpt.get.io.deq.ready := queueIssue && chooseI
    dtlbMissQOpt.get.io.deq.ready := queueIssue && chooseD
    when(queueIssue && chooseI) { rrITLB := false.B }
    when(queueIssue && chooseD) { rrITLB := true.B }
  } else if (profile.tlbEnabled && profile.tlbMissQueueMode == "follow_tlb") {
    queueIssue                    := utlbMissQOpt.get.io.deq.valid && ptwCanIssue &&
      ptw.io.req.ready
    queueCtx                      := utlbMissQOpt.get.io.deq.bits
    utlbMissQOpt.get.io.deq.ready := queueIssue
  } else if (profile.tlbEnabled) {
    queueIssue                      := globalMissQOpt.get.io.deq.valid && ptwCanIssue &&
      ptw.io.req.ready
    queueCtx                        := globalMissQOpt.get.io.deq.bits
    globalMissQOpt.get.io.deq.ready := queueIssue
  }

  // 无 TLB 时没有 miss queue，保留原行为：ctxLookup 直接占用 PTW。
  val directIssueNeeded =
    !profile.tlbEnabled.B && lookupValid(0) && !laneBypass(0) &&
      !laneAddressFault(0) && !anyPtwDone && anyFreeWalk && !io.flush
  val issueCtx          = Mux(directIssueNeeded, lookupIndex(0), queueCtx)

  def requestPtw(ctxIndex: UInt): Unit = {
    ptw.io.req.valid          := true.B
    ptw.io.req.bits.id        := freeWalkIdx.pad(4)
    ptw.io.req.bits.vaddr     := ctx(ctxIndex).req.vaddr
    ptw.io.req.bits.satp      := ctx(ctxIndex).req.satp
    ptw.io.req.bits.privilege := ctx(ctxIndex).req.privilege
    ptw.io.req.bits.access    := ctx(ctxIndex).req.access
    ptw.io.req.bits.sum       := (if (profile.hasU) ctx(ctxIndex).req.sum else false.B)
    ptw.io.req.bits.mxr       := ctx(ctxIndex).req.mxr
    when(ptw.io.req.fire) {
      ctxState(ctxIndex)     := ctxWalk
      walkValid(freeWalkIdx) := true.B
      walkCtx(freeWalkIdx)   := ctxIndex
    }
  }

  ptw.io.req.valid := directIssueNeeded || queueIssue
  ptw.io.req.bits  := 0.U.asTypeOf(
    new ysyx_26030103_PTWRequest(xlen, addressWidth))
  when(directIssueNeeded || queueIssue) { requestPtw(issueCtx) }
  for (p <- 0 until profile.refillPorts) {
    ptw.io.resp(p).ready := ptwDoneVec(p)
  }
  ptw.io.flush := io.flush

  // PTW 回填 TLB。多个上下文可以并行 walk，但页表总线仍由 PTW 仲裁。
  val fillFetch = VecInit(
    (0 until profile.refillPorts).map(p => ctx(ptwCtxIdxVec(p)).req.access === ysyx_26030103_AccessType.Fetch)
  )
  if (split) {
    itlbOpt.foreach { tlb =>
      for (p <- 0 until profile.refillPorts) {
        connectFill(tlb, p, ptwCtxValidVec(p) && ptwRespVec(p).fillValid && fillFetch(p))
      }
    }
    dtlbOpt.foreach { tlb =>
      for (p <- 0 until profile.refillPorts) {
        connectFill(tlb, p, ptwCtxValidVec(p) && ptwRespVec(p).fillValid && !fillFetch(p))
      }
    }
  } else {
    utlbOpt.foreach { tlb =>
      for (p <- 0 until profile.refillPorts) {
        connectFill(tlb, p, ptwCtxValidVec(p) && ptwRespVec(p).fillValid)
      }
    }
  }

  // 接收新请求：空上下文槽 + 请求队列非空。
  val freeCandidates = VecInit((0 until contexts).map(i => !ctxValid(i)))
  val anyFree        = freeCandidates.reduce(_ || _)
  val freeIndex      = PriorityEncoder(freeCandidates)
  reqQueue.io.deq.ready := flushDrain || (anyFree && !io.flush)
  when(reqQueue.io.deq.fire && !flushDrain) {
    ctx(freeIndex).req  := reqQueue.io.deq.bits
    ctxSeq(freeIndex)   := nextSeq
    ctxValid(freeIndex) := true.B
    ctxState(freeIndex) := ctxLookup
    nextSeq             := nextSeq + 1.U
  }
  // io.flush 当拍置位，队列真正排空后撤销。队列空时 deq.ready 仍保持
  // 高电平，但 deq.valid 为低，不会产生虚假握手。
  when(io.flush) {
    flushDrain := true.B
  }.elsewhen(reqQueue.io.count === 0.U) {
    flushDrain := false.B
  }

  // 响应表队首出队。
  when(io.resp.fire) {
    respValid(headIdx) := false.B
    headSeq            := headSeq + 1.U
  }

  when(io.flush) {
    for (i <- 0 until contexts) {
      ctxValid(i) := false.B
      ctxState(i) := ctxFree
    }
    for (i <- 0 until walkSlots) { walkValid(i) := false.B }
    for (i <- 0 until tableSize) { respValid(i) := false.B }
    headSeq := 0.U
    nextSeq := 0.U
    rrITLB  := true.B
  }

  ptw.io.bus.req <> io.bus.req
  ptw.io.bus.resp <> io.bus.resp
}
