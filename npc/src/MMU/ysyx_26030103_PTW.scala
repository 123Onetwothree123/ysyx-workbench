package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_PteConfig
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile

class ysyx_26030103_PTWRequest(
    val XLEN: Int = 32,
    val AddressWidth: Int = 32
) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"PTWRequest XLEN 非法: $XLEN")
  require(AddressWidth == 32 || AddressWidth == 64,
    s"PTWRequest AddressWidth 非法: $AddressWidth")
  val id = UInt(4.W)
  val vaddr = UInt(XLEN.W)
  val satp = UInt(XLEN.W)
  val privilege = UInt(2.W)
  val access = UInt(2.W)
  val sum = Bool()
  val mxr = Bool()
}

class ysyx_26030103_PTWResponse(
    val XLEN: Int = 32,
    val AddressWidth: Int = 32,
    val pteWidth: Int = 32
) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"PTWResponse XLEN 非法: $XLEN")
  require(AddressWidth == 32 || AddressWidth == 64,
    s"PTWResponse AddressWidth 非法: $AddressWidth")
  require(pteWidth == 32 || pteWidth == 64,
    s"PTWResponse pteWidth 非法: $pteWidth")
  val id = UInt(4.W)
  val paddr = UInt(AddressWidth.W)
  val fault = Bool()
  val cause = UInt(32.W)
  val tval = UInt(XLEN.W)
  val fillValid = Bool()
  val fillPte = UInt(pteWidth.W)
  val fillVaddr = UInt(XLEN.W)
  val fillAsid = UInt(16.W)
  // 0=基础页，k=第 k 级超级页；TLB 用 pageBits + k*indexBits 推导 shift。
  // 3 位统一编码支持最多 8 级页表，不再把 CUSTOM 限制在 4 级。
  val fillLevel = UInt(ysyx_26030103_MMUProfile.LevelBits.W)
}

/** 可配置页大小/级数/页表项数的 page-table walker。
  *
  * RV32/Sv32 与 RV64/Sv39/Sv48 共用同一套逐级 walk 状态机：
  *   - vaBits = pageBits + levels * indexBits（32/39/48）；
  *   - PTE 宽度由 pteSizeBytes 决定（Sv32=4B，Sv39/Sv48=8B）；
  *   - satp 的 MODE/ASID/PPN 位置按 XLEN 选择；
  *   - Sv39/Sv48 的 64 位 PTE 使用 bit 53:10 的 44 位 PPN，A/D 写回只
  *     修改包含标志位的低 32 位字。
  */
class ysyx_26030103_PTW(
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
    hasU: Boolean = true,
    // 架构宽度与物理地址宽度；默认 32/32 保持既有 Sv32 行为。
    XLEN: Int = 32,
    addressWidth: Int = 32,
    // 有效虚拟地址位数：Sv32=32，Sv39=39，Sv48=48。
    vaBits: Int = 32,
    // 该 walker 在页表总线上的固定事务 ID；多 walker 簇用 walker 编号，
    // 单 walker/直连测试保持 0。
    busId: Int = 0
) extends Module {
  require(Set("fault", "ignore", "hardware").contains(adMode), "未知 PTW A/D 模式")
  require(busId >= 0 && busId <= 15, "PTW busId 必须在 0..15")
  require(XLEN == 32 || XLEN == 64, s"PTW XLEN 非法: $XLEN")
  require(addressWidth == 32 || addressWidth == 64,
    s"PTW addressWidth 非法: $addressWidth")
  require(vaBits >= 2 && vaBits <= XLEN, s"PTW vaBits 非法: $vaBits")
  require(pageBits >= 2 && pageBits < vaBits, "pageBits 非法")
  require(levels >= 1 && levels <= ysyx_26030103_MMUProfile.MaxLevels,
    s"页表级数必须在 1..${ysyx_26030103_MMUProfile.MaxLevels}" +
      s"（fillLevel 为 ${ysyx_26030103_MMUProfile.LevelBits} 位）")
  require(tableEntries >= 2 && (tableEntries & (tableEntries - 1)) == 0,
    "每级页表项数必须是不小于 2 的 2 的幂")
  require(pteSizeBytes == 4 || pteSizeBytes == 8, "PTE 大小只支持 4/8 字节")
  // RV64 标准 Sv39/Sv48 必须使用 64 位 PTE 字段配置；32 位自定义几何
  // 仍允许 8B PTE 配默认 32 位字段模板（兼容既有测试与实验 profile）。
  require(XLEN == 32 || pteConfig.pteBits == pteSizeBytes * 8,
    "RV64 PTW 的 PTE 字段配置位宽必须与 PTE 大小一致")
  // 8B PTE 的 A/D 硬件写回走现有 32 位页表写端口，只更新低字。若 A/D
  // 位落在高字，无法正确写回；必须在 elaboration 期拒绝而不是静默丢失。
  require(adMode != "hardware" || pteSizeBytes <= 4 ||
    (pteConfig.aBit < 32 && pteConfig.dBit < 32),
    "64 位 PTE 的 A/D 硬件写回要求 A/D 位位于低 32 位字内")
  private val pteShift = log2Ceil(pteSizeBytes)
  private val pteBytes = pteSizeBytes
  private val pteWidth = pteBytes * 8
  // 标准 PTE 的 PPN 位数：Sv32=22（bit31:10），Sv39/48=44（bit53:10）。
  private val ptePpnBits = pteConfig.ppnHigh - pteConfig.ppnLow + 1
  // 物理地址宽度可能小于 pageBits+PPN 字段宽度（例如 2MiB 页 + 44 位
  // PPN = 65 位）。只能使用能装入 addressWidth 的低位 PPN，高位必须
  // 为 0；walk 时显式检查并报 page fault，不能依赖赋值截断。
  private val paddrPpnBits = math.min(ptePpnBits, addressWidth - pageBits)
  require(paddrPpnBits >= 1, "PPN 字段无法装入物理地址宽度")
  private val satpPpnBits = math.min(44, addressWidth - pageBits)
  require(satpPpnBits >= 1, "satp.PPN 无法装入物理地址宽度")
  // 4B/8B PTE 都可以进入 cache：8B 表项保存低/高两个 32 位字，命中时
  // 不再产生两次页表读。
  private val cacheEffective = cacheEnable
  private val indexBits = log2Ceil(tableEntries)
  require(pageBits + levels * indexBits == vaBits,
    s"pageBits($pageBits) + levels($levels)*indexBits($indexBits) 必须等于 $vaBits")
  // 超级页 PPN 的低 (levels-1)*indexBits 位必须可检查；PPN 字段更窄时
  // 对齐掩码会溢出，必须拒绝而不是静默截断。
  require(!(superpage || fillSuperpage) ||
    ptePpnBits >= (levels - 1) * indexBits,
    s"超级页对齐位(${(levels - 1) * indexBits})超过 PTE PPN 位宽($ptePpnBits)")
  if (cacheEnable) {
    require(cacheEntries > 0 && (cacheEntries & (cacheEntries - 1)) == 0,
      "PTW cache entries 必须是 2 的幂")
    require(cacheWays > 0 && cacheEntries % cacheWays == 0,
      "PTW cache ways 必须整除 entries")
    require((cacheWays & (cacheWays - 1)) == 0, "PTW cache ways 必须是 2 的幂")
  }

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new ysyx_26030103_PTWRequest(XLEN, addressWidth)))
    val resp = Decoupled(new ysyx_26030103_PTWResponse(XLEN, addressWidth, pteWidth))
    val bus = new ysyx_26030103_PTEBusIO(addressWidth)
    val flush = Input(Bool())
  })

  val idle :: reqState :: waitState :: reqHigh :: waitHigh :: writeState :: waitWrite :: done :: Nil = Enum(8)
  val state = RegInit(idle)

  val idReg = RegInit(0.U(4.W))
  val vaddrReg = RegInit(0.U(XLEN.W))
  val satpReg = RegInit(0.U(XLEN.W))
  // HAS_U=n 时 PTW 不需要保存/检查 U 特权级和 SUM；U/S 权限检查固定
  // 不适用，只保留 R/W/X/A/D 检查。
  val privRegOpt = if (hasU) Some(RegInit(0.U(2.W))) else None
  val accessReg = RegInit(0.U(2.W))
  val sumRegOpt = if (hasU) Some(RegInit(false.B)) else None
  val mxrReg = RegInit(false.B)
  val levelReg = RegInit(0.U(ysyx_26030103_MMUProfile.LevelBits.W))
  val tableReg = RegInit(0.U(addressWidth.W))
  val pteAddrReg = RegInit(0.U(addressWidth.W))
  val pteLowReg = RegInit(0.U(32.W))
  val respReg = RegInit(0.U.asTypeOf(new ysyx_26030103_PTWResponse(XLEN, addressWidth, pteWidth)))
  val fillPulse = RegInit(false.B)
  val fillLevelReg = RegInit(0.U(ysyx_26030103_MMUProfile.LevelBits.W))
  val writeDataReg = RegInit(0.U(pteWidth.W))

  class CacheEntry extends Bundle {
    val valid = Bool()
    val tag = UInt(addressWidth.W)
    // 保存原始 PTE（8B PTE 的低/高字），命中时再按 pteConfig 归一化，
    // 这样自定义 PTE 编码和 A/D 写回都不会因缓存二次归一化而失真。
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

  def cacheLookup(addr: UInt): (Bool, UInt) = {
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

  def cacheFill(addr: UInt, data: UInt): Unit = {
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

  when(io.flush) {
    cache.foreach { c => for (i <- 0 until cacheEntries) { c(i).valid := false.B } }
    fillPulse := false.B
  }

  val isFetch = accessReg === ysyx_26030103_AccessType.Fetch
  val isStore = accessReg === ysyx_26030103_AccessType.Store
  def causeOf(access: UInt): UInt = {
    val fetch = access === ysyx_26030103_AccessType.Fetch
    val store = access === ysyx_26030103_AccessType.Store
    Mux(fetch, 12.U, Mux(store, 15.U, 13.U))
  }
  val pageFaultCause = causeOf(accessReg)

  /** 把原始 PTE 归一化为内部标准布局：低 8 位标志，PPN 从 ppnLow 开始。 */
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
  private def adRaw(d: UInt): UInt =
    d | (1.U << pteConfig.aBit) |
      Mux(isStore, 1.U << pteConfig.dBit, 0.U(pteWidth.W))

  // 以下函数接收的是 normalizePte 之后的内部 PTE：标志位固定在低 8 位，
  // 只有 PPN 位置仍由 pteConfig 决定。绝不能再用 pteConfig 的原始标志
  // 位置去解释归一化结果，否则 CUSTOM 的私有 PTE 布局会被误判。
  def isLeaf(d: UInt): Bool =
    Cat(d(2), d(1), d(3)) =/= 0.U // 归一化 W/R/X = bit2/1/3
  def leafAllowed(d: UInt): Bool = superpage.B || levelReg === (levels - 1).U
  /** 非叶子 PTE 的保留位（PPN 高位以外）必须为 0。 */
  def reservedFault(d: UInt): Bool =
    if (pteConfig.ppnHigh + 1 >= pteWidth) false.B
    else d(pteWidth - 1, pteConfig.ppnHigh + 1) =/= 0.U
  /** PPN 字段以上的 PTE 位为保留位，非零必须 page fault。
    *
    * 标准 Sv39/Sv48 的 PPN 到 bit53，因此对应 PTE[63:54]；CUSTOM 允许
    * 重新定义 PPN 高位，保留区必须随 pteConfig 一起移动，不能硬编码 54。
    */
  def rawReservedFault(raw: UInt): Bool =
    if (XLEN <= 32 || pteWidth <= 32 || pteConfig.ppnHigh + 1 >= pteWidth) false.B
    else raw(pteWidth - 1, pteConfig.ppnHigh + 1) =/= 0.U
  /** 超级页 PPN 的低位必须按层级对齐。 */
  def superpageMisaligned(d: UInt): Bool = {
    if (levels <= 1) false.B
    else {
      val allLowerBits = (levels - 1) * indexBits
      val shift = levelReg * indexBits.U
      val mask = ((BigInt(1) << allLowerBits) - 1).U(ptePpnBits.W) >> shift
      (d(pteConfig.ppnHigh, pteConfig.ppnLow) & mask) =/= 0.U
    }
  }
  /** PTE PPN 是否落在物理地址宽度内。
    *
    * pageBits+PPN 超过物理地址宽度时，PPN 字段的高位无法表达；这些位
    * 必须为 0，否则叶子和非叶子 PTE 都会产生 page fault。
    */
  def ppnFits(d: UInt): Bool = {
    if (paddrPpnBits >= ptePpnBits) true.B
    else d(pteConfig.ppnLow + ptePpnBits - 1,
      pteConfig.ppnLow + paddrPpnBits) === 0.U
  }
  /** 去掉不可用的 PPN 高位；调用前必须已检查 ppnFits。 */
  def ppnValue(d: UInt): UInt =
    d(pteConfig.ppnLow + paddrPpnBits - 1, pteConfig.ppnLow)
  def walkFault(d: UInt): Bool =
    !d(0) || !ppnFits(d) || reservedFault(d) || // 归一化 valid = bit0
      (isLeaf(d) && (!leafAllowed(d) || superpageMisaligned(d))) ||
      (!isLeaf(d) && levelReg === (levels - 1).U)
  def leafFaultOf(d: UInt): Bool = {
    // d 是归一化 PTE，标志布局固定：V/R/W/X/U/G/A/D = bit0..7。
    // HAS_U=n 时 U 级不存在，U 位与 SUM 检查固定为不适用；
    // R/W/X、A/D 等其余页表权限仍然正常检查。
    val userFault =
      if (hasU) privRegOpt.get === 0.U && !d(4) else false.B
    val sumFault =
      if (hasU) privRegOpt.get === 1.U && d(4) && (!sumRegOpt.get || isFetch)
      else false.B
    val fetchFault = isFetch && !d(3)
    val readFault = !isFetch && !isStore && !d(1) && !(mxrReg && d(3))
    val writeFault = isStore && !d(2)
    val adFault = adMode match {
      case "fault" => !d(6) || (isStore && !d(7))
      case _       => false.B
    }
    userFault || sumFault || fetchFault || readFault || writeFault || adFault
  }
  def needsAdUpdate(d: UInt): Bool = adMode match {
    case "hardware" => d(0) && (!d(6) || (isStore && !d(7)))
    case _          => false.B
  }
  /** 给 TLB 的填充层级：0=基础页，k=第 k 级超级页。
    *
    * PTW 内部 levelReg 从根(0)递增到叶子；超级页层级按“距离基础页
    * 多少级”编码，TLB 用 pageBits + k*indexBits 计算对应 shift。
    */
  def fillLevelOf(level: UInt): UInt = (levels - 1).U - level
  def adUpdated(d: UInt): UInt = adRaw(d)

  /** 当前级在虚拟地址中的索引；第 0 级是根页表，使用最高 VPN。 */
  def vpnIndex(level: UInt): UInt = {
    MuxLookup(level, vaddrReg(vaBits - 1, pageBits))(
      (0 until levels).map { l =>
        val lo = pageBits + (levels - 1 - l) * indexBits
        l.U -> vaddrReg(lo + indexBits - 1, lo)
      }
    )
  }
  def tableAddress(base: UInt, index: UInt): UInt =
    base + (index << pteShift).pad(addressWidth)

  def superOffsetBits(level: UInt): UInt =
    pageBits.U + ((levels - 1).U - level) * indexBits.U
  def paddrOf(d: UInt, level: UInt): UInt = {
    val ppn = ppnValue(d)
    val shift = superOffsetBits(level)
    val offsetMask = ((BigInt(1) << vaBits) - 1).U(vaBits.W) >> (vaBits.U - shift)
    ((ppn << pageBits).pad(addressWidth) |
      (vaddrReg & offsetMask).pad(addressWidth))
  }

  /** Sv39/Sv48 的高位必须是指示位的符号扩展。 */
  def canonicalOk(vaddr: UInt): Bool = {
    if (vaBits == XLEN) true.B
    else {
      val upper = vaddr(XLEN - 1, vaBits)
      val sign = Fill(XLEN - vaBits, vaddr(vaBits - 1))
      upper === sign
    }
  }
  def satpPpn(satp: UInt): UInt =
    if (XLEN == 32) satp(21, 0) else satp(43, 0)
  def satpAsid(satp: UInt): UInt =
    if (XLEN == 32) satp(30, 22) else satp(59, 44)
  /** satp.PPN 的高位必须能装入物理地址宽度。 */
  def satpRootFits(satp: UInt): Bool = {
    val ppn = satpPpn(satp)
    if (satpPpnBits >= (if (XLEN == 32) 22 else 44)) true.B
    else ppn((if (XLEN == 32) 21 else 43), satpPpnBits) === 0.U
  }
  def tableBaseOf(satp: UInt): UInt =
    (satpPpn(satp)(satpPpnBits - 1, 0) << pageBits).pad(addressWidth)

  val nextPteAddr = tableAddress(tableReg, vpnIndex(levelReg))
  val (cacheHit, cacheData) = cacheLookup(nextPteAddr)
  val useBus = state === reqState && !cacheHit
  io.req.ready := state === idle
  io.bus.req.valid := useBus || state === reqHigh || state === writeState
  // 单 walker 同时只有一笔页表事务；ID 在簇内唯一，用于响应回收。
  io.bus.req.bits.id := busId.U(4.W)
  io.bus.req.bits.addr := Mux(state === writeState, pteAddrReg,
    Mux(state === reqState, nextPteAddr,
      Mux(state === reqHigh, pteAddrReg + 4.U, pteAddrReg)))
  io.bus.req.bits.write := state === writeState
  io.bus.req.bits.data := Mux(state === writeState, writeDataReg(31, 0), 0.U)
  io.bus.req.bits.strb := Mux(state === writeState, "hf".U(4.W), 0.U)
  io.bus.resp.ready := state === waitState || state === waitHigh || state === waitWrite
  io.resp.valid := state === done
  io.resp.bits := respReg

  def setFault(): Unit = {
    respReg.paddr := 0.U
    respReg.fault := true.B
    respReg.cause := pageFaultCause
    respReg.tval := vaddrReg
    respReg.fillValid := false.B
    respReg.fillPte := 0.U
    respReg.fillVaddr := 0.U
    respReg.fillAsid := 0.U
    fillLevelReg := 0.U
    fillPulse := false.B
    state := done
  }

  def finishLeaf(d: UInt, fault: Bool): Unit = {
    respReg.paddr := paddrOf(d, levelReg)
    respReg.fault := fault
    respReg.cause := pageFaultCause
    respReg.tval := vaddrReg
    respReg.fillPte := d
    respReg.fillVaddr := vaddrReg
    respReg.fillAsid := satpAsid(satpReg)
    fillLevelReg := fillLevelOf(levelReg)
    respReg.fillValid := false.B
    fillPulse := !fault &&
      (fillSuperpage.B || levelReg === (levels - 1).U)
    state := done
  }

  def processPte(d: UInt, raw: UInt, busFault: Bool): Unit = {
    // cache 保存原始 PTE；4B 时 raw 就是 32 位字，8B 时是低/高字拼接。
    when(cacheEffective.B) { cacheFill(pteAddrReg, raw) }
    when(busFault || rawReservedFault(raw) || walkFault(d)) {
      setFault()
    }.elsewhen(isLeaf(d)) {
      when(needsAdUpdate(d)) {
        writeDataReg := adRaw(raw)
        state := writeState
      }.otherwise {
        finishLeaf(d, leafFaultOf(d))
      }
    }.otherwise {
      tableReg := (ppnValue(d) << pageBits).pad(addressWidth)
      levelReg := levelReg + 1.U
      state := reqState
    }
  }

  when(state === idle && io.req.fire) {
    idReg := io.req.bits.id
    vaddrReg := io.req.bits.vaddr
    satpReg := io.req.bits.satp
    privRegOpt.foreach { r => r := io.req.bits.privilege }
    accessReg := io.req.bits.access
    sumRegOpt.foreach { r => r := io.req.bits.sum }
    mxrReg := io.req.bits.mxr
    levelReg := 0.U
    tableReg := tableBaseOf(io.req.bits.satp)
    pteAddrReg := 0.U
    when(!canonicalOk(io.req.bits.vaddr) || !satpRootFits(io.req.bits.satp)) {
      // 非规范虚拟地址或无法装入物理地址的 satp.PPN 在查页表前就产生
      // page fault，避免高位别名或根表地址截断。
      respReg := 0.U.asTypeOf(new ysyx_26030103_PTWResponse(XLEN, addressWidth, pteWidth))
      respReg.id := io.req.bits.id
      respReg.paddr := 0.U
      respReg.fault := true.B
      respReg.cause := causeOf(io.req.bits.access)
      respReg.tval := io.req.bits.vaddr
      fillPulse := false.B
      state := done
    }.otherwise {
      state := reqState
    }
  }

  switch(state) {
    is(reqState) {
      // 计算当前级页表项地址。
      pteAddrReg := nextPteAddr
      when(cacheHit) {
        // cache 中保存的是原始 PTE，命中路径与总线读路径一样先归一化。
        val cachePte = normalizePte(cacheData)
        when(rawReservedFault(cacheData) || walkFault(cachePte)) {
          setFault()
        }.elsewhen(isLeaf(cachePte)) {
          finishLeaf(cachePte, leafFaultOf(cachePte))
        }.otherwise {
          tableReg := (ppnValue(cachePte) << pageBits).pad(addressWidth)
          levelReg := levelReg + 1.U
          state := reqState
        }
      }.elsewhen(io.bus.req.ready) {
        state := waitState
      }
    }
    is(waitState) {
      when(io.bus.resp.valid) {
        when(io.bus.resp.bits.fault) {
          setFault()
        }.otherwise {
          if (pteBytes == 8) {
            pteLowReg := io.bus.resp.bits.data
            state := reqHigh
          } else {
            processPte(normalizePte(io.bus.resp.bits.data),
              io.bus.resp.bits.data, false.B)
          }
        }
      }
    }
    is(reqHigh) {
      when(io.bus.req.ready) { state := waitHigh }
    }
    is(waitHigh) {
      when(io.bus.resp.valid) {
        val raw = Cat(io.bus.resp.bits.data, pteLowReg)
        processPte(normalizePte(raw), raw, io.bus.resp.bits.fault)
      }
    }
    is(writeState) {
      when(io.bus.req.ready) { state := waitWrite }
    }
    is(waitWrite) {
      when(io.bus.resp.valid) {
        val written = normalizePte(writeDataReg)
        // A/D 写回只更新低 32 位；cache 保存完整 PTE，8B 时高字保留原值。
        cacheFill(pteAddrReg, writeDataReg)
        respReg.paddr := paddrOf(written, levelReg)
        respReg.fault := io.bus.resp.bits.fault || leafFaultOf(written)
        respReg.cause := pageFaultCause
        respReg.tval := vaddrReg
        respReg.fillPte := written
        respReg.fillVaddr := vaddrReg
        respReg.fillAsid := satpAsid(satpReg)
        fillLevelReg := fillLevelOf(levelReg)
        respReg.fillValid := false.B
        fillPulse := !leafFaultOf(written) && !io.bus.resp.bits.fault &&
          (fillSuperpage.B || levelReg === (levels - 1).U)
        state := done
      }
    }
    is(done) {
      fillPulse := false.B
      when(io.resp.ready) { state := idle }
    }
  }

  io.resp.bits.fillValid := fillPulse
  io.resp.bits.fillLevel := fillLevelReg
  io.resp.bits.id := idReg
}
