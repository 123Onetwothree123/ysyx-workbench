package ysyx_26030103.mmu
import _root_.ysyx_26030103.common.ysyx_26030103_XLENRequire
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import _root_.ysyx_26030103.common.ysyx_26030103_AnyOf

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_PTEConfig
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile

// 三级流水 page-table walker：
//   A 级：为在途请求计算当前级 PTE 地址并查 PTE cache，命中直接送 C 级，
//         缺失时把页表读请求放入总线队列。
//   B 级：多槽页表总线引擎，槽位按事务 ID 匹配响应，可重叠甚至乱序；
//         8B PTE 的低/高字仍由同一槽位顺序读取。
//   C 级：解析 PTE、检查权限/超级页、推进级数或产生最终响应。
// 同一请求的各级存在数据依赖；重叠发生在不同请求之间及 B 级槽位之间。
class ysyx_26030103_PTWPipeline(
    outstanding: Int = ysyx_26030103_KconfigDefaults.MMUPTWOutstanding,
    busOutstanding: Int = ysyx_26030103_KconfigDefaults.MMUPTWBusOutstanding,
    adMode: String = "fault",
    cacheEnable: Boolean = false,
    cacheEntries: Int = ysyx_26030103_KconfigDefaults.MMUPTWCacheEntries,
    cacheWays: Int = ysyx_26030103_KconfigDefaults.MMUPTWCacheWays,
    superpage: Boolean = false,
    PTEConfig: ysyx_26030103_PTEConfig = ysyx_26030103_PTEConfig(),
    pageBits: Int = ysyx_26030103_KconfigDefaults.MMUPageBits,
    levels: Int = ysyx_26030103_KconfigDefaults.MMULevels,
    tableEntries: Int = ysyx_26030103_KconfigDefaults.MMUTableEntries,
    fillSuperpage: Boolean = false,
    PTESizeBytes: Int = ysyx_26030103_KconfigDefaults.MMUPTESizeBytes,
    inOrder: Boolean = true,
    ResponsePorts: Int = 1,
    hasU: Boolean = true,
    XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    addressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    vaBits: Int = ysyx_26030103_KconfigDefaults.MMUVaBits
) extends ysyx_26030103_PTWBackend(ResponsePorts, XLEN, addressWidth, PTESizeBytes * 8) {
  require(Set("fault", "ignore", "hardware").contains(adMode), "未知 PTW A/D 模式")
  ysyx_26030103_XLENRequire(XLEN, "PTW pipeline")
  require(addressWidth == 32 || addressWidth == 64,
    s"PTW pipeline addressWidth 非法: $addressWidth")
  require(vaBits >= 2 && vaBits <= XLEN, s"PTW pipeline vaBits 非法: $vaBits")
  require(outstanding >= 1 && outstanding <= 16,
    "PTW outstanding 必须在 1..16（页表总线事务 ID 为 4 位）")
  require(busOutstanding >= 0 && busOutstanding <= 16,
    "PTW 页表总线 outstanding 必须在 0..16（0 表示跟随 outstanding）")
  require(ResponsePorts >= 1, "PTW 响应端口数必须 >= 1")
  require(pageBits >= 2 && pageBits < vaBits, "pageBits 非法")
  require(levels >= 1 && levels <= ysyx_26030103_MMUProfile.MaxLevels,
    s"页表级数必须在 1..${ysyx_26030103_MMUProfile.MaxLevels}" +
      s"（fillLevel 为 ${ysyx_26030103_MMUProfile.LevelBits} 位）")
  require(tableEntries >= 2 && (tableEntries & (tableEntries - 1)) == 0,
    "每级页表项数必须是不小于 2 的 2 的幂")
  require(PTESizeBytes == 4 || PTESizeBytes == 8, "PTE 大小只支持 4/8 字节")
  require(XLEN == 32 || PTEConfig.PTEBits == PTESizeBytes * 8,
    "RV64 PTW 的 PTE 字段配置位宽必须与 PTE 大小一致")
  // 8B PTE 的 A/D 硬件写回走现有 32 位页表写端口，只更新低字。A/D 位
  // 落在高字时无法正确写回，必须在 elaboration 期拒绝。
  require(adMode != "hardware" || PTESizeBytes <= 4 ||
    (PTEConfig.aBit < 32 && PTEConfig.dBit < 32),
    "64 位 PTE 的 A/D 硬件写回要求 A/D 位位于低 32 位字内")
  if (cacheEnable) {
    require(cacheEntries > 0 && (cacheEntries & (cacheEntries - 1)) == 0,
      "PTW cache entries 必须是 2 的幂")
    require(cacheWays > 0 && cacheEntries % cacheWays == 0,
      "PTW cache ways 必须整除 entries")
    require((cacheWays & (cacheWays - 1)) == 0, "PTW cache ways 必须是 2 的幂")
  }

  private val PTEBytes = PTESizeBytes
  private val PTEShift = log2Ceil(PTEBytes)
  private val indexBits = log2Ceil(tableEntries)
  private val PTEPPNBits = PTEConfig.PPNHigh - PTEConfig.PPNLow + 1
  // 与 independent PTW 相同：pageBits+PPN 超过物理地址宽度时，只使用
  // 低位 PPN，高位在 walk 时按保留位检查并要求为 0。
  private val PaddrPPNBits = math.min(PTEPPNBits, addressWidth - pageBits)
  require(PaddrPPNBits >= 1, "PPN 字段无法装入物理地址宽度")
  private val SatpPPNBits = math.min(44, addressWidth - pageBits)
  require(SatpPPNBits >= 1, "satp.PPN 无法装入物理地址宽度")
  require(pageBits + levels * indexBits == vaBits,
    s"pageBits($pageBits) + levels($levels)*indexBits($indexBits) 必须等于 $vaBits")
  require(!(superpage || fillSuperpage) ||
    PTEPPNBits >= (levels - 1) * indexBits,
    s"超级页对齐位(${(levels - 1) * indexBits})超过 PTE PPN 位宽($PTEPPNBits)")

  private val contextBits = log2Ceil(outstanding.max(2))
  // 页表总线槽位数：默认与上下文数相同，每个上下文最多一笔在途事务；
  // 可通过 busOutstanding 单独收缩，便于面积/时序权衡。
  private val busSlots =
    if (busOutstanding <= 0) outstanding else math.min(busOutstanding, outstanding)
  private val ageBits = 16
  private val epochBits = 8
  private val PTEWidth = PTEBytes * 8
  // 4B/8B PTE 都支持 PTE cache；8B 表项保存低/高字，命中后无需再发
  // 两次页表读。
  private val cacheEffective = cacheEnable

  class Context extends Bundle {
    val ID = UInt(4.W)
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
    val PTEAddress = UInt(addressWidth.W)
    val writeData = UInt(PTEWidth.W)
    val age = UInt(ageBits.W)
    val response = new ysyx_26030103_PTWResponse(XLEN, addressWidth, PTEWidth)
  }

  // A 级交给 B 级的页表总线任务。
  class BusJob extends Bundle {
    val ctx = UInt(contextBits.W)
    val address = UInt(addressWidth.W)
    val write = Bool()
    // A/D 写回时保存完整 PTE，总线只发送低 32 位。
    val data = UInt(PTEWidth.W)
    val epoch = UInt(epochBits.W)
  }

  // B 级交给 C 级的已完成页表项。
  class ResolveJob extends Bundle {
    val ctx = UInt(contextBits.W)
    val PTEAddress = UInt(addressWidth.W)
    // 8B PTE 时保存低/高字拼接后的完整 64 位原始值。
    val raw = UInt(PTEWidth.W)
    val fault = Bool()
    val write = Bool()
    val epoch = UInt(epochBits.W)
  }

  val context = RegInit(VecInit(Seq.fill(outstanding)(0.U.asTypeOf(new Context))))
  val nextAge = RegInit(0.U(ageBits.W))
  val epoch = RegInit(0.U(epochBits.W))

  // PTE cache 与 independent 版本一致：保存原始 PTE，4B/8B 都启用。
  private val cache = new ysyx_26030103_PTECache(
    cacheEffective, cacheEntries, cacheWays, addressWidth, PTEWidth, PTEShift)

  private def normalizePTE(PTE: UInt): UInt =
    ysyx_26030103_PTEDecode.normalizePTE(PTE, PTEConfig, PTEWidth)
  private def adRaw(PTE: UInt, isStore: Bool): UInt =
    ysyx_26030103_PTEDecode.adRaw(PTE, isStore, PTEConfig, PTEWidth)

  // 这些函数接收 normalizePte 之后的内部 PTE：标志位固定在 bit0..7，
  // PPN 位置仍由 PTEConfig 决定。CUSTOM 私有标志位置不能用于解释
  // 归一化结果。
  private def isLeaf(PTE: UInt): Bool =
    ysyx_26030103_PTEDecode.isLeaf(PTE)
  // R=0 且 W=1 是保留编码；无论叶子还是非叶子都必须 page fault。
  private def reservedEncoding(PTE: UInt): Bool =
    ysyx_26030103_PTEDecode.reservedEncoding(PTE)
  private def reservedFault(PTE: UInt): Bool =
    ysyx_26030103_PTEDecode.reservedFault(PTE, PTEConfig, PTEWidth)
  // PPN 字段以上的保留位随 PTEConfig 移动；标准 Sv39/Sv48 即 PTE[63:54]，
  // CUSTOM 重新定义 PPN 高位时不能继续硬编码 54。
  private def rawReservedFault(raw: UInt): Bool =
    ysyx_26030103_PTEDecode.rawReservedFault(raw, PTEConfig, XLEN, PTEWidth,
      false, false.B)
  private def superpageMisaligned(PTE: UInt, level: UInt): Bool =
    ysyx_26030103_PTEDecode.superpageMisaligned(PTE, level, PTEConfig,
      pageBits, levels, vaBits)
  private def ppnFits(PTE: UInt): Bool =
    ysyx_26030103_PTEDecode.ppnFits(PTE, PTEConfig, pageBits, addressWidth)
  private def ppnValue(PTE: UInt): UInt =
    ysyx_26030103_PTEDecode.ppnValue(PTE, PTEConfig, pageBits, addressWidth)
  private def walkFault(PTE: UInt, level: UInt): Bool =
    ysyx_26030103_PTEDecode.walkFault(PTE, level, PTEConfig, PTEWidth,
      pageBits, levels, vaBits, addressWidth, superpage)
  private def leafFaultOf(PTE: UInt, privilege: UInt, access: UInt,
      sum: Bool, mxr: Bool): Bool =
    ysyx_26030103_PTEDecode.leafFaultOf(PTE, privilege, access, sum, mxr,
      adMode, hasU)
  private def needsAdUpdate(PTE: UInt, isStore: Bool): Bool =
    ysyx_26030103_PTEDecode.needsAdUpdate(PTE, isStore, adMode)
  private def pageFaultCause(access: UInt): UInt =
    ysyx_26030103_PTEDecode.pageFaultCause(access)
  // 页表总线错误的 cause：取指=1，load=5，store=7。
  private def accessFaultCause(access: UInt): UInt =
    ysyx_26030103_PTEDecode.accessFaultCause(access)
  private def vpnIndex(virtualAddress: UInt, level: UInt): UInt =
    ysyx_26030103_PTEDecode.vpnIndex(virtualAddress, level, pageBits,
      levels, vaBits)
  private def tableAddress(base: UInt, index: UInt): UInt =
    ysyx_26030103_PTEDecode.tableAddress(base, index, addressWidth, PTEWidth)
  private def superOffsetBits(level: UInt): UInt =
    ysyx_26030103_PTEDecode.superOffsetBits(level, pageBits, levels, vaBits)
  private def paddrOf(PTE: UInt, level: UInt, virtualAddress: UInt): UInt =
    ysyx_26030103_PTEDecode.paddrOf(PTE, level, virtualAddress, false.B,
      false, PTEConfig, pageBits, levels, vaBits, XLEN, addressWidth)
  private def canonicalOk(virtualAddress: UInt): Bool =
    ysyx_26030103_PTEDecode.canonicalOk(virtualAddress, vaBits, XLEN)
  private def satpPPN(satp: UInt): UInt =
    ysyx_26030103_PTEDecode.satpPPN(satp, XLEN)
  private def satpASID(satp: UInt): UInt =
    ysyx_26030103_PTEDecode.satpASID(satp, XLEN)
  private def satpRootFits(satp: UInt): Bool =
    ysyx_26030103_PTEDecode.satpRootFits(satp, XLEN, pageBits, addressWidth)
  private def tableBaseOf(satp: UInt): UInt =
    ysyx_26030103_PTEDecode.tableBaseOf(satp, XLEN, pageBits, addressWidth)
  // 与 independent PTW 相同：0=基础页，k=第 k 级超级页。
  private def fillLevelOf(level: UInt): UInt =
    ysyx_26030103_PTEDecode.fillLevelOf(level, levels)

  // B 级：页表总线任务队列。每个槽位独立持有一笔在途读/写，响应按
  // 事务 ID 回收；8B PTE 的高字读仍由同一槽位顺序发出。
  val busQ = Module(new Queue(new BusJob, outstanding, pipe = true,
    flow = false, hasFlush = true))
  busQ.io.flush.get := io.flush

  val bIdle :: bReadRequest :: bReadWait :: bHighRequest :: bHighWait :: bWriteRequest :: bWriteWait :: Nil =
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

  io.bus.request.bits := 0.U.asTypeOf(new ysyx_26030103_PTEBusRequest(addressWidth))

  // 从任务队列填充空闲槽位。每拍最多分配一个任务；任务在真正发请求前
  // 已完整保存在槽位里，flush 后仍能用旧 epoch 收完在途事务并安全丢弃。
  val idleVec = VecInit((0 until busSlots).map(i => busSlot(i).state === bIdle))
  val anyIdle = idleVec.reduce(_ || _)
  val idleIndex = PriorityEncoder(idleVec)
  busQ.io.deq.ready := anyIdle && !io.flush
  when(busQ.io.deq.fire) {
    busSlot(idleIndex).job := busQ.io.deq.bits
    busSlot(idleIndex).lowData := 0.U
    busSlot(idleIndex).state := Mux(busQ.io.deq.bits.write, bWriteRequest, bReadRequest)
  }

  // 请求仲裁：A/D 写优先，其次是普通读和 8B 高字读；每拍只向页表
  // 总线发一个请求。事务 ID 固定为槽位号，因此响应可以乱序返回。
  val writeRequestVec = VecInit((0 until busSlots).map(i =>
    busSlot(i).state === bWriteRequest))
  val readRequestVec = VecInit((0 until busSlots).map { i =>
    busSlot(i).state === bReadRequest || busSlot(i).state === bHighRequest
  })
  val anyWriteRequest = writeRequestVec.reduce(_ || _)
  val anyReadRequest = readRequestVec.reduce(_ || _)
  val requestVec = Mux(anyWriteRequest, writeRequestVec, readRequestVec)
  val anyRequest = anyWriteRequest || anyReadRequest
  val requestIndex = PriorityEncoder(requestVec)
  val requestSlot = busSlot(requestIndex)
  // flush 当拍不再向总线提交新请求，避免刚取消的槽位仍产生响应。
  io.bus.request.valid := anyRequest && !io.flush
  when(anyRequest) {
    io.bus.request.bits.ID := requestIndex.pad(4)
    io.bus.request.bits.address :=
      Mux(requestSlot.state === bHighRequest, requestSlot.job.address + 4.U, requestSlot.job.address)
    io.bus.request.bits.write := requestSlot.state === bWriteRequest
    io.bus.request.bits.data := Mux(requestSlot.state === bWriteRequest,
      requestSlot.job.data(31, 0), 0.U)
    io.bus.request.bits.strobe := Mux(requestSlot.state === bWriteRequest, "hf".U(4.W), 0.U)
  }
  when(io.bus.request.fire) {
    switch(requestSlot.state) {
      is(bReadRequest)  { busSlot(requestIndex).state := bReadWait }
      is(bHighRequest)  { busSlot(requestIndex).state := bHighWait }
      is(bWriteRequest) { busSlot(requestIndex).state := bWriteWait }
    }
  }

  // 响应按 ID 送回对应槽位。若 ID 不匹配任何等待槽位则保持 ready=0，
  // 避免吞掉无法归属的响应；正常总线上不应出现未知 ID。
  val waitVec = VecInit((0 until busSlots).map { i =>
    busSlot(i).state === bReadWait || busSlot(i).state === bHighWait ||
      busSlot(i).state === bWriteWait
  })
  val matchVec = VecInit((0 until busSlots).map { i =>
    waitVec(i) && io.bus.response.bits.ID === i.U(4.W)
  })
  val anyMatch = matchVec.reduce(_ || _)
  // 只把响应交给已经进入等待态的槽位。零延迟总线可能在请求握手同拍
  // 就给出响应，此时槽位状态要到下一拍才更新；保持 ready=0 让响应
  // 继续有效即可，下一拍再按 ID 接收。
  io.bus.response.ready := anyMatch

  def emitResolve(job: BusJob, PTEAddress: UInt, raw: UInt, fault: Bool,
      write: Bool): Unit = {
    s1Out.valid := true.B
    s1Out.bits.ctx := job.ctx
    s1Out.bits.PTEAddress := PTEAddress
    s1Out.bits.raw := raw
    s1Out.bits.fault := fault
    s1Out.bits.write := write
    s1Out.bits.epoch := job.epoch
  }

  for (i <- 0 until busSlots) {
    when(io.bus.response.valid && matchVec(i)) {
      val job = busSlot(i).job
      switch(busSlot(i).state) {
        is(bReadWait) {
          if (PTEBytes == 8) {
            // 8B PTE：先收低字，再由同一槽位发起高字读。
            when(io.bus.response.bits.fault) {
              emitResolve(job, job.address, io.bus.response.bits.data, true.B, false.B)
              busSlot(i).state := bIdle
            }.otherwise {
              busSlot(i).lowData := io.bus.response.bits.data
              busSlot(i).state := bHighRequest
            }
          } else {
            emitResolve(job, job.address, io.bus.response.bits.data,
              io.bus.response.bits.fault, false.B)
            busSlot(i).state := bIdle
          }
        }
        is(bHighWait) {
          emitResolve(job, job.address,
            Cat(io.bus.response.bits.data, busSlot(i).lowData),
            io.bus.response.bits.fault, false.B)
          busSlot(i).state := bIdle
        }
        is(bWriteWait) {
          emitResolve(job, job.address, job.data, io.bus.response.bits.fault, true.B)
          busSlot(i).state := bIdle
        }
      }
    }
  }

  // A 级：有 A/D 写回待办时优先发写；否则为最早的空闲 walk 计算地址并查 cache。
  val writeEligible = VecInit((0 until outstanding).map { i =>
    context(i).busy && !context(i).done && !context(i).inFlight && context(i).needWrite
  })
  val anyWrite = writeEligible.reduce(_ || _)
  val writeIndex = PriorityEncoder(writeEligible)

  val walkEligible = VecInit((0 until outstanding).map { i =>
    context(i).busy && !context(i).done && !context(i).inFlight && !context(i).needWrite
  })
  val anyWalk = walkEligible.reduce(_ || _)
  val walkIndex = PriorityEncoder(walkEligible)

  val walkVirtualAddress = context(walkIndex).vaddr
  val walkLevel = context(walkIndex).level
  val NextPTEAddress = tableAddress(context(walkIndex).table, vpnIndex(walkVirtualAddress, walkLevel))
  val (cacheHit, cacheData) = cache.lookup(NextPTEAddress)

  val newJob = Wire(new BusJob)
  newJob := 0.U.asTypeOf(new BusJob)
  newJob.ctx := Mux(anyWrite, writeIndex, walkIndex)
  newJob.address := Mux(anyWrite, context(writeIndex).PTEAddress, NextPTEAddress)
  newJob.write := anyWrite
  newJob.data := Mux(anyWrite, context(writeIndex).writeData, 0.U)
  newJob.epoch := epoch

  // 无论 B 级是否正在等响应，A 级都可以继续为其它上下文算地址。
  val busEnqValid = anyWrite || (anyWalk && !cacheHit)
  busQ.io.enq.valid := busEnqValid && !io.flush
  busQ.io.enq.bits := newJob
  when(busQ.io.enq.fire) {
    context(newJob.ctx).inFlight := true.B
  }

  // C 级：独占一个解析入口。B 级结果优先，cache 命中在 B 级空闲时同拍解析。
  val busResultValid = s1Out.valid && s1Out.bits.epoch === epoch
  val hitValid = anyWalk && cacheHit && !busResultValid
  val resolveValid = busResultValid || hitValid
  val resolveContext = Mux(busResultValid, s1Out.bits.ctx, walkIndex)
  val ResolvePTEAddress = Mux(busResultValid, s1Out.bits.PTEAddress, NextPTEAddress)
  val resolveRaw = Mux(busResultValid, s1Out.bits.raw, cacheData)
  val resolveBusFault = Mux(busResultValid, s1Out.bits.fault, false.B)
  val resolveWrite = Mux(busResultValid, s1Out.bits.write, false.B)
  val resolveFromBus = busResultValid

  when(resolveValid) {
    val PTE = normalizePTE(resolveRaw)
    val level = context(resolveContext).level
    val virtualAddress = context(resolveContext).vaddr
    val privilege = context(resolveContext).privilege.getOrElse(0.U(2.W))
    val access = context(resolveContext).access
    val sum = context(resolveContext).sum.getOrElse(false.B)
    val mxr = context(resolveContext).mxr
    val isStore = access === ysyx_26030103_AccessType.Store
    val cause = pageFaultCause(access)
    val busFaultCause = accessFaultCause(access)
    val leaf = isLeaf(PTE)
    val leafFault = leafFaultOf(PTE, privilege, access, sum, mxr)

    context(resolveContext).inFlight := false.B
    context(resolveContext).PTEAddress := ResolvePTEAddress
    // 与 independent walker 一致：总线读到的原始 PTE 先填入 cache，即使
    // 它是非叶子或权限错误；cache 命中路径不需要重复写。8B PTE 的
    // resolveRaw 已包含低/高两个字，命中时可直接复用完整表项。总线
    // 错误的数据不是有效 PTE，不能写入 cache。
    when(resolveFromBus && !resolveWrite && !resolveBusFault) {
      cache.fill(ResolvePTEAddress, resolveRaw)
    }

    when(resolveWrite) {
      // A/D 写回完成，写回数据就是最终原始 PTE（只写低 32 位）。
      when(cacheEffective.B && !resolveBusFault) {
        cache.fill(ResolvePTEAddress, resolveRaw.pad(PTEWidth))
      }
      context(resolveContext).needWrite := false.B
      context(resolveContext).done := true.B
      context(resolveContext).response.paddr := paddrOf(PTE, level, virtualAddress)
      context(resolveContext).response.fault := resolveBusFault ||
        leafFaultOf(PTE, privilege, access, sum, mxr)
      context(resolveContext).response.cause :=
        Mux(resolveBusFault, busFaultCause, cause)
      context(resolveContext).response.tval := virtualAddress
      context(resolveContext).response.FillPTE := PTE
      context(resolveContext).response.FillVirtualAddress := virtualAddress
      context(resolveContext).response.FillASID := satpASID(context(resolveContext).satp)
      context(resolveContext).response.FillLevel := fillLevelOf(level)
      context(resolveContext).response.FillNapot := false.B
      context(resolveContext).response.FillValid := !resolveBusFault &&
        !leafFaultOf(PTE, privilege, access, sum, mxr) &&
        (fillSuperpage.B || level === (levels - 1).U)
    }.elsewhen(resolveBusFault || rawReservedFault(resolveRaw) ||
        walkFault(PTE, level)) {
      context(resolveContext).needWrite := false.B
      context(resolveContext).done := true.B
      context(resolveContext).response := 0.U.asTypeOf(
        new ysyx_26030103_PTWResponse(XLEN, addressWidth, PTEWidth))
      context(resolveContext).response.ID := context(resolveContext).ID
      context(resolveContext).response.fault := true.B
      context(resolveContext).response.cause :=
        Mux(resolveBusFault, busFaultCause, cause)
      context(resolveContext).response.tval := virtualAddress
    }.elsewhen(leaf) {
      when(needsAdUpdate(PTE, isStore)) {
        context(resolveContext).needWrite := true.B
        // A/D 写回必须基于原始 PTE 的字段位置，不能对归一化 PTE 使用
        // PTEConfig 的私有位置。
        context(resolveContext).writeData := adRaw(resolveRaw, isStore)
      }.otherwise {
        context(resolveContext).done := true.B
        context(resolveContext).response.paddr := paddrOf(PTE, level, virtualAddress)
        context(resolveContext).response.fault := leafFault
        context(resolveContext).response.cause := cause
        context(resolveContext).response.tval := virtualAddress
        context(resolveContext).response.FillPTE := PTE
        context(resolveContext).response.FillVirtualAddress := virtualAddress
        context(resolveContext).response.FillASID := satpASID(context(resolveContext).satp)
        context(resolveContext).response.FillLevel := fillLevelOf(level)
        context(resolveContext).response.FillNapot := false.B
        context(resolveContext).response.FillValid := !leafFault &&
          (fillSuperpage.B || level === (levels - 1).U)
      }
    }.otherwise {
      context(resolveContext).table :=
        (ppnValue(PTE) << pageBits).pad(addressWidth)
      context(resolveContext).level := level + 1.U
    }
  }

  // 上下文分配：每个空闲槽对应一笔在途请求。
  val freeCandidates = VecInit((0 until outstanding).map(i => !context(i).busy))
  val anyFree = freeCandidates.reduce(_ || _)
  val freeIndex = PriorityEncoder(freeCandidates)
  io.request.ready := anyFree && !io.flush
  when(io.request.fire) {
    context(freeIndex).ID := io.request.bits.ID
    context(freeIndex).busy := true.B
    context(freeIndex).inFlight := false.B
    context(freeIndex).needWrite := false.B
    context(freeIndex).vaddr := io.request.bits.vaddr
    context(freeIndex).satp := io.request.bits.satp
    context(freeIndex).privilege.foreach { privilegeRegister => privilegeRegister := io.request.bits.privilege }
    context(freeIndex).access := io.request.bits.access
    context(freeIndex).sum.foreach { sumRegister => sumRegister := io.request.bits.sum }
    context(freeIndex).mxr := io.request.bits.mxr
    context(freeIndex).level := 0.U
    context(freeIndex).PTEAddress := 0.U
    context(freeIndex).writeData := 0.U
    context(freeIndex).age := nextAge
    context(freeIndex).response := 0.U.asTypeOf(
      new ysyx_26030103_PTWResponse(XLEN, addressWidth, PTEWidth))
    context(freeIndex).response.ID := io.request.bits.ID
    when(!canonicalOk(io.request.bits.vaddr) || !satpRootFits(io.request.bits.satp)) {
      // 非规范 VA 或无法装入物理地址的 satp.PPN 在查页表前直接页错误。
      context(freeIndex).done := true.B
      context(freeIndex).table := 0.U
      context(freeIndex).response.fault := true.B
      context(freeIndex).response.cause := pageFaultCause(io.request.bits.access)
      context(freeIndex).response.tval := io.request.bits.vaddr
    }.otherwise {
      context(freeIndex).done := false.B
      context(freeIndex).table := tableBaseOf(io.request.bits.satp)
    }
    nextAge := nextAge + 1.U
  }

  // 响应排序：in_order 只允许最老的未返回请求先走；out_of_order 可选任意
  // 已完成请求。多个响应端口按年龄/优先级依次消去，单拍可回填多个 TLB 表项。
  def olderThan(a: UInt, b: UInt): Bool = (a - b)(ageBits - 1)
  var taken: Seq[Bool] = Seq.fill(outstanding)(false.B)
  for (port <- 0 until ResponsePorts) {
    val eligible = (0 until outstanding).map { i =>
      val olderPending = ysyx_26030103_AnyOf((0 until outstanding).map { j =>
        if (j == i) false.B
        else context(j).busy && !taken(j) && olderThan(context(j).age, context(i).age)
      })
      val orderOk = if (inOrder) !olderPending else true.B
      context(i).busy && context(i).done && !taken(i) && orderOk
    }
    val eligibleVec = VecInit(eligible)
    val valid = eligibleVec.reduce(_ || _)
    val index = PriorityEncoder(eligibleVec)
    io.response(port).valid := valid
    io.response(port).bits := context(index).response
    when(valid && io.response(port).ready) {
      context(index).busy := false.B
    }
    taken = taken.zipWithIndex.map { case (already, i) =>
      already || (valid && index === i.U)
    }
  }

  // flush 使旧总线事务的 epoch 失效；B 级各槽位会继续收完已在总线上的
  // 响应并丢弃，避免复位上下文后旧响应污染复用同一槽位的新请求。
  when(io.flush) {
    cache.invalidate()
    // 尚未发到总线上的槽位直接取消；已在等待响应的槽位继续收完，
    // 之后由 epoch 检查安全丢弃，避免总线响应无人接收。
    for (i <- 0 until busSlots) {
      when(busSlot(i).state === bReadRequest || busSlot(i).state === bHighRequest ||
          busSlot(i).state === bWriteRequest) {
        busSlot(i).state := bIdle
      }
    }
    for (i <- 0 until outstanding) {
      context(i) := 0.U.asTypeOf(new Context)
    }
    nextAge := 0.U
    epoch := epoch + 1.U
  }
}
