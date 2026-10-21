package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile

/** Sv32 MMU：请求队列 + 多上下文 PTW 调度。
  *
  * 每个在途请求拥有独立上下文；TLB 命中可直接完成，TLB 缺失则把上下文
  * 交给 PTWCluster。PTWCluster 的多个 walker 可以同时工作，响应按请求
  * 序号而不是完成顺序返回，保持架构顺序。
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

  val split = profile.tlbOrganization == "split"

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
    refillPorts = refillPorts
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
    respPorts = refillPorts
  ))

  val lookupValid = WireDefault(false.B)
  val lookupIndex = Wire(UInt(ctxBits.W))
  lookupIndex := 0.U
  val lookupCandidates = VecInit((0 until contexts).map { i =>
    ctxValid(i) && ctxState(i) === ctxLookup
  })
  lookupValid := lookupCandidates.reduce(_ || _)
  lookupIndex := PriorityEncoder(lookupCandidates)
  val lookupReq = WireInit(0.U.asTypeOf(new ysyx_26030103_MMURequest))
  when(lookupValid) { lookupReq := ctx(lookupIndex).req }

  def connectTlb(tlb: ysyx_26030103_TLB): Unit = {
    tlb.io.lookup.vaddr := lookupReq.vaddr
    tlb.io.lookup.asid := lookupReq.satp(30, 22)
    tlb.io.lookup.privilege := lookupReq.privilege
    tlb.io.lookup.access := lookupReq.access
    tlb.io.lookup.sum := lookupReq.sum
    tlb.io.lookup.mxr := lookupReq.mxr
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

  val selFetch = lookupReq.access === ysyx_26030103_AccessType.Fetch
  val tlbResultValid =
    if (!profile.tlbEnabled) true.B
    else if (split) Mux(selFetch, optBool(itlbOpt, _.io.result.resultValid),
      optBool(dtlbOpt, _.io.result.resultValid))
    else optBool(utlbOpt, _.io.result.resultValid)
  val tlbHit =
    if (split) Mux(selFetch, optBool(itlbOpt, _.io.result.hit),
      optBool(dtlbOpt, _.io.result.hit))
    else optBool(utlbOpt, _.io.result.hit)
  val tlbFault =
    if (split) Mux(selFetch, optBool(itlbOpt, _.io.result.fault),
      optBool(dtlbOpt, _.io.result.fault))
    else optBool(utlbOpt, _.io.result.fault)
  val tlbPaddr =
    if (split) Mux(selFetch, optUInt(itlbOpt, _.io.result.paddr),
      optUInt(dtlbOpt, _.io.result.paddr))
    else optUInt(utlbOpt, _.io.result.paddr)
  val tlbCause =
    if (split) Mux(selFetch, optUInt(itlbOpt, _.io.result.cause),
      optUInt(dtlbOpt, _.io.result.cause))
    else optUInt(utlbOpt, _.io.result.cause)

  // 响应顺序：每个请求分配一个序号，完成结果写入按序号索引的表，
  // 只有队首完成时才向软件返回，后面的尽早完成也不会越过前面。
  val headIdx = headSeq(tableBits - 1, 0)
  io.resp.valid := respValid(headIdx)
  io.resp.bits := respData(headIdx)

  val bypass = !profile.enabled.B || lookupReq.satp(31) === 0.U ||
    lookupReq.privilege === 3.U

  val ptwDoneVec = VecInit((0 until refillPorts).map(p => ptw.io.resp(p).valid))
  val ptwCtxIdxVec = VecInit((0 until refillPorts).map(p =>
    ptw.io.resp(p).bits.id(ctxBits - 1, 0)))
  val ptwCtxValidVec = VecInit((0 until refillPorts).map(p =>
    ptwDoneVec(p) && ctxValid(ptwCtxIdxVec(p)) &&
      ctxState(ptwCtxIdxVec(p)) === ctxWalk))
  val ptwRespVec = VecInit((0 until refillPorts).map(p => ptw.io.resp(p).bits))
  val anyPtwDone = ptwDoneVec.reduce(_ || _)

  // PTW 响应优先完成；TLB 命中完成被推迟一拍，保证每拍写出稳定的响应表项。
  val tlbDone = !anyPtwDone && lookupValid && tlbResultValid && !bypass &&
    profile.tlbEnabled.B && tlbHit
  val bypassDone = !anyPtwDone && lookupValid && bypass

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

  val hitResponse = WireInit(0.U.asTypeOf(new ysyx_26030103_MMUResponse))
  hitResponse.id := lookupReq.id
  hitResponse.paddr := tlbPaddr
  hitResponse.fault := tlbFault
  hitResponse.cause := tlbCause
  hitResponse.tval := lookupReq.vaddr

  val bypassResponse = WireInit(0.U.asTypeOf(new ysyx_26030103_MMUResponse))
  bypassResponse.id := lookupReq.id
  bypassResponse.paddr := lookupReq.vaddr
  bypassResponse.fault := false.B
  bypassResponse.cause := 0.U
  bypassResponse.tval := lookupReq.vaddr

  for (p <- 0 until refillPorts) {
    when(ptwCtxValidVec(p)) {
      writeResponse(true.B, ptwCtxIdxVec(p), ctxSeq(ptwCtxIdxVec(p)), ptwResponseVec(p))
    }
  }
  when(!anyPtwDone && tlbDone) {
    writeResponse(true.B, lookupIndex, ctxSeq(lookupIndex), hitResponse)
  }.elsewhen(!anyPtwDone && bypassDone) {
    writeResponse(true.B, lookupIndex, ctxSeq(lookupIndex), bypassResponse)
  }

  // TLB 命中直接返回；缺失则把上下文交给 PTWCluster。
  ptw.io.req.valid := false.B
  ptw.io.req.bits := 0.U.asTypeOf(new ysyx_26030103_PTWRequest)
  for (p <- 0 until refillPorts) { ptw.io.resp(p).ready := ptwDoneVec(p) }
  ptw.io.flush := io.flush
  when(lookupValid && ctxState(lookupIndex) === ctxLookup && !bypass &&
       !tlbDone && !anyPtwDone) {
    when(tlbResultValid && tlbHit) {
      // 命中完成在上面的写响应逻辑中处理。
    }.otherwise {
      ptw.io.req.valid := true.B
      ptw.io.req.bits.id := lookupIndex
      ptw.io.req.bits.vaddr := lookupReq.vaddr
      ptw.io.req.bits.satp := lookupReq.satp
      ptw.io.req.bits.privilege := lookupReq.privilege
      ptw.io.req.bits.access := lookupReq.access
      ptw.io.req.bits.sum := lookupReq.sum
      ptw.io.req.bits.mxr := lookupReq.mxr
      when(ptw.io.req.fire) {
        ctxState(lookupIndex) := ctxWalk
      }
    }
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
