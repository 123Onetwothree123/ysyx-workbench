package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile

/** Sv32 MMU：支持的 TLB 组织为 split（ITLB/DTLB）或 unified。
  *
  * 当前簇只有一个请求端口；顶层用仲裁器把 IFU/LSU 请求串行化。这样
  * split 模式下 I/D TLB 仍然独立，PTW 只有一个物理页表总线。后续若需要
  * I/D 并行 walk，可在此基础上复制 PTW 和控制状态。
  */
class ysyx_26030103_MMU(
    profile: ysyx_26030103_MMUProfile = ysyx_26030103_MMUProfile()
) extends Module {
  require(
    profile.pageSizeBytes == 4096 && profile.levels == 2 &&
      profile.pteSizeBytes == 4 && profile.pageTableSizeBytes == 4096 &&
      profile.pteFormat == "riscv32",
    "当前 MMU 只实现标准 Sv32 4KiB/2 级/4B PTE/4KiB 页表"
  )
  require(!profile.tlbSuperpage || profile.superpage, "TLB_SUPERPAGE 需要 MMU_SUPERPAGE")
  require(profile.ptwArch == "independent", "流水化 PTW 尚未实现")
  require(profile.refillPorts == 1, "当前仅支持单 TLB refill 端口")
  require(profile.tlbMissQueueMode == "follow_tlb", "当前仅支持 follow_tlb 缺失队列")

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new ysyx_26030103_MMURequest))
    val resp = Decoupled(new ysyx_26030103_MMUResponse)
    val bus = new ysyx_26030103_PTEBusIO
    val flush = Input(Bool())
  })

  val idle :: lookup :: walk :: respond :: Nil = Enum(4)
  val state = RegInit(idle)
  val reqReg = RegInit(0.U.asTypeOf(new ysyx_26030103_MMURequest))
  val respReg = RegInit(0.U.asTypeOf(new ysyx_26030103_MMUResponse))

  val enabled = if (profile.enabled) true.B else false.B
  // 请求队列：顶层可以连续送入多个已翻译请求；当前响应仍按序返回。
  val reqQueue = Module(new Queue(
    new ysyx_26030103_MMURequest,
    profile.ptwOutstanding.max(1),
    pipe = false,
    flow = false
  ))
  reqQueue.io.enq <> io.req
  io.req.ready := reqQueue.io.enq.ready
  reqQueue.io.deq.ready := false.B
  val split = profile.tlbOrganization == "split"
  val selFetch = reqReg.access === ysyx_26030103_AccessType.Fetch

  def makeTlb(entries: Int, ways: Int) = Module(new ysyx_26030103_TLB(
    entries = entries,
    ways = ways,
    asidBits = profile.tlbAsidBits,
    globalEnable = profile.tlbGlobal,
    replacement = profile.tlbReplacement,
    adMode = profile.adMode
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

  val ptwOpt =
    if (profile.enabled) Some(Module(new ysyx_26030103_PTWCluster(
      outstanding = profile.ptwOutstanding,
      adMode = profile.adMode,
      cacheEnable = profile.ptwCache,
      cacheEntries = profile.ptwCacheEntries,
      cacheWays = profile.ptwCacheWays,
      superpage = profile.superpage,
      inOrder = profile.ptwOrder == "in_order"
    )))
    else None

  io.resp.valid := state === respond
  io.resp.bits := respReg

  def connectTlb(tlb: ysyx_26030103_TLB): Unit = {
    tlb.io.lookup.vaddr := reqReg.vaddr
    tlb.io.lookup.asid := reqReg.satp(30, 22)
    tlb.io.lookup.privilege := reqReg.privilege
    tlb.io.lookup.access := reqReg.access
    tlb.io.lookup.sum := reqReg.sum
    tlb.io.lookup.mxr := reqReg.mxr
    tlb.io.flush := io.flush
    tlb.io.fillValid := false.B
    tlb.io.fillVaddr := 0.U
    tlb.io.fillAsid := 0.U
    tlb.io.fillPte := 0.U
  }
  itlbOpt.foreach(connectTlb)
  dtlbOpt.foreach(connectTlb)
  utlbOpt.foreach(connectTlb)

  def optBool(opt: Option[ysyx_26030103_TLB], f: ysyx_26030103_TLB => Bool): Bool =
    opt.map(f).getOrElse(false.B)
  def optUInt(opt: Option[ysyx_26030103_TLB], f: ysyx_26030103_TLB => UInt): UInt =
    opt.map(f).getOrElse(0.U)

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

  ptwOpt match {
    case Some(ptw) =>
      ptw.io.req.valid := false.B
      ptw.io.req.bits := 0.U.asTypeOf(new ysyx_26030103_PTWRequest)
      ptw.io.resp.ready := state === walk
      ptw.io.flush := io.flush

      io.bus.req.valid := ptw.io.bus.req.valid
      io.bus.req.bits := ptw.io.bus.req.bits
      ptw.io.bus.req.ready := io.bus.req.ready
      ptw.io.bus.resp.valid := io.bus.resp.valid
      ptw.io.bus.resp.bits := io.bus.resp.bits
      io.bus.resp.ready := ptw.io.bus.resp.ready

      val fillValid = ptw.io.resp.valid && ptw.io.resp.bits.fillValid
      def connectFill(tlb: ysyx_26030103_TLB, valid: Bool): Unit = {
        tlb.io.fillValid := valid
        tlb.io.fillVaddr := ptw.io.resp.bits.fillVaddr
        tlb.io.fillAsid := ptw.io.resp.bits.fillAsid
        tlb.io.fillPte := ptw.io.resp.bits.fillPte
      }
      if (split) {
        itlbOpt.foreach(connectFill(_, fillValid && selFetch))
        dtlbOpt.foreach(connectFill(_, fillValid && !selFetch))
      } else {
        utlbOpt.foreach(connectFill(_, fillValid))
      }
    case None =>
      io.bus.req.valid := false.B
      io.bus.req.bits := 0.U.asTypeOf(new ysyx_26030103_PTEBusRequest)
      io.bus.resp.ready := false.B
  }

  when(state === idle && reqQueue.io.deq.valid) {
    reqQueue.io.deq.ready := true.B
    reqReg := reqQueue.io.deq.bits
    when(!enabled || reqQueue.io.deq.bits.satp(31) === 0.U ||
         reqQueue.io.deq.bits.privilege === 3.U) {
      respReg.id := reqQueue.io.deq.bits.id
      respReg.paddr := reqQueue.io.deq.bits.vaddr
      respReg.fault := false.B
      respReg.cause := 0.U
      respReg.tval := reqQueue.io.deq.bits.vaddr
      state := respond
    }.otherwise {
      state := lookup
    }
  }

  switch(state) {
    is(lookup) {
      when(tlbResultValid && tlbHit) {
        respReg.id := reqReg.id
        respReg.paddr := tlbPaddr
        respReg.fault := tlbFault
        respReg.cause := tlbCause
        respReg.tval := reqReg.vaddr
        state := respond
      }.otherwise {
        ptwOpt.foreach { ptw =>
          ptw.io.req.valid := true.B
          ptw.io.req.bits.vaddr := reqReg.vaddr
          ptw.io.req.bits.satp := reqReg.satp
          ptw.io.req.bits.privilege := reqReg.privilege
          ptw.io.req.bits.access := reqReg.access
          ptw.io.req.bits.sum := reqReg.sum
          ptw.io.req.bits.mxr := reqReg.mxr
          when(ptw.io.req.fire) { state := walk }
        }
      }
    }
    is(walk) {
      ptwOpt.foreach { ptw =>
        when(ptw.io.resp.valid) {
          respReg.id := reqReg.id
          respReg.paddr := ptw.io.resp.bits.paddr
          respReg.fault := ptw.io.resp.bits.fault
          respReg.cause := ptw.io.resp.bits.cause
          respReg.tval := ptw.io.resp.bits.tval
          state := respond
        }
      }
    }
    is(respond) {
      when(io.resp.ready) { state := idle }
    }
  }
}
