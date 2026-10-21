package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile

/** Sv32 MMU：请求队列 + 多上下文 PTW 调度。
  *
  * 每个在途请求拥有独立上下文；TLB 命中可直接完成，TLB 缺失则把上下文
  * 交给 PTWCluster。PTWCluster 的多个 walker 可以同时工作，响应按请求
  * 序号而不是完成顺序返回，保持架构顺序。unified TLB 打开双查找端口时，
  * 每拍可以让两个 ctxLookup 上下文并行查找。
  */
class ysyx_26030103_MMU(
    profile: ysyx_26030103_MMUProfile = ysyx_26030103_MMUProfile()
) extends Module {
  require(profile.pteSizeBytes == 4 || profile.pteSizeBytes == 8,
    "PTE 大小只支持 4/8 字节")
  require(profile.levels >= 1 && profile.levels <= 4,
    "页表级数必须在 1..4")
  require(profile.pageBits + profile.levels * profile.indexBitsPerLevel == 32,
    "pageBits + levels*indexBits 必须等于 32")
  require(!profile.tlbSuperpage || (profile.pageSizeBytes == 4096 && profile.levels == 2),
    "TLB 超级页当前只支持 4KiB/2 级 Sv32")
  require(!profile.tlbSuperpage || profile.superpage, "TLB_SUPERPAGE 需要 MMU_SUPERPAGE")
  require(profile.ptwOutstanding >= 1 && profile.ptwOutstanding <= 16,
    "MMU_PTW_OUTSTANDING 必须在 1..16")
  require(profile.refillPorts >= 1, "TLB refill 端口数必须 >= 1")
  require(Set("follow_tlb", "global").contains(profile.tlbMissQueueMode),
    "TLB miss queue 模式必须是 follow_tlb/global")

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new ysyx_26030103_MMURequest))
    val resp = Decoupled(new ysyx_26030103_MMUResponse)
    val bus = new ysyx_26030103_PTEBusIO
    val flush = Input(Bool())
  })

  private val contexts = profile.ptwOutstanding.max(1)
  private val refillPorts = profile.refillPorts.max(1)
  private val split = profile.tlbOrganization == "split"
  // split 的 I/D TLB 各自只有 1 个查找端口；unified TLB 可按 Kconfig
  // 打开第 2 个端口，让两个 ctxLookup 上下文同拍查找。
  private val lookupPorts =
    if (profile.tlbEnabled && !split) profile.utlbLookupPorts.max(1) else 1
  private val ctxBits = log2Ceil(contexts.max(2))
  private val tableSize = 1 << log2Ceil((contexts * 2).max(2))
  private val tableBits = log2Ceil(tableSize)
  private val seqBits = 16

  val ctxFree :: ctxLookup :: ctxWalk :: Nil = Enum(3)
  class Context extends Bundle {
    val req = new ysyx_26030103_MMURequest
    val seq = UInt(seqBits.W)
  }
  val ctx = RegInit(VecInit(Seq.fill(contexts)(0.U.asTypeOf(new Context))))
  val ctxValid = RegInit(VecInit(Seq.fill(contexts)(false.B)))
  val ctxState = RegInit(VecInit(Seq.fill(contexts)(ctxFree)))
  val ctxSeq = RegInit(VecInit(Seq.fill(contexts)(0.U(seqBits.W))))

  val nextSeq = RegInit(0.U(seqBits.W))
  val headSeq = RegInit(0.U(seqBits.W))
  val respValid = RegInit(VecInit(Seq.fill(tableSize)(false.B)))
  val respData = RegInit(VecInit(Seq.fill(tableSize)(0.U.asTypeOf(new ysyx_26030103_MMUResponse))))

  // 请求队列深度真正使用配置；follow_tlb 取各 TLB 队列最大值，global 使用共享深度。
  private val queueDepth = {
    val configured = if (profile.tlbMissQueueMode == "global")
      profile.globalMissQueueEntries
    else Seq(profile.itlbMissQueueEntries, profile.dtlbMissQueueEntries,
      profile.utlbMissQueueEntries).max
    configured.max(contexts).max(1)
  }
  val reqQueue = Module(new Queue(
    new ysyx_26030103_MMURequest,
    queueDepth,
    pipe = false,
    flow = false
  ))
  reqQueue.io.enq <> io.req
  io.req.ready := reqQueue.io.enq.ready

  def makeTlb(entries: Int, ways: Int) = Module(new ysyx_26030103_TLB(
    entries = entries,
    ways = ways,
    asidBits = profile.tlbAsidBits,
    globalEnable = profile.tlbGlobal,
    replacement = profile.tlbReplacement,
    adMode = profile.adMode,
    storage = profile.tlbStorage,
    superpage = profile.tlbSuperpage,
    pageBits = profile.pageBits,
    refillPorts = refillPorts,
    hasU = profile.hasU,
    lookupPorts = lookupPorts
  ))

  val itlbOpt =
    if (profile.tlbEnabled && split) Some(makeTlb(profile.itlbEntries, profile.itlbWays))
    else None
  val dtlbOpt =
    if (profile.tlbEnabled && split) Some(makeTlb(profile.dtlbEntries, profile.dtlbWays))
    else None
  val utlbOpt =
    if (profile.tlbEnabled && !split) Some(makeTlb(profile.utlbEntries, profile.utlbWays))
    else None

  val ptw = Module(new ysyx_26030103_PTWCluster(
    outstanding = contexts,
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
    respPorts = refillPorts,
    hasU = profile.hasU
  ))

  // 每拍为 unified TLB 选择最多两个不同的 ctxLookup 上下文：端口 0 选
  // 编号最小的候选，端口 1 在剩余候选中再选一个。split 只有一个端口。
  val lookupValid = Wire(Vec(lookupPorts, Bool()))
  val lookupIndex = Wire(Vec(lookupPorts, UInt(ctxBits.W)))
  val lookupReq = Wire(Vec(lookupPorts, new ysyx_26030103_MMURequest))
  val lookupCandidates = VecInit((0 until contexts).map { i =>
    ctxValid(i) && ctxState(i) === ctxLookup
  })
  for (p <- 0 until lookupPorts) {
    lookupValid(p) := false.B
    lookupIndex(p) := 0.U
    lookupReq(p) := 0.U.asTypeOf(new ysyx_26030103_MMURequest)
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

  def connectTlb(tlb: ysyx_26030103_TLB): Unit = {
    for (p <- 0 until lookupPorts) {
      tlb.io.lookup(p).vaddr := lookupReq(p).vaddr
      tlb.io.lookup(p).asid := lookupReq(p).satp(30, 22)
      tlb.io.lookup(p).privilege := lookupReq(p).privilege
      tlb.io.lookup(p).access := lookupReq(p).access
      tlb.io.lookup(p).sum := lookupReq(p).sum
      tlb.io.lookup(p).mxr := lookupReq(p).mxr
    }
    tlb.io.flush := io.flush
    tlb.io.fillValid := false.B
    tlb.io.fillVaddr := 0.U
    tlb.io.fillAsid := 0.U
    tlb.io.fillPte := 0.U
    tlb.io.fillLevel := 0.U
    tlb.io.fillExtra.foreach { ports =>
      for (port <- ports) {
        port.valid := false.B
        port.vaddr := 0.U
        port.asid := 0.U
        port.pte := 0.U
        port.level := 0.U
      }
    }
  }
  def connectFill(tlb: ysyx_26030103_TLB, port: Int, valid: Bool): Unit = {
    val vaddr = ptwRespVec(port).fillVaddr
    val asid = ptwRespVec(port).fillAsid
    val pte = ptwRespVec(port).fillPte
    val level = ptwRespVec(port).fillLevel
    if (port == 0) {
      tlb.io.fillValid := valid
      tlb.io.fillVaddr := vaddr
      tlb.io.fillAsid := asid
      tlb.io.fillPte := pte
      tlb.io.fillLevel := level
    } else {
      tlb.io.fillExtra.get(port - 1).valid := valid
      tlb.io.fillExtra.get(port - 1).vaddr := vaddr
      tlb.io.fillExtra.get(port - 1).asid := asid
      tlb.io.fillExtra.get(port - 1).pte := pte
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
  io.resp.bits := respData(headIdx)

  // split 下两个 TLB 都只有一个查找端口；unified 的端口 1 只在
  // lookupPorts=2 时存在，下面按端口号从对应 TLB 实例取结果。
  def laneResultValid(p: Int): Bool =
    if (!profile.tlbEnabled) true.B
    else if (split) Mux(lookupReq(p).access === ysyx_26030103_AccessType.Fetch,
      optBool(itlbOpt, _.io.result(0).resultValid),
      optBool(dtlbOpt, _.io.result(0).resultValid))
    else optBool(utlbOpt, _.io.result(p).resultValid)
  def laneHit(p: Int): Bool =
    if (split) Mux(lookupReq(p).access === ysyx_26030103_AccessType.Fetch,
      optBool(itlbOpt, _.io.result(0).hit),
      optBool(dtlbOpt, _.io.result(0).hit))
    else optBool(utlbOpt, _.io.result(p).hit)
  def laneFault(p: Int): Bool =
    if (split) Mux(lookupReq(p).access === ysyx_26030103_AccessType.Fetch,
      optBool(itlbOpt, _.io.result(0).fault),
      optBool(dtlbOpt, _.io.result(0).fault))
    else optBool(utlbOpt, _.io.result(p).fault)
  def lanePaddr(p: Int): UInt =
    if (split) Mux(lookupReq(p).access === ysyx_26030103_AccessType.Fetch,
      optUInt(itlbOpt, _.io.result(0).paddr),
      optUInt(dtlbOpt, _.io.result(0).paddr))
    else optUInt(utlbOpt, _.io.result(p).paddr)
  def laneCause(p: Int): UInt =
    if (split) Mux(lookupReq(p).access === ysyx_26030103_AccessType.Fetch,
      optUInt(itlbOpt, _.io.result(0).cause),
      optUInt(dtlbOpt, _.io.result(0).cause))
    else optUInt(utlbOpt, _.io.result(p).cause)

  val laneTlbValid = VecInit((0 until lookupPorts).map(laneResultValid))
  val laneTlbHit = VecInit((0 until lookupPorts).map(laneHit))
  val laneTlbFault = VecInit((0 until lookupPorts).map(laneFault))
  val lanePaddrVec = VecInit((0 until lookupPorts).map(lanePaddr))
  val laneCauseVec = VecInit((0 until lookupPorts).map(laneCause))
  val laneBypass = VecInit((0 until lookupPorts).map { p =>
    !profile.enabled.B || lookupReq(p).satp(31) === 0.U ||
      lookupReq(p).privilege === 3.U
  })

  val ptwDoneVec = VecInit((0 until refillPorts).map(p => ptw.io.resp(p).valid))
  val ptwCtxIdxVec = VecInit((0 until refillPorts).map(p =>
    ptw.io.resp(p).bits.id(ctxBits - 1, 0)))
  val ptwCtxValidVec = VecInit((0 until refillPorts).map(p =>
    ptwDoneVec(p) && ctxValid(ptwCtxIdxVec(p)) &&
      ctxState(ptwCtxIdxVec(p)) === ctxWalk))
  val ptwRespVec = VecInit((0 until refillPorts).map(p => ptw.io.resp(p).bits))
  val anyPtwDone = ptwDoneVec.reduce(_ || _)

  // PTW 响应优先写回响应表；同一拍发生 PTW 响应时，本拍所有 TLB 命中
  // 都延迟到下一拍，避免 TLB 和 PTW 争用响应表写端口。
  val laneHitDone = VecInit((0 until lookupPorts).map { p =>
    !anyPtwDone && lookupValid(p) && profile.tlbEnabled.B &&
      !laneBypass(p) && laneTlbValid(p) && laneTlbHit(p)
  })
  val laneBypassDone = VecInit((0 until lookupPorts).map { p =>
    !anyPtwDone && lookupValid(p) && laneBypass(p)
  })

  def writeResponse(valid: Bool, index: UInt, seq: UInt,
      response: ysyx_26030103_MMUResponse): Unit = {
    when(valid) {
      respValid(seq(tableBits - 1, 0)) := true.B
      respData(seq(tableBits - 1, 0)) := response
      ctxValid(index) := false.B
      ctxState(index) := ctxFree
    }
  }

  val ptwResponseVec = WireInit(VecInit(Seq.fill(refillPorts)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse))))
  for (p <- 0 until refillPorts) {
    ptwResponseVec(p).id := ctx(ptwCtxIdxVec(p)).req.id
    ptwResponseVec(p).paddr := ptwRespVec(p).paddr
    ptwResponseVec(p).fault := ptwRespVec(p).fault
    ptwResponseVec(p).cause := ptwRespVec(p).cause
    ptwResponseVec(p).tval := ptwRespVec(p).tval
  }

  val laneHitResponse = WireInit(VecInit(Seq.fill(lookupPorts)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse))))
  val laneBypassResponse = WireInit(VecInit(Seq.fill(lookupPorts)(
    0.U.asTypeOf(new ysyx_26030103_MMUResponse))))
  for (p <- 0 until lookupPorts) {
    laneHitResponse(p).id := lookupReq(p).id
    laneHitResponse(p).paddr := lanePaddrVec(p)
    laneHitResponse(p).fault := laneTlbFault(p)
    laneHitResponse(p).cause := laneCauseVec(p)
    laneHitResponse(p).tval := lookupReq(p).vaddr

    laneBypassResponse(p).id := lookupReq(p).id
    laneBypassResponse(p).paddr := lookupReq(p).vaddr
    laneBypassResponse(p).fault := false.B
    laneBypassResponse(p).cause := 0.U
    laneBypassResponse(p).tval := lookupReq(p).vaddr
  }

  // 两个查找端口可以在同一拍各写一个响应表项；表项按请求序号索引，
  // 因此同拍完成不会破坏向软件的响应顺序。
  for (p <- 0 until refillPorts) {
    when(ptwCtxValidVec(p)) {
      writeResponse(true.B, ptwCtxIdxVec(p), ctxSeq(ptwCtxIdxVec(p)), ptwResponseVec(p))
    }
  }
  for (p <- 0 until lookupPorts) {
    when(laneHitDone(p)) {
      writeResponse(true.B, lookupIndex(p), ctxSeq(lookupIndex(p)), laneHitResponse(p))
    }.elsewhen(laneBypassDone(p)) {
      writeResponse(true.B, lookupIndex(p), ctxSeq(lookupIndex(p)), laneBypassResponse(p))
    }
  }

  // TLB 命中直接返回；缺失则把上下文交给 PTWCluster。每拍最多只发一个
  // PTW 请求，另一个未命中的上下文保持在 ctxLookup，下一拍继续尝试。
  val laneTlbMiss = VecInit((0 until lookupPorts).map { p =>
    !profile.tlbEnabled.B || (laneTlbValid(p) && !laneTlbHit(p))
  })
  val lanePtwNeeded = VecInit((0 until lookupPorts).map { p =>
    lookupValid(p) && !laneBypass(p) && laneTlbMiss(p) && !anyPtwDone
  })
  ptw.io.req.valid := false.B
  ptw.io.req.bits := 0.U.asTypeOf(new ysyx_26030103_PTWRequest)
  for (p <- 0 until refillPorts) { ptw.io.resp(p).ready := ptwDoneVec(p) }
  ptw.io.flush := io.flush
  def requestPtw(p: Int): Unit = {
    ptw.io.req.valid := true.B
    ptw.io.req.bits.id := lookupIndex(p)
    ptw.io.req.bits.vaddr := lookupReq(p).vaddr
    ptw.io.req.bits.satp := lookupReq(p).satp
    ptw.io.req.bits.privilege := lookupReq(p).privilege
    ptw.io.req.bits.access := lookupReq(p).access
    ptw.io.req.bits.sum := lookupReq(p).sum
    ptw.io.req.bits.mxr := lookupReq(p).mxr
    when(ptw.io.req.fire) {
      ctxState(lookupIndex(p)) := ctxWalk
    }
  }
  when(lanePtwNeeded(0)) { requestPtw(0) }
  if (lookupPorts > 1) {
    when(!lanePtwNeeded(0) && lanePtwNeeded(1)) { requestPtw(1) }
  }

  // PTW 回填 TLB。多个上下文可以并行 walk，但页表总线仍由 PTWCluster 仲裁。
  val fillFetch = VecInit((0 until refillPorts).map(p =>
    ctx(ptwCtxIdxVec(p)).req.access === ysyx_26030103_AccessType.Fetch))
  if (split) {
    itlbOpt.foreach { tlb =>
      for (p <- 0 until refillPorts) {
        connectFill(tlb, p, ptwCtxValidVec(p) && ptwRespVec(p).fillValid && fillFetch(p))
      }
    }
    dtlbOpt.foreach { tlb =>
      for (p <- 0 until refillPorts) {
        connectFill(tlb, p, ptwCtxValidVec(p) && ptwRespVec(p).fillValid && !fillFetch(p))
      }
    }
  } else {
    utlbOpt.foreach { tlb =>
      for (p <- 0 until refillPorts) {
        connectFill(tlb, p, ptwCtxValidVec(p) && ptwRespVec(p).fillValid)
      }
    }
  }

  // 接收新请求：空上下文槽 + 请求队列非空。
  val freeCandidates = VecInit((0 until contexts).map(i => !ctxValid(i)))
  val anyFree = freeCandidates.reduce(_ || _)
  val freeIndex = PriorityEncoder(freeCandidates)
  reqQueue.io.deq.ready := anyFree && !io.flush
  when(reqQueue.io.deq.fire) {
    ctx(freeIndex).req := reqQueue.io.deq.bits
    ctxSeq(freeIndex) := nextSeq
    ctxValid(freeIndex) := true.B
    ctxState(freeIndex) := ctxLookup
    nextSeq := nextSeq + 1.U
  }

  // 响应表队首出队。
  when(io.resp.fire) {
    respValid(headIdx) := false.B
    headSeq := headSeq + 1.U
  }

  when(io.flush) {
    for (i <- 0 until contexts) {
      ctxValid(i) := false.B
      ctxState(i) := ctxFree
    }
    for (i <- 0 until tableSize) { respValid(i) := false.B }
    headSeq := 0.U
    nextSeq := 0.U
  }

  ptw.io.bus.req <> io.bus.req
  ptw.io.bus.resp <> io.bus.resp
}
