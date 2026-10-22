package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_PteConfig

/** 级间流水化的 page-table walker。
  *
  * 三级结构：
  *   A 级：为一个在途请求计算当前级 PTE 地址并查询 PTE cache；命中时直接
  *         送入 C 级，缺失时把页表读请求放入总线队列。
  *   B 级：单端口页表总线引擎，负责 4B/8B PTE 读和 A/D 硬件写回。等待
  *         总线响应时 A 级仍可服务其它在途请求。
  *   C 级：解析 PTE，完成权限/超级页检查，推进页表级数，或产生最终
  *         响应并交给响应排序逻辑。
  *
  * 同一请求的页表各级天然存在数据依赖，必须等上一级 PTE 返回后才能
  * 计算下一级地址；流水重叠发生在不同请求之间，以及 A 级与 B 级之间。
  * 因此本实现不是把多个独立 walker 换个名字，而是共享一份地址生成、
  * 总线引擎和 PTE 解析流水。
  */
class ysyx_26030103_PTWPipeline(
    outstanding: Int = 2,
    adMode: String = "fault",
    cacheEnable: Boolean = false,
    cacheEntries: Int = 16,
    cacheWays: Int = 2,
    superpage: Boolean = false,
    pteConfig: ysyx_26030103_PteConfig = ysyx_26030103_PteConfig(),
    pageBits: Int = 12,
    levels: Int = 2,
    tableEntries: Int = 1024,
    fillSuperpage: Boolean = false,
    pteSizeBytes: Int = 4,
    inOrder: Boolean = true,
    respPorts: Int = 1,
    hasU: Boolean = true
) extends ysyx_26030103_PTWBackend(respPorts) {
  require(Set("fault", "ignore", "hardware").contains(adMode), "未知 PTW A/D 模式")
  require(outstanding >= 1, "PTW outstanding 必须 >= 1")
  require(respPorts >= 1, "PTW 响应端口数必须 >= 1")
  require(pageBits >= 2 && pageBits < 32, "pageBits 非法")
  require(levels >= 1 && levels <= 4, "页表级数必须在 1..4")
  require(tableEntries > 0 && (tableEntries & (tableEntries - 1)) == 0,
    "每级页表项数必须是 2 的幂")
  require(pteSizeBytes == 4 || pteSizeBytes == 8, "PTE 大小只支持 4/8 字节")
  if (cacheEnable) {
    require(cacheEntries > 0 && (cacheEntries & (cacheEntries - 1)) == 0,
      "PTW cache entries 必须是 2 的幂")
    require(cacheWays > 0 && cacheEntries % cacheWays == 0,
      "PTW cache ways 必须整除 entries")
    require((cacheWays & (cacheWays - 1)) == 0, "PTW cache ways 必须是 2 的幂")
  }

  private val pteBytes = pteSizeBytes
  private val pteShift = log2Ceil(pteBytes)
  private val indexBits = log2Ceil(tableEntries)
  require(pageBits + levels * indexBits == 32,
    s"pageBits($pageBits) + levels($levels)*indexBits($indexBits) 必须等于 32")

  private val ctxBits = log2Ceil(outstanding.max(2))
  private val ageBits = 16
  private val epochBits = 8
  private val cacheEffective = cacheEnable && pteBytes == 4

  class Context extends Bundle {
    val id = UInt(4.W)
    val busy = Bool()
    val inFlight = Bool()
    val needWrite = Bool()
    val done = Bool()
    val vaddr = UInt(32.W)
    val satp = UInt(32.W)
    // HAS_U=n 时 U/SUM 检查不存在，Context 不保存这两个字段。
    val privilege = if (hasU) Some(UInt(2.W)) else None
    val access = UInt(2.W)
    val sum = if (hasU) Some(Bool()) else None
    val mxr = Bool()
    val level = UInt(2.W)
    val table = UInt(32.W)
    val pteAddr = UInt(32.W)
    val writeData = UInt(32.W)
    val age = UInt(ageBits.W)
    val resp = new ysyx_26030103_PTWResponse
  }

  /** A 级交给 B 级的页表总线任务。 */
  class BusJob extends Bundle {
    val ctx = UInt(ctxBits.W)
    val addr = UInt(32.W)
    val write = Bool()
    val data = UInt(32.W)
    val epoch = UInt(epochBits.W)
  }

  /** B 级交给 C 级的已完成页表项。 */
  class ResolveJob extends Bundle {
    val ctx = UInt(ctxBits.W)
    val pteAddr = UInt(32.W)
    val raw = UInt(32.W)
    val fault = Bool()
    val write = Bool()
    val epoch = UInt(epochBits.W)
  }

  val ctx = RegInit(VecInit(Seq.fill(outstanding)(0.U.asTypeOf(new Context))))
  val nextAge = RegInit(0.U(ageBits.W))
  val epoch = RegInit(0.U(epochBits.W))

  // PTE cache 与 independent 版本保持一致：仅缓存 4B PTE。
  class CacheEntry extends Bundle {
    val valid = Bool()
    val tag = UInt(32.W)
    val data = UInt(32.W)
  }
  private val cacheSets = if (cacheEffective) cacheEntries / cacheWays else 1
  private val cacheSetBits = if (cacheSets <= 1) 0 else log2Ceil(cacheSets)
  private val cacheWayBits = log2Ceil(cacheWays.max(2))
  private val cacheIndexBits = if (cacheEffective) log2Ceil(cacheEntries) else 1
  private def cacheIndex(set: UInt, way: UInt): UInt =
    if (!cacheEnable) 0.U
    else (set * cacheWays.U + way)(cacheIndexBits - 1, 0)
  val cache = if (cacheEffective)
    Some(RegInit(VecInit(Seq.fill(cacheEntries)(0.U.asTypeOf(new CacheEntry)))))
  else None
  val cacheRR = if (cacheEffective)
    Some(RegInit(VecInit(Seq.fill(cacheSets)(0.U(cacheWayBits.W)))))
  else None

  private def cacheSetOf(addr: UInt): UInt =
    if (cacheSetBits == 0) 0.U else addr(cacheSetBits + 1, 2)

  private def cacheLookup(addr: UInt): (Bool, UInt) = {
    if (!cacheEffective) (false.B, 0.U)
    else {
      val set = cacheSetOf(addr)
      val hits = (0 until cacheWays).map { w =>
        val e = cache.get(cacheIndex(set, w.U))
        e.valid && e.tag === addr
      }
      val data = Mux1H(hits, (0 until cacheWays).map { w =>
        cache.get(cacheIndex(set, w.U)).data
      })
      (hits.reduce(_ || _), data)
    }
  }

  private def cacheFill(addr: UInt, data: UInt): Unit = {
    if (cacheEffective) {
      val set = cacheSetOf(addr)
      val idx = cacheIndex(set, cacheRR.get(set))
      val e = Wire(new CacheEntry)
      e := 0.U.asTypeOf(new CacheEntry)
      e.valid := true.B
      e.tag := addr
      e.data := data
      cache.get(idx) := e
      cacheRR.get(set) := Mux(
        cacheRR.get(set) === (cacheWays - 1).U,
        0.U,
        cacheRR.get(set) + 1.U
      )
    }
  }

  private def normalizePte(d: UInt): UInt = {
    val flags = Cat(
      d(pteConfig.dBit), d(pteConfig.aBit), d(pteConfig.globalBit),
      d(pteConfig.userBit), d(pteConfig.executeBit), d(pteConfig.writeBit),
      d(pteConfig.readBit), d(pteConfig.validBit)
    )
    val ppn = d(pteConfig.ppnHigh, pteConfig.ppnLow)
    Cat(ppn.pad(22)(21, 0), 0.U(2.W), flags)
  }
  private def adRaw(d: UInt, isStore: Bool): UInt =
    d | (1.U << pteConfig.aBit) |
      Mux(isStore, 1.U << pteConfig.dBit, 0.U(32.W))

  private def isLeaf(d: UInt): Bool = d(3, 1) =/= 0.U
  private def leafAllowed(level: UInt): Bool =
    superpage.B || level === (levels - 1).U
  private def walkFault(d: UInt, level: UInt): Bool =
    !d(0) || (isLeaf(d) && !leafAllowed(level)) ||
      (!isLeaf(d) && level === (levels - 1).U)
  private def leafFaultOf(d: UInt, privilege: UInt, access: UInt,
      sum: Bool, mxr: Bool): Bool = {
    val isFetch = access === ysyx_26030103_AccessType.Fetch
    val isStore = access === ysyx_26030103_AccessType.Store
    val userFault = if (hasU) privilege === 0.U && !d(4) else false.B
    val sumFault =
      if (hasU) privilege === 1.U && d(4) && (!sum || isFetch) else false.B
    val fetchFault = isFetch && !d(3)
    val readFault = !isFetch && !isStore && !d(1) && !(mxr && d(3))
    val writeFault = isStore && !d(2)
    val adFault = adMode match {
      case "fault" => !d(6) || (isStore && !d(7))
      case _       => false.B
    }
    userFault || sumFault || fetchFault || readFault || writeFault || adFault
  }
  private def needsAdUpdate(d: UInt, isStore: Bool): Bool = adMode match {
    case "hardware" => d(0) && (!d(6) || (isStore && !d(7)))
    case _          => false.B
  }
  private def pageFaultCause(access: UInt): UInt = {
    val isFetch = access === ysyx_26030103_AccessType.Fetch
    val isStore = access === ysyx_26030103_AccessType.Store
    Mux(isFetch, 12.U, Mux(isStore, 15.U, 13.U))
  }
  private def vpnIndex(vaddr: UInt, level: UInt): UInt =
    MuxLookup(level, vaddr(31, pageBits))(
      (0 until levels).map { l =>
        val lo = pageBits + (levels - 1 - l) * indexBits
        l.U -> vaddr(lo + indexBits - 1, lo)
      }
    )
  private def tableAddress(base: UInt, index: UInt): UInt =
    base + (index << pteShift)
  private def superOffsetBits(level: UInt): UInt =
    pageBits.U + ((levels - 1).U - level) * indexBits.U
  private def paddrOf(d: UInt, level: UInt, vaddr: UInt): UInt = {
    val ppn = d(31, 10)
    val shift = superOffsetBits(level)
    val offsetMask = (1.U << shift) - 1.U
    (ppn << pageBits) | (vaddr & offsetMask)
  }
  /** 与 independent PTW 相同：0=基础页，k=第 k 级超级页。 */
  private def fillLevelOf(level: UInt): UInt = (levels - 1).U - level

  // B 级：页表总线任务队列，深度等于可同时在途的上下文数。
  val busQ = Module(new Queue(new BusJob, outstanding, pipe = true,
    flow = false, hasFlush = true))
  busQ.io.flush.get := io.flush

  val busIdle :: busReadWait :: busHighReq :: busHighWait :: busWriteWait :: Nil =
    Enum(5)
  val busState = RegInit(busIdle)
  val curJob = RegInit(0.U.asTypeOf(new BusJob))
  val lowData = RegInit(0.U(32.W))
  val s1Out = Wire(Valid(new ResolveJob))
  s1Out.valid := false.B
  s1Out.bits := 0.U.asTypeOf(new ResolveJob)

  io.bus.req.valid := false.B
  io.bus.req.bits := 0.U.asTypeOf(new ysyx_26030103_PTEBusRequest)
  busQ.io.deq.ready := false.B
  io.bus.resp.ready := busState =/= busIdle && busState =/= busHighReq

  // B 级从队列取任务；只有在请求真正握手后才弹出，避免丢请求。
  when(busState === busIdle) {
    when(busQ.io.deq.valid) {
      io.bus.req.valid := true.B
      io.bus.req.bits.addr := busQ.io.deq.bits.addr
      io.bus.req.bits.write := busQ.io.deq.bits.write
      io.bus.req.bits.data := busQ.io.deq.bits.data
      io.bus.req.bits.strb := Mux(busQ.io.deq.bits.write, "hf".U(4.W), 0.U(4.W))
      when(io.bus.req.ready) {
        busQ.io.deq.ready := true.B
        curJob := busQ.io.deq.bits
        busState := Mux(busQ.io.deq.bits.write, busWriteWait, busReadWait)
      }
    }
  }

  // 8B PTE 的低/高两个 32 位字由 B 级连续读取，A 级无需等待。
  when(busState === busReadWait) {
    when(io.bus.resp.valid) {
      if (pteBytes == 8) {
        when(io.bus.resp.bits.fault) {
          s1Out.valid := true.B
          s1Out.bits.ctx := curJob.ctx
          s1Out.bits.pteAddr := curJob.addr
          s1Out.bits.raw := io.bus.resp.bits.data
          s1Out.bits.fault := true.B
          s1Out.bits.write := false.B
          s1Out.bits.epoch := curJob.epoch
          busState := busIdle
        }.otherwise {
          lowData := io.bus.resp.bits.data
          busState := busHighReq
        }
      } else {
        s1Out.valid := true.B
        s1Out.bits.ctx := curJob.ctx
        s1Out.bits.pteAddr := curJob.addr
        s1Out.bits.raw := io.bus.resp.bits.data
        s1Out.bits.fault := io.bus.resp.bits.fault
        s1Out.bits.write := false.B
        s1Out.bits.epoch := curJob.epoch
        busState := busIdle
      }
    }
  }

  when(busState === busHighReq) {
    io.bus.req.valid := true.B
    io.bus.req.bits.addr := curJob.addr + 4.U
    io.bus.req.bits.write := false.B
    io.bus.req.bits.data := 0.U
    io.bus.req.bits.strb := 0.U
    when(io.bus.req.ready) { busState := busHighWait }
  }

  when(busState === busHighWait) {
    when(io.bus.resp.valid) {
      s1Out.valid := true.B
      s1Out.bits.ctx := curJob.ctx
      s1Out.bits.pteAddr := curJob.addr
      s1Out.bits.raw := Cat(io.bus.resp.bits.data, lowData)
      s1Out.bits.fault := io.bus.resp.bits.fault
      s1Out.bits.write := false.B
      s1Out.bits.epoch := curJob.epoch
      busState := busIdle
    }
  }

  when(busState === busWriteWait) {
    when(io.bus.resp.valid) {
      s1Out.valid := true.B
      s1Out.bits.ctx := curJob.ctx
      s1Out.bits.pteAddr := curJob.addr
      s1Out.bits.raw := curJob.data
      s1Out.bits.fault := io.bus.resp.bits.fault
      s1Out.bits.write := true.B
      s1Out.bits.epoch := curJob.epoch
      busState := busIdle
    }
  }

  // A 级：有 A/D 写回待办时优先发写；否则为最早的空闲 walk 计算地址并查 cache。
  val writeEligible = VecInit((0 until outstanding).map { i =>
    ctx(i).busy && !ctx(i).done && !ctx(i).inFlight && ctx(i).needWrite
  })
  val anyWrite = writeEligible.reduce(_ || _)
  val writeIdx = PriorityEncoder(writeEligible)

  val walkEligible = VecInit((0 until outstanding).map { i =>
    ctx(i).busy && !ctx(i).done && !ctx(i).inFlight && !ctx(i).needWrite
  })
  val anyWalk = walkEligible.reduce(_ || _)
  val walkIdx = PriorityEncoder(walkEligible)

  val walkVaddr = ctx(walkIdx).vaddr
  val walkLevel = ctx(walkIdx).level
  val nextPteAddr = tableAddress(ctx(walkIdx).table, vpnIndex(walkVaddr, walkLevel))
  val (cacheHit, cacheData) = cacheLookup(nextPteAddr)

  val newJob = Wire(new BusJob)
  newJob := 0.U.asTypeOf(new BusJob)
  newJob.ctx := Mux(anyWrite, writeIdx, walkIdx)
  newJob.addr := Mux(anyWrite, ctx(writeIdx).pteAddr, nextPteAddr)
  newJob.write := anyWrite
  newJob.data := Mux(anyWrite, ctx(writeIdx).writeData, 0.U)
  newJob.epoch := epoch

  // 无论 B 级是否正在等响应，A 级都可以继续为其它上下文算地址。
  val busEnqValid = anyWrite || (anyWalk && !cacheHit)
  busQ.io.enq.valid := busEnqValid
  busQ.io.enq.bits := newJob
  when(busQ.io.enq.fire) {
    ctx(newJob.ctx).inFlight := true.B
  }

  // C 级：独占一个解析入口。B 级结果优先，cache 命中在 B 级空闲时同拍解析。
  val busResultValid = s1Out.valid && s1Out.bits.epoch === epoch
  val hitValid = anyWalk && cacheHit && !busResultValid
  val resolveValid = busResultValid || hitValid
  val resolveCtx = Mux(busResultValid, s1Out.bits.ctx, walkIdx)
  val resolvePteAddr = Mux(busResultValid, s1Out.bits.pteAddr, nextPteAddr)
  val resolveRaw = Mux(busResultValid, s1Out.bits.raw, cacheData)
  val resolveBusFault = Mux(busResultValid, s1Out.bits.fault, false.B)
  val resolveWrite = Mux(busResultValid, s1Out.bits.write, false.B)
  val resolveFromBus = busResultValid

  when(resolveValid) {
    val d = normalizePte(resolveRaw)
    val level = ctx(resolveCtx).level
    val vaddr = ctx(resolveCtx).vaddr
    val privilege = ctx(resolveCtx).privilege.getOrElse(0.U(2.W))
    val access = ctx(resolveCtx).access
    val sum = ctx(resolveCtx).sum.getOrElse(false.B)
    val mxr = ctx(resolveCtx).mxr
    val isStore = access === ysyx_26030103_AccessType.Store
    val cause = pageFaultCause(access)
    val leaf = isLeaf(d)
    val leafFault = leafFaultOf(d, privilege, access, sum, mxr)

    ctx(resolveCtx).inFlight := false.B
    ctx(resolveCtx).pteAddr := resolvePteAddr
    // 与 independent walker 一致：总线读到的 PTE 先填入 cache，即使它是
    // 非叶子或权限错误；cache 命中路径不需要重复写。
    when(resolveFromBus && !resolveWrite) { cacheFill(resolvePteAddr, d) }

    when(resolveWrite) {
      // A/D 写回完成，写回数据就是最终 PTE。
      cacheFill(resolvePteAddr, d)
      ctx(resolveCtx).needWrite := false.B
      ctx(resolveCtx).done := true.B
      ctx(resolveCtx).resp.paddr := paddrOf(d, level, vaddr)
      ctx(resolveCtx).resp.fault := resolveBusFault ||
        leafFaultOf(d, privilege, access, sum, mxr)
      ctx(resolveCtx).resp.cause := cause
      ctx(resolveCtx).resp.tval := vaddr
      ctx(resolveCtx).resp.fillPte := d
      ctx(resolveCtx).resp.fillVaddr := vaddr
      ctx(resolveCtx).resp.fillAsid := ctx(resolveCtx).satp(30, 22)
      ctx(resolveCtx).resp.fillLevel := fillLevelOf(level)
      ctx(resolveCtx).resp.fillValid := !resolveBusFault &&
        !leafFaultOf(d, privilege, access, sum, mxr) &&
        (fillSuperpage.B || level === (levels - 1).U)
    }.elsewhen(resolveBusFault || walkFault(d, level)) {
      ctx(resolveCtx).needWrite := false.B
      ctx(resolveCtx).done := true.B
      ctx(resolveCtx).resp := 0.U.asTypeOf(new ysyx_26030103_PTWResponse)
      ctx(resolveCtx).resp.id := ctx(resolveCtx).id
      ctx(resolveCtx).resp.fault := true.B
      ctx(resolveCtx).resp.cause := cause
      ctx(resolveCtx).resp.tval := vaddr
    }.elsewhen(leaf) {
      when(needsAdUpdate(d, isStore)) {
        ctx(resolveCtx).needWrite := true.B
        ctx(resolveCtx).writeData := adRaw(d, isStore)
      }.otherwise {
        ctx(resolveCtx).done := true.B
        ctx(resolveCtx).resp.paddr := paddrOf(d, level, vaddr)
        ctx(resolveCtx).resp.fault := leafFault
        ctx(resolveCtx).resp.cause := cause
        ctx(resolveCtx).resp.tval := vaddr
        ctx(resolveCtx).resp.fillPte := d
        ctx(resolveCtx).resp.fillVaddr := vaddr
        ctx(resolveCtx).resp.fillAsid := ctx(resolveCtx).satp(30, 22)
        ctx(resolveCtx).resp.fillLevel := fillLevelOf(level)
        ctx(resolveCtx).resp.fillValid := !leafFault &&
          (fillSuperpage.B || level === (levels - 1).U)
      }
    }.otherwise {
      ctx(resolveCtx).table := Cat(d(31, 10), 0.U(pageBits.W))
      ctx(resolveCtx).level := level + 1.U
    }
  }

  // 上下文分配：每个空闲槽对应一笔在途请求。
  val freeCandidates = VecInit((0 until outstanding).map(i => !ctx(i).busy))
  val anyFree = freeCandidates.reduce(_ || _)
  val freeIndex = PriorityEncoder(freeCandidates)
  io.req.ready := anyFree && !io.flush
  when(io.req.fire) {
    ctx(freeIndex).id := io.req.bits.id
    ctx(freeIndex).busy := true.B
    ctx(freeIndex).inFlight := false.B
    ctx(freeIndex).needWrite := false.B
    ctx(freeIndex).done := false.B
    ctx(freeIndex).vaddr := io.req.bits.vaddr
    ctx(freeIndex).satp := io.req.bits.satp
    ctx(freeIndex).privilege.foreach { p => p := io.req.bits.privilege }
    ctx(freeIndex).access := io.req.bits.access
    ctx(freeIndex).sum.foreach { s => s := io.req.bits.sum }
    ctx(freeIndex).mxr := io.req.bits.mxr
    ctx(freeIndex).level := 0.U
    ctx(freeIndex).table := Cat(io.req.bits.satp(21, 0), 0.U(pageBits.W))
    ctx(freeIndex).pteAddr := 0.U
    ctx(freeIndex).writeData := 0.U
    ctx(freeIndex).age := nextAge
    ctx(freeIndex).resp := 0.U.asTypeOf(new ysyx_26030103_PTWResponse)
    ctx(freeIndex).resp.id := io.req.bits.id
    nextAge := nextAge + 1.U
  }

  // 响应排序：in_order 只允许最老的未返回请求先走；out_of_order 可选任意
  // 已完成请求。多个响应端口按年龄/优先级依次消去，单拍可回填多个 TLB 表项。
  def olderThan(a: UInt, b: UInt): Bool = (a - b)(ageBits - 1)
  var taken: Seq[Bool] = Seq.fill(outstanding)(false.B)
  for (port <- 0 until respPorts) {
    val eligible = (0 until outstanding).map { i =>
      val olderPending = (0 until outstanding).map { j =>
        if (j == i) false.B
        else ctx(j).busy && !taken(j) && olderThan(ctx(j).age, ctx(i).age)
      }.reduceOption(_ || _).getOrElse(false.B)
      val orderOk = if (inOrder) !olderPending else true.B
      ctx(i).busy && ctx(i).done && !taken(i) && orderOk
    }
    val eligibleVec = VecInit(eligible)
    val valid = eligibleVec.reduce(_ || _)
    val index = PriorityEncoder(eligibleVec)
    io.resp(port).valid := valid
    io.resp(port).bits := ctx(index).resp
    when(valid && io.resp(port).ready) {
      ctx(index).busy := false.B
    }
    taken = taken.zipWithIndex.map { case (already, i) =>
      already || (valid && index === i.U)
    }
  }

  // flush 使旧总线事务的 epoch 失效；B 级会继续收完已在总线上的响应并丢弃，
  // 避免复位上下文后旧响应污染复用同一槽位的新请求。
  when(io.flush) {
    cache.foreach { c => for (i <- 0 until cacheEntries) { c(i).valid := false.B } }
    for (i <- 0 until outstanding) {
      ctx(i) := 0.U.asTypeOf(new Context)
    }
    nextAge := 0.U
    epoch := epoch + 1.U
  }
}
