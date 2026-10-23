package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_PteConfig
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile

/** 级间流水化的 page-table walker。
  *
  * 三级结构：
  *   A 级：为一个在途请求计算当前级 PTE 地址并查询 PTE cache；命中时直接
  *         送入 C 级，缺失时把页表读请求放入总线队列。
  *   B 级：多槽页表总线引擎，可同时持有多笔在途页表读/写。每个槽位用
  *         事务 ID 匹配响应，因此总线可以重叠、甚至乱序返回；8B PTE 的
  *         低/高字仍由同一槽位顺序读取。
  *   C 级：解析 PTE，完成权限/超级页检查，推进页表级数，或产生最终
  *         响应并交给响应排序逻辑。
  *
  * 同一请求的页表各级天然存在数据依赖，必须等上一级 PTE 返回后才能
  * 计算下一级地址；流水重叠发生在不同请求之间、A 级与 B 级之间，以及
  * B 级多个页表总线槽位之间。
  */
class ysyx_26030103_PTWPipeline(
    outstanding: Int = 2,
    busOutstanding: Int = 0,
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
    hasU: Boolean = true,
    XLEN: Int = 32,
    addressWidth: Int = 32,
    vaBits: Int = 32
) extends ysyx_26030103_PTWBackend(respPorts, XLEN, addressWidth, pteSizeBytes * 8) {
  require(Set("fault", "ignore", "hardware").contains(adMode), "未知 PTW A/D 模式")
  require(XLEN == 32 || XLEN == 64, s"PTW pipeline XLEN 非法: $XLEN")
  require(addressWidth == 32 || addressWidth == 64,
    s"PTW pipeline addressWidth 非法: $addressWidth")
  require(vaBits >= 2 && vaBits <= XLEN, s"PTW pipeline vaBits 非法: $vaBits")
  require(outstanding >= 1 && outstanding <= 16,
    "PTW outstanding 必须在 1..16（页表总线事务 ID 为 4 位）")
  require(busOutstanding >= 0 && busOutstanding <= 16,
    "PTW 页表总线 outstanding 必须在 0..16（0 表示跟随 outstanding）")
  require(respPorts >= 1, "PTW 响应端口数必须 >= 1")
  require(pageBits >= 2 && pageBits < vaBits, "pageBits 非法")
  require(levels >= 1 && levels <= ysyx_26030103_MMUProfile.MaxLevels,
    s"页表级数必须在 1..${ysyx_26030103_MMUProfile.MaxLevels}" +
      s"（fillLevel 为 ${ysyx_26030103_MMUProfile.LevelBits} 位）")
  require(tableEntries >= 2 && (tableEntries & (tableEntries - 1)) == 0,
    "每级页表项数必须是不小于 2 的 2 的幂")
  require(pteSizeBytes == 4 || pteSizeBytes == 8, "PTE 大小只支持 4/8 字节")
  require(XLEN == 32 || pteConfig.pteBits == pteSizeBytes * 8,
    "RV64 PTW 的 PTE 字段配置位宽必须与 PTE 大小一致")
  // 8B PTE 的 A/D 硬件写回走现有 32 位页表写端口，只更新低字。A/D 位
  // 落在高字时无法正确写回，必须在 elaboration 期拒绝。
  require(adMode != "hardware" || pteSizeBytes <= 4 ||
    (pteConfig.aBit < 32 && pteConfig.dBit < 32),
    "64 位 PTE 的 A/D 硬件写回要求 A/D 位位于低 32 位字内")
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
  private val ptePpnBits = pteConfig.ppnHigh - pteConfig.ppnLow + 1
  // 与 independent PTW 相同：pageBits+PPN 超过物理地址宽度时，只使用
  // 低位 PPN，高位在 walk 时按保留位检查并要求为 0。
  private val paddrPpnBits = math.min(ptePpnBits, addressWidth - pageBits)
  require(paddrPpnBits >= 1, "PPN 字段无法装入物理地址宽度")
  private val satpPpnBits = math.min(44, addressWidth - pageBits)
  require(satpPpnBits >= 1, "satp.PPN 无法装入物理地址宽度")
  require(pageBits + levels * indexBits == vaBits,
    s"pageBits($pageBits) + levels($levels)*indexBits($indexBits) 必须等于 $vaBits")
  require(!(superpage || fillSuperpage) ||
    ptePpnBits >= (levels - 1) * indexBits,
    s"超级页对齐位(${(levels - 1) * indexBits})超过 PTE PPN 位宽($ptePpnBits)")

  private val ctxBits = log2Ceil(outstanding.max(2))
  // 页表总线槽位数：默认与上下文数相同，每个上下文最多一笔在途事务；
  // 可通过 busOutstanding 单独收缩，便于面积/时序权衡。
  private val busSlots =
    if (busOutstanding <= 0) outstanding else math.min(busOutstanding, outstanding)
  private val ageBits = 16
  private val epochBits = 8
  private val pteWidth = pteBytes * 8
  // 4B/8B PTE 都支持 PTE cache；8B 表项保存低/高字，命中后无需再发
  // 两次页表读。
  private val cacheEffective = cacheEnable

  class Context extends Bundle {
    val id = UInt(4.W)
    val busy = Bool()
    val inFlight = Bool()
    val needWrite = Bool()
    val done = Bool()
    val vaddr = UInt(XLEN.W)
    val satp = UInt(XLEN.W)
    // HAS_U=n 时 U/SUM 检查不存在，Context 不保存这两个字段。
    val privilege = if (hasU) Some(UInt(2.W)) else None
    val access = UInt(2.W)
    val sum = if (hasU) Some(Bool()) else None
    val mxr = Bool()
    val level = UInt(ysyx_26030103_MMUProfile.LevelBits.W)
    val table = UInt(addressWidth.W)
    val pteAddr = UInt(addressWidth.W)
    val writeData = UInt(pteWidth.W)
    val age = UInt(ageBits.W)
    val resp = new ysyx_26030103_PTWResponse(XLEN, addressWidth, pteWidth)
  }

  /** A 级交给 B 级的页表总线任务。 */
  class BusJob extends Bundle {
    val ctx = UInt(ctxBits.W)
    val addr = UInt(addressWidth.W)
    val write = Bool()
    // A/D 写回时保存完整 PTE，总线只发送低 32 位。
    val data = UInt(pteWidth.W)
    val epoch = UInt(epochBits.W)
  }

  /** B 级交给 C 级的已完成页表项。 */
  class ResolveJob extends Bundle {
    val ctx = UInt(ctxBits.W)
    val pteAddr = UInt(addressWidth.W)
    // 8B PTE 时保存低/高字拼接后的完整 64 位原始值。
    val raw = UInt(pteWidth.W)
    val fault = Bool()
    val write = Bool()
    val epoch = UInt(epochBits.W)
  }

  val ctx = RegInit(VecInit(Seq.fill(outstanding)(0.U.asTypeOf(new Context))))
  val nextAge = RegInit(0.U(ageBits.W))
  val epoch = RegInit(0.U(epochBits.W))

  // PTE cache 与 independent 版本一致：保存原始 PTE，4B/8B 都启用。
  class CacheEntry extends Bundle {
    val valid = Bool()
    val tag = UInt(addressWidth.W)
    val data = UInt(pteWidth.W)
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
    if (cacheSetBits == 0) 0.U else addr(cacheSetBits + pteShift - 1, pteShift)

  private def cacheLookup(addr: UInt): (Bool, UInt) = {
    if (!cacheEffective) (false.B, 0.U(pteWidth.W))
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
    Cat(ppn.pad(pteWidth - pteConfig.ppnLow),
      if (pteConfig.ppnLow > 8) 0.U((pteConfig.ppnLow - 8).W) else 0.U,
      flags)
  }
  private def adRaw(d: UInt, isStore: Bool): UInt =
    d | (1.U << pteConfig.aBit) |
      Mux(isStore, 1.U << pteConfig.dBit, 0.U(pteWidth.W))

  // 这些函数接收 normalizePte 之后的内部 PTE：标志位固定在 bit0..7，
  // PPN 位置仍由 pteConfig 决定。CUSTOM 私有标志位置不能用于解释
  // 归一化结果。
  private def isLeaf(d: UInt): Bool =
    Cat(d(2), d(1), d(3)) =/= 0.U // 归一化 W/R/X = bit2/1/3
  private def leafAllowed(level: UInt): Bool =
    superpage.B || level === (levels - 1).U
  private def reservedFault(d: UInt): Bool =
    if (pteConfig.ppnHigh + 1 >= pteWidth) false.B
    else d(pteWidth - 1, pteConfig.ppnHigh + 1) =/= 0.U
  // PPN 字段以上的保留位随 pteConfig 移动；标准 Sv39/Sv48 即 PTE[63:54]，
  // CUSTOM 重新定义 PPN 高位时不能继续硬编码 54。
  private def rawReservedFault(raw: UInt): Bool =
    if (XLEN <= 32 || pteWidth <= 32 || pteConfig.ppnHigh + 1 >= pteWidth) false.B
    else raw(pteWidth - 1, pteConfig.ppnHigh + 1) =/= 0.U
  private def superpageMisaligned(d: UInt, level: UInt): Bool = {
    if (levels <= 1) false.B
    else {
      val allLowerBits = (levels - 1) * indexBits
      val shift = level * indexBits.U
      val mask = ((BigInt(1) << allLowerBits) - 1).U(ptePpnBits.W) >> shift
      (d(pteConfig.ppnHigh, pteConfig.ppnLow) & mask) =/= 0.U
    }
  }
  private def ppnFits(d: UInt): Bool = {
    if (paddrPpnBits >= ptePpnBits) true.B
    else d(pteConfig.ppnLow + ptePpnBits - 1,
      pteConfig.ppnLow + paddrPpnBits) === 0.U
  }
  private def ppnValue(d: UInt): UInt =
    d(pteConfig.ppnLow + paddrPpnBits - 1, pteConfig.ppnLow)
  private def walkFault(d: UInt, level: UInt): Bool =
    !d(0) || !ppnFits(d) || reservedFault(d) || // 归一化 valid = bit0
      (isLeaf(d) && (!leafAllowed(level) || superpageMisaligned(d, level))) ||
      (!isLeaf(d) && level === (levels - 1).U)
  private def leafFaultOf(d: UInt, privilege: UInt, access: UInt,
      sum: Bool, mxr: Bool): Bool = {
    val isFetch = access === ysyx_26030103_AccessType.Fetch
    val isStore = access === ysyx_26030103_AccessType.Store
    val userFault =
      if (hasU) privilege === 0.U && !d(4) else false.B
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
    MuxLookup(level, vaddr(vaBits - 1, pageBits))(
      (0 until levels).map { l =>
        val lo = pageBits + (levels - 1 - l) * indexBits
        l.U -> vaddr(lo + indexBits - 1, lo)
      }
    )
  private def tableAddress(base: UInt, index: UInt): UInt =
    base + (index << pteShift).pad(addressWidth)
  private def superOffsetBits(level: UInt): UInt =
    pageBits.U + ((levels - 1).U - level) * indexBits.U
  private def paddrOf(d: UInt, level: UInt, vaddr: UInt): UInt = {
    val ppn = ppnValue(d)
    val shift = superOffsetBits(level)
    val offsetMask = ((BigInt(1) << vaBits) - 1).U(vaBits.W) >> (vaBits.U - shift)
    ((ppn << pageBits).pad(addressWidth) |
      (vaddr & offsetMask).pad(addressWidth))
  }
  private def canonicalOk(vaddr: UInt): Bool = {
    if (vaBits == XLEN) true.B
    else {
      val upper = vaddr(XLEN - 1, vaBits)
      val sign = Fill(XLEN - vaBits, vaddr(vaBits - 1))
      upper === sign
    }
  }
  private def satpPpn(satp: UInt): UInt =
    if (XLEN == 32) satp(21, 0) else satp(43, 0)
  private def satpAsid(satp: UInt): UInt =
    if (XLEN == 32) satp(30, 22) else satp(59, 44)
  private def satpRootFits(satp: UInt): Bool = {
    val ppn = satpPpn(satp)
    if (satpPpnBits >= (if (XLEN == 32) 22 else 44)) true.B
    else ppn((if (XLEN == 32) 21 else 43), satpPpnBits) === 0.U
  }
  private def tableBaseOf(satp: UInt): UInt =
    (satpPpn(satp)(satpPpnBits - 1, 0) << pageBits).pad(addressWidth)
  /** 与 independent PTW 相同：0=基础页，k=第 k 级超级页。 */
  private def fillLevelOf(level: UInt): UInt = (levels - 1).U - level

  // B 级：页表总线任务队列。每个槽位独立持有一笔在途读/写，响应按
  // 事务 ID 回收；8B PTE 的高字读仍由同一槽位顺序发出。
  val busQ = Module(new Queue(new BusJob, outstanding, pipe = true,
    flow = false, hasFlush = true))
  busQ.io.flush.get := io.flush

  val bIdle :: bReadReq :: bReadWait :: bHighReq :: bHighWait :: bWriteReq :: bWriteWait :: Nil =
    Enum(7)

  class BusSlot extends Bundle {
    val state = UInt(3.W)
    val job = new BusJob
    val lowData = UInt(32.W)
  }

  val busSlot = RegInit(VecInit(Seq.fill(busSlots)(0.U.asTypeOf(new BusSlot))))

  val s1Out = Wire(Valid(new ResolveJob))
  s1Out.valid := false.B
  s1Out.bits := 0.U.asTypeOf(new ResolveJob)

  io.bus.req.valid := false.B
  io.bus.req.bits := 0.U.asTypeOf(new ysyx_26030103_PTEBusRequest(addressWidth))
  busQ.io.deq.ready := false.B

  // 从任务队列填充空闲槽位。每拍最多分配一个任务；任务在真正发请求前
  // 已完整保存在槽位里，flush 后仍能用旧 epoch 收完在途事务并安全丢弃。
  val idleVec = VecInit((0 until busSlots).map(i => busSlot(i).state === bIdle))
  val anyIdle = idleVec.reduce(_ || _)
  val idleIdx = PriorityEncoder(idleVec)
  busQ.io.deq.ready := anyIdle && !io.flush
  when(busQ.io.deq.fire) {
    busSlot(idleIdx).job := busQ.io.deq.bits
    busSlot(idleIdx).lowData := 0.U
    busSlot(idleIdx).state := Mux(busQ.io.deq.bits.write, bWriteReq, bReadReq)
  }

  // 请求仲裁：A/D 写优先，其次是普通读和 8B 高字读；每拍只向页表
  // 总线发一个请求。事务 ID 固定为槽位号，因此响应可以乱序返回。
  val writeReqVec = VecInit((0 until busSlots).map(i =>
    busSlot(i).state === bWriteReq))
  val readReqVec = VecInit((0 until busSlots).map { i =>
    busSlot(i).state === bReadReq || busSlot(i).state === bHighReq
  })
  val anyWriteReq = writeReqVec.reduce(_ || _)
  val anyReadReq = readReqVec.reduce(_ || _)
  val reqVec = Mux(anyWriteReq, writeReqVec, readReqVec)
  val anyReq = anyWriteReq || anyReadReq
  val reqIdx = PriorityEncoder(reqVec)
  val reqSlot = busSlot(reqIdx)
  // flush 当拍不再向总线提交新请求，避免刚取消的槽位仍产生响应。
  io.bus.req.valid := anyReq && !io.flush
  when(anyReq) {
    io.bus.req.bits.id := reqIdx.pad(4)
    io.bus.req.bits.addr :=
      Mux(reqSlot.state === bHighReq, reqSlot.job.addr + 4.U, reqSlot.job.addr)
    io.bus.req.bits.write := reqSlot.state === bWriteReq
    io.bus.req.bits.data := Mux(reqSlot.state === bWriteReq,
      reqSlot.job.data(31, 0), 0.U)
    io.bus.req.bits.strb := Mux(reqSlot.state === bWriteReq, "hf".U(4.W), 0.U)
  }
  when(io.bus.req.fire) {
    switch(reqSlot.state) {
      is(bReadReq)  { busSlot(reqIdx).state := bReadWait }
      is(bHighReq)  { busSlot(reqIdx).state := bHighWait }
      is(bWriteReq) { busSlot(reqIdx).state := bWriteWait }
    }
  }

  // 响应按 ID 送回对应槽位。若 ID 不匹配任何等待槽位则保持 ready=0，
  // 避免吞掉无法归属的响应；正常总线上不应出现未知 ID。
  val waitVec = VecInit((0 until busSlots).map { i =>
    busSlot(i).state === bReadWait || busSlot(i).state === bHighWait ||
      busSlot(i).state === bWriteWait
  })
  val matchVec = VecInit((0 until busSlots).map { i =>
    waitVec(i) && io.bus.resp.bits.id === i.U(4.W)
  })
  val anyMatch = matchVec.reduce(_ || _)
  // 只把响应交给已经进入等待态的槽位。零延迟总线可能在请求握手同拍
  // 就给出响应，此时槽位状态要到下一拍才更新；保持 ready=0 让响应
  // 继续有效即可，下一拍再按 ID 接收。
  io.bus.resp.ready := anyMatch

  def emitResolve(job: BusJob, pteAddr: UInt, raw: UInt, fault: Bool,
      write: Bool): Unit = {
    s1Out.valid := true.B
    s1Out.bits.ctx := job.ctx
    s1Out.bits.pteAddr := pteAddr
    s1Out.bits.raw := raw
    s1Out.bits.fault := fault
    s1Out.bits.write := write
    s1Out.bits.epoch := job.epoch
  }

  for (i <- 0 until busSlots) {
    when(io.bus.resp.valid && matchVec(i)) {
      val job = busSlot(i).job
      switch(busSlot(i).state) {
        is(bReadWait) {
          if (pteBytes == 8) {
            // 8B PTE：先收低字，再由同一槽位发起高字读。
            when(io.bus.resp.bits.fault) {
              emitResolve(job, job.addr, io.bus.resp.bits.data, true.B, false.B)
              busSlot(i).state := bIdle
            }.otherwise {
              busSlot(i).lowData := io.bus.resp.bits.data
              busSlot(i).state := bHighReq
            }
          } else {
            emitResolve(job, job.addr, io.bus.resp.bits.data,
              io.bus.resp.bits.fault, false.B)
            busSlot(i).state := bIdle
          }
        }
        is(bHighWait) {
          emitResolve(job, job.addr,
            Cat(io.bus.resp.bits.data, busSlot(i).lowData),
            io.bus.resp.bits.fault, false.B)
          busSlot(i).state := bIdle
        }
        is(bWriteWait) {
          emitResolve(job, job.addr, job.data, io.bus.resp.bits.fault, true.B)
          busSlot(i).state := bIdle
        }
      }
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
  busQ.io.enq.valid := busEnqValid && !io.flush
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
    // 与 independent walker 一致：总线读到的原始 PTE 先填入 cache，即使
    // 它是非叶子或权限错误；cache 命中路径不需要重复写。8B PTE 的
    // resolveRaw 已包含低/高两个字，命中时可直接复用完整表项。
    when(resolveFromBus && !resolveWrite) { cacheFill(resolvePteAddr, resolveRaw) }

    when(resolveWrite) {
      // A/D 写回完成，写回数据就是最终原始 PTE（只写低 32 位）。
      cacheFill(resolvePteAddr, resolveRaw.pad(pteWidth))
      ctx(resolveCtx).needWrite := false.B
      ctx(resolveCtx).done := true.B
      ctx(resolveCtx).resp.paddr := paddrOf(d, level, vaddr)
      ctx(resolveCtx).resp.fault := resolveBusFault ||
        leafFaultOf(d, privilege, access, sum, mxr)
      ctx(resolveCtx).resp.cause := cause
      ctx(resolveCtx).resp.tval := vaddr
      ctx(resolveCtx).resp.fillPte := d
      ctx(resolveCtx).resp.fillVaddr := vaddr
      ctx(resolveCtx).resp.fillAsid := satpAsid(ctx(resolveCtx).satp)
      ctx(resolveCtx).resp.fillLevel := fillLevelOf(level)
      ctx(resolveCtx).resp.fillValid := !resolveBusFault &&
        !leafFaultOf(d, privilege, access, sum, mxr) &&
        (fillSuperpage.B || level === (levels - 1).U)
    }.elsewhen(resolveBusFault || rawReservedFault(resolveRaw) ||
        walkFault(d, level)) {
      ctx(resolveCtx).needWrite := false.B
      ctx(resolveCtx).done := true.B
      ctx(resolveCtx).resp := 0.U.asTypeOf(
        new ysyx_26030103_PTWResponse(XLEN, addressWidth, pteWidth))
      ctx(resolveCtx).resp.id := ctx(resolveCtx).id
      ctx(resolveCtx).resp.fault := true.B
      ctx(resolveCtx).resp.cause := cause
      ctx(resolveCtx).resp.tval := vaddr
    }.elsewhen(leaf) {
      when(needsAdUpdate(d, isStore)) {
        ctx(resolveCtx).needWrite := true.B
        // A/D 写回必须基于原始 PTE 的字段位置，不能对归一化 PTE 使用
        // pteConfig 的私有位置。
        ctx(resolveCtx).writeData := adRaw(resolveRaw, isStore)
      }.otherwise {
        ctx(resolveCtx).done := true.B
        ctx(resolveCtx).resp.paddr := paddrOf(d, level, vaddr)
        ctx(resolveCtx).resp.fault := leafFault
        ctx(resolveCtx).resp.cause := cause
        ctx(resolveCtx).resp.tval := vaddr
        ctx(resolveCtx).resp.fillPte := d
        ctx(resolveCtx).resp.fillVaddr := vaddr
        ctx(resolveCtx).resp.fillAsid := satpAsid(ctx(resolveCtx).satp)
        ctx(resolveCtx).resp.fillLevel := fillLevelOf(level)
        ctx(resolveCtx).resp.fillValid := !leafFault &&
          (fillSuperpage.B || level === (levels - 1).U)
      }
    }.otherwise {
      ctx(resolveCtx).table :=
        (ppnValue(d) << pageBits).pad(addressWidth)
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
    ctx(freeIndex).vaddr := io.req.bits.vaddr
    ctx(freeIndex).satp := io.req.bits.satp
    ctx(freeIndex).privilege.foreach { p => p := io.req.bits.privilege }
    ctx(freeIndex).access := io.req.bits.access
    ctx(freeIndex).sum.foreach { s => s := io.req.bits.sum }
    ctx(freeIndex).mxr := io.req.bits.mxr
    ctx(freeIndex).level := 0.U
    ctx(freeIndex).pteAddr := 0.U
    ctx(freeIndex).writeData := 0.U
    ctx(freeIndex).age := nextAge
    ctx(freeIndex).resp := 0.U.asTypeOf(
      new ysyx_26030103_PTWResponse(XLEN, addressWidth, pteWidth))
    ctx(freeIndex).resp.id := io.req.bits.id
    when(!canonicalOk(io.req.bits.vaddr) || !satpRootFits(io.req.bits.satp)) {
      // 非规范 VA 或无法装入物理地址的 satp.PPN 在查页表前直接页错误。
      ctx(freeIndex).done := true.B
      ctx(freeIndex).table := 0.U
      ctx(freeIndex).resp.fault := true.B
      ctx(freeIndex).resp.cause := pageFaultCause(io.req.bits.access)
      ctx(freeIndex).resp.tval := io.req.bits.vaddr
    }.otherwise {
      ctx(freeIndex).done := false.B
      ctx(freeIndex).table := tableBaseOf(io.req.bits.satp)
    }
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

  // flush 使旧总线事务的 epoch 失效；B 级各槽位会继续收完已在总线上的
  // 响应并丢弃，避免复位上下文后旧响应污染复用同一槽位的新请求。
  when(io.flush) {
    cache.foreach { c => for (i <- 0 until cacheEntries) { c(i).valid := false.B } }
    // 尚未发到总线上的槽位直接取消；已在等待响应的槽位继续收完，
    // 之后由 epoch 检查安全丢弃，避免总线响应无人接收。
    for (i <- 0 until busSlots) {
      when(busSlot(i).state === bReadReq || busSlot(i).state === bHighReq ||
          busSlot(i).state === bWriteReq) {
        busSlot(i).state := bIdle
      }
    }
    for (i <- 0 until outstanding) {
      ctx(i) := 0.U.asTypeOf(new Context)
    }
    nextAge := 0.U
    epoch := epoch + 1.U
  }
}
