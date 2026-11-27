package ysyx_26030103.mmu
import _root_.ysyx_26030103.common.ysyx_26030103_XLENRequire
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_PTEConfig
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile

class ysyx_26030103_PTWRequest(
    val XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    val AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth
) extends Bundle {
  ysyx_26030103_XLENRequire(XLEN, "PTWRequest")
  require(AddressWidth == 32 || AddressWidth == 64,
    s"PTWRequest AddressWidth 非法: $AddressWidth")
  val ID = UInt(4.W)
  val vaddr = UInt(XLEN.W)
  val satp = UInt(XLEN.W)
  val privilege = UInt(2.W)
  val access = UInt(2.W)
  val sum = Bool()
  val mxr = Bool()
}

class ysyx_26030103_PTWResponse(
    val XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    val AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    val PTEWidth: Int = ysyx_26030103_KconfigDefaults.MMUPTEWidth
) extends Bundle {
  ysyx_26030103_XLENRequire(XLEN, "PTWResponse")
  require(AddressWidth == 32 || AddressWidth == 64,
    s"PTWResponse AddressWidth 非法: $AddressWidth")
  require(PTEWidth == 32 || PTEWidth == 64,
    s"PTWResponse PTEWidth 非法: $PTEWidth")
  val ID = UInt(4.W)
  val paddr = UInt(AddressWidth.W)
  val fault = Bool()
  val cause = UInt(32.W)
  val tval = UInt(XLEN.W)
  val FillValid = Bool()
  val FillPTE = UInt(PTEWidth.W)
  val FillVirtualAddress = UInt(XLEN.W)
  val FillASID = UInt(16.W)
  // 0=基础页，k=第 k 级超级页；TLB 用 pageBits + k*indexBits 推导 shift。
  // 3 位统一编码支持最多 8 级页表，不再把 CUSTOM 限制在 4 级。
  val FillLevel = UInt(ysyx_26030103_MMUProfile.LevelBits.W)
  // 64KiB Svnapot 页
  val FillNapot = Bool()
}

// PTW 内部响应荷载；`ID`/`fillLevel` 由独立寄存器在输出级拼接。
private[mmu] class ysyx_26030103_PTWResponsePayload(
    val XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    val AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    val PTEWidth: Int = ysyx_26030103_KconfigDefaults.MMUPTEWidth
) extends Bundle {
  ysyx_26030103_XLENRequire(XLEN, "PTWRespPayload")
  require(AddressWidth == 32 || AddressWidth == 64,
    s"PTWRespPayload AddressWidth 非法: $AddressWidth")
  require(PTEWidth == 32 || PTEWidth == 64,
    s"PTWRespPayload PTEWidth 非法: $PTEWidth")
  val paddr = UInt(AddressWidth.W)
  val fault = Bool()
  val cause = UInt(32.W)
  val tval = UInt(XLEN.W)
  val FillValid = Bool()
  val FillPTE = UInt(PTEWidth.W)
  val FillVirtualAddress = UInt(XLEN.W)
  val FillASID = UInt(16.W)
}

// 可配置页大小/级数/页表项数的 page-table walker，RV32/Sv32 与
// RV64/Sv39/Sv48 共用同一套逐级 walk 状态机：
//   - vaBits = pageBits + levels * indexBits，PTE 宽度由 PTESizeBytes 决定；
//   - satp 的 MODE/ASID/PPN 位置按 XLEN 选择；Sv39/Sv48 的 64 位 PTE 用
//     bit 53:10 的 44 位 PPN，A/D 写回只改包含标志位的低 32 位字。
class ysyx_26030103_PTW(
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
    hasU: Boolean = true,
    // 架构宽度与物理地址宽度；默认 32/32 保持既有 Sv32 行为。
    XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    addressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    // 有效虚拟地址位数：Sv32=32，Sv39=39，Sv48=48。
    vaBits: Int = ysyx_26030103_KconfigDefaults.MMUVaBits,
    // 该 walker 在页表总线上的固定事务 ID；多 walker 簇用 walker 编号，
    // 单 walker/直连测试保持 0。
    BusID: Int = 0
) extends Module {
  require(Set("fault", "ignore", "hardware").contains(adMode), "未知 PTW A/D 模式")
  require(BusID >= 0 && BusID <= 15, "PTW busId 必须在 0..15")
  ysyx_26030103_XLENRequire(XLEN, "PTW")
  require(addressWidth == 32 || addressWidth == 64,
    s"PTW addressWidth 非法: $addressWidth")
  require(vaBits >= 2 && vaBits <= XLEN, s"PTW vaBits 非法: $vaBits")
  require(pageBits >= 2 && pageBits < vaBits, "pageBits 非法")
  require(levels >= 1 && levels <= ysyx_26030103_MMUProfile.MaxLevels,
    s"页表级数必须在 1..${ysyx_26030103_MMUProfile.MaxLevels}" +
      s"（fillLevel 为 ${ysyx_26030103_MMUProfile.LevelBits} 位）")
  require(tableEntries >= 2 && (tableEntries & (tableEntries - 1)) == 0,
    "每级页表项数必须是不小于 2 的 2 的幂")
  require(PTESizeBytes == 4 || PTESizeBytes == 8, "PTE 大小只支持 4/8 字节")
  // RV64 标准 Sv39/Sv48 必须使用 64 位 PTE 字段配置；32 位自定义几何
  // 仍允许 8B PTE 配默认 32 位字段模板（兼容既有测试与实验 profile）。
  require(XLEN == 32 || PTEConfig.PTEBits == PTESizeBytes * 8,
    "RV64 PTW 的 PTE 字段配置位宽必须与 PTE 大小一致")
  // 8B PTE 的 A/D 硬件写回走现有 32 位页表写端口，只更新低字。若 A/D
  // 位落在高字，无法正确写回；必须在 elaboration 期拒绝而不是静默丢失。
  require(adMode != "hardware" || PTESizeBytes <= 4 ||
    (PTEConfig.aBit < 32 && PTEConfig.dBit < 32),
    "64 位 PTE 的 A/D 硬件写回要求 A/D 位位于低 32 位字内")
  private val PTEShift = log2Ceil(PTESizeBytes)
  private val PTEBytes = PTESizeBytes
  private val PTEWidth = PTEBytes * 8
  // 标准 PTE 的 PPN 位数：Sv32=22（bit31:10），Sv39/48=44（bit53:10）。
  private val PTEPPNBits = PTEConfig.PPNHigh - PTEConfig.PPNLow + 1
  // 物理地址宽度可能小于 pageBits+PPN 字段宽度（例如 2MiB 页 + 44 位
  // PPN = 65 位）。只能使用能装入 addressWidth 的低位 PPN，高位必须
  // 为 0；walk 时显式检查并报 page fault，不能依赖赋值截断。
  private val PaddrPPNBits = math.min(PTEPPNBits, addressWidth - pageBits)
  require(PaddrPPNBits >= 1, "PPN 字段无法装入物理地址宽度")
  private val SatpPPNBits = math.min(44, addressWidth - pageBits)
  require(SatpPPNBits >= 1, "satp.PPN 无法装入物理地址宽度")
  // 4B/8B PTE 都可以进入 cache：8B 表项保存低/高两个 32 位字，命中时
  // 不再产生两次页表读。
  private val cacheEffective = cacheEnable
  private val indexBits = log2Ceil(tableEntries)
  require(pageBits + levels * indexBits == vaBits,
    s"pageBits($pageBits) + levels($levels)*indexBits($indexBits) 必须等于 $vaBits")
  // 超级页 PPN 的低 (levels-1)*indexBits 位必须可检查；PPN 字段更窄时
  // 对齐掩码会溢出，必须拒绝而不是静默截断。
  require(!(superpage || fillSuperpage) ||
    PTEPPNBits >= (levels - 1) * indexBits,
    s"超级页对齐位(${(levels - 1) * indexBits})超过 PTE PPN 位宽($PTEPPNBits)")
  if (cacheEnable) {
    require(cacheEntries > 0 && (cacheEntries & (cacheEntries - 1)) == 0,
      "PTW cache entries 必须是 2 的幂")
    require(cacheWays > 0 && cacheEntries % cacheWays == 0,
      "PTW cache ways 必须整除 entries")
    require((cacheWays & (cacheWays - 1)) == 0, "PTW cache ways 必须是 2 的幂")
  }

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new ysyx_26030103_PTWRequest(XLEN, addressWidth)))
    val response = Decoupled(new ysyx_26030103_PTWResponse(XLEN, addressWidth, PTEWidth))
    val bus = new ysyx_26030103_PTEBusIO(addressWidth)
    val flush = Input(Bool())
  })

  val idle :: requestState :: waitState :: reqHigh :: waitHigh :: writeState :: waitWrite :: done :: Nil = Enum(8)
  val state = RegInit(idle)
  // flush 时如果已有页表事务在途，必须继续把它收完（否则总线上会留下
  // 无人接收的响应，且后续 walk 可能误认领旧响应），但绝不能再让旧 walk
  // 的 PTE 回填 cache 或向上层返回结果。discard 标记这段收尾过程。
  val discard = RegInit(false.B)

  val IDReg = RegInit(0.U(4.W))
  val virtualAddressReg = RegInit(0.U(XLEN.W))
  val satpReg = RegInit(0.U(XLEN.W))
  // HAS_U=n 时 PTW 不需要保存/检查 U 特权级和 SUM；U/S 权限检查固定
  // 不适用，只保留 R/W/X/A/D 检查。
  val privRegOpt = if (hasU) Some(RegInit(0.U(2.W))) else None
  val accessReg = RegInit(0.U(2.W))
  val sumRegOpt = if (hasU) Some(RegInit(false.B)) else None
  val mxrReg = RegInit(false.B)
  val levelReg = RegInit(0.U(ysyx_26030103_MMUProfile.LevelBits.W))
  val tableReg = RegInit(0.U(addressWidth.W))
  val PTEAddressReg = RegInit(0.U(addressWidth.W))
  val PTELowReg = RegInit(0.U(32.W))
  // 只保存响应荷载；ID/fillLevel 由独立寄存器在输出级拼接，避免 responseReg
  // 里出现永远被覆盖的影子字段。
  private val responseReg = RegInit(
    0.U.asTypeOf(new ysyx_26030103_PTWResponsePayload(XLEN, addressWidth, PTEWidth))
  )
  val fillLevelReg = RegInit(0.U(ysyx_26030103_MMUProfile.LevelBits.W))
  val fillNapotReg = RegInit(false.B)
  val writeDataReg = RegInit(0.U(PTEWidth.W))

  private val cache = new ysyx_26030103_PTECache(
    cacheEffective, cacheEntries, cacheWays, addressWidth, PTEWidth, PTEShift)

  when(io.flush) {
    cache.invalidate()
    when(state =/= idle) { discard := true.B }
  }

  val isFetch = accessReg === ysyx_26030103_AccessType.Fetch
  val isStore = accessReg === ysyx_26030103_AccessType.Store
  def causeOf(access: UInt): UInt =
    ysyx_26030103_PTEDecode.pageFaultCause(access)
  // 页表总线错误不是页错误：取指/load/store 分别报告 1/5/7。
  def accessCauseOf(access: UInt): UInt =
    ysyx_26030103_PTEDecode.accessFaultCause(access)
  val pageFaultCause = causeOf(accessReg)
  val accessFaultCause = accessCauseOf(accessReg)

  // 把原始 PTE 归一化为内部标准布局：低 8 位标志，PPN 从 PPNLow 开始。
  private def normalizePTE(PTE: UInt): UInt =
    ysyx_26030103_PTEDecode.normalizePTE(PTE, PTEConfig, PTEWidth)
  private def adRaw(PTE: UInt): UInt =
    ysyx_26030103_PTEDecode.adRaw(PTE, isStore, PTEConfig, PTEWidth)

  // 以下函数接收的是 normalizePte 之后的内部 PTE：标志位固定在低 8 位，
  // 只有 PPN 位置仍由 PTEConfig 决定。绝不能再用 PTEConfig 的原始标志
  // 位置去解释归一化结果，否则 CUSTOM 的私有 PTE 布局会被误判。
  def isLeaf(PTE: UInt): Bool =
    ysyx_26030103_PTEDecode.isLeaf(PTE)
  // R=0 且 W=1 是保留编码；无论叶子还是非叶子都必须 page fault。
  def reservedEncoding(PTE: UInt): Bool =
    ysyx_26030103_PTEDecode.reservedEncoding(PTE)
  // 非叶子 PTE 的保留位（PPN 高位以外）必须为 0。
  def reservedFault(PTE: UInt): Bool =
    ysyx_26030103_PTEDecode.reservedFault(PTE, PTEConfig, PTEWidth)
  /** PPN 字段以上的 PTE 位为保留位，非零必须 page fault。
    *
    * 标准 Sv39/Sv48 的 PPN 到 bit53，因此对应 PTE[63:54]；CUSTOM 允许
    * 重新定义 PPN 高位，保留区必须随 PTEConfig 一起移动，不能硬编码 54。
    */
  def rawReservedFault(raw: UInt): Bool =
    ysyx_26030103_PTEDecode.rawReservedFault(raw, PTEConfig, XLEN, PTEWidth,
      true, napotLeaf(raw))
  // 64KiB Svnapot：N=1、末级、PPN[3:0]=1000、RWX 至少一位
  def napotLeaf(raw: UInt): Bool = {
    if (XLEN <= 32 || PTEWidth <= 32 || pageBits != 12 ||
        PTEConfig.PPNHigh >= PTEWidth - 1) false.B
    else {
      val flags = Cat(raw(PTEConfig.executeBit), raw(PTEConfig.writeBit),
        raw(PTEConfig.readBit))
      raw(PTEWidth - 1) && levelReg === (levels - 1).U &&
        raw(PTEConfig.PPNLow + 3, PTEConfig.PPNLow) === 8.U &&
        flags =/= 0.U
    }
  }
  // 超级页 PPN 的低位必须按层级对齐。
  def superpageMisaligned(PTE: UInt): Bool =
    ysyx_26030103_PTEDecode.superpageMisaligned(PTE, levelReg, PTEConfig,
      pageBits, levels, vaBits)
  /** PTE PPN 是否落在物理地址宽度内。
    *
    * pageBits+PPN 超过物理地址宽度时，PPN 字段的高位无法表达；这些位
    * 必须为 0，否则叶子和非叶子 PTE 都会产生 page fault。
    */
  def ppnFits(PTE: UInt): Bool =
    ysyx_26030103_PTEDecode.ppnFits(PTE, PTEConfig, pageBits, addressWidth)
  // 去掉不可用的 PPN 高位；调用前必须已检查 ppnFits。
  def ppnValue(PTE: UInt): UInt =
    ysyx_26030103_PTEDecode.ppnValue(PTE, PTEConfig, pageBits, addressWidth)
  def walkFault(PTE: UInt): Bool =
    ysyx_26030103_PTEDecode.walkFault(PTE, levelReg, PTEConfig, PTEWidth,
      pageBits, levels, vaBits, addressWidth, superpage)
  def leafFaultOf(PTE: UInt): Bool = {
    // PTE 是归一化 PTE，标志布局固定：V/R/W/X/U/G/A/D = bit0..7。
    // HAS_U=n 时 U 级不存在，U 位与 SUM 检查固定为不适用；
    // R/W/X、A/D 等其余页表权限仍然正常检查。
    ysyx_26030103_PTEDecode.leafFaultOf(PTE,
      if (hasU) privRegOpt.get else 0.U(2.W), accessReg,
      if (hasU) sumRegOpt.get else false.B, mxrReg, adMode, hasU)
  }
  def needsAdUpdate(PTE: UInt): Bool =
    ysyx_26030103_PTEDecode.needsAdUpdate(PTE, isStore, adMode)
  /** 给 TLB 的填充层级：0=基础页，k=第 k 级超级页。
    *
    * PTW 内部 levelReg 从根(0)递增到叶子；超级页层级按“距离基础页
    * 多少级”编码，TLB 用 pageBits + k*indexBits 计算对应 shift。
    */
  def fillLevelOf(level: UInt): UInt =
    ysyx_26030103_PTEDecode.fillLevelOf(level, levels)

  // 当前级在虚拟地址中的索引；第 0 级是根页表，使用最高 VPN。
  def vpnIndex(level: UInt): UInt =
    ysyx_26030103_PTEDecode.vpnIndex(virtualAddressReg, level, pageBits,
      levels, vaBits)
  def tableAddress(base: UInt, index: UInt): UInt =
    ysyx_26030103_PTEDecode.tableAddress(base, index, addressWidth, PTEWidth)

  def superOffsetBits(level: UInt): UInt =
    ysyx_26030103_PTEDecode.superOffsetBits(level, pageBits, levels, vaBits)
  def paddrOf(PTE: UInt, level: UInt, napot: Bool = false.B): UInt =
    ysyx_26030103_PTEDecode.paddrOf(PTE, level, virtualAddressReg, napot,
      true, PTEConfig, pageBits, levels, vaBits, XLEN, addressWidth)

  // Sv39/Sv48 的高位必须是指示位的符号扩展。
  def canonicalOk(virtualAddress: UInt): Bool =
    ysyx_26030103_PTEDecode.canonicalOk(virtualAddress, vaBits, XLEN)
  def satpPPN(satp: UInt): UInt =
    ysyx_26030103_PTEDecode.satpPPN(satp, XLEN)
  def satpASID(satp: UInt): UInt =
    ysyx_26030103_PTEDecode.satpASID(satp, XLEN)
  // satp.PPN 的高位必须能装入物理地址宽度。
  def satpRootFits(satp: UInt): Bool =
    ysyx_26030103_PTEDecode.satpRootFits(satp, XLEN, pageBits, addressWidth)
  def tableBaseOf(satp: UInt): UInt =
    ysyx_26030103_PTEDecode.tableBaseOf(satp, XLEN, pageBits, addressWidth)

  val NextPTEAddress = tableAddress(tableReg, vpnIndex(levelReg))
  val (cacheHit, cacheData) = cache.lookup(NextPTEAddress)
  val useBus = state === requestState && !cacheHit
  io.request.ready := state === idle && !io.flush && !discard
  // flush/收尾期间不再发起新的页表事务；requestState/reqHigh/writeState 的
  // 推进也必须改用 fire，否则 valid 被 flush 门控掉时仍会误判为已发请求。
  io.bus.request.valid :=
    (useBus || state === reqHigh || state === writeState) &&
      !io.flush && !discard
  // 单 walker 同时只有一笔页表事务；ID 在簇内唯一，用于响应回收。
  io.bus.request.bits.ID := BusID.U(4.W)
  io.bus.request.bits.address := Mux(state === writeState, PTEAddressReg,
    Mux(state === requestState, NextPTEAddress,
      Mux(state === reqHigh, PTEAddressReg + 4.U, PTEAddressReg)))
  io.bus.request.bits.write := state === writeState
  io.bus.request.bits.data := Mux(state === writeState, writeDataReg(31, 0), 0.U)
  io.bus.request.bits.strobe := Mux(state === writeState, "hf".U(4.W), 0.U)
  io.bus.response.ready := state === waitState || state === waitHigh || state === waitWrite
  io.response.valid := state === done && !io.flush && !discard
  io.response.bits.paddr := responseReg.paddr
  io.response.bits.fault := responseReg.fault
  io.response.bits.cause := responseReg.cause
  io.response.bits.tval := responseReg.tval
  io.response.bits.FillValid := responseReg.FillValid
  io.response.bits.FillPTE := responseReg.FillPTE
  io.response.bits.FillVirtualAddress := responseReg.FillVirtualAddress
  io.response.bits.FillASID := responseReg.FillASID
  io.response.bits.FillLevel := fillLevelReg
  io.response.bits.FillNapot := fillNapotReg
  io.response.bits.ID := IDReg

  def setFault(cause: UInt = pageFaultCause): Unit = {
    responseReg.paddr := 0.U
    responseReg.fault := true.B
    responseReg.cause := cause
    responseReg.tval := virtualAddressReg
    responseReg.FillValid := false.B
    responseReg.FillPTE := 0.U
    responseReg.FillVirtualAddress := 0.U
    responseReg.FillASID := 0.U
    fillLevelReg := 0.U
    fillNapotReg := false.B
    state := done
  }

  def finishLeaf(PTE: UInt, fault: Bool, napot: Bool = false.B): Unit = {
    responseReg.paddr := paddrOf(PTE, levelReg, napot)
    responseReg.fault := fault
    responseReg.cause := pageFaultCause
    responseReg.tval := virtualAddressReg
    responseReg.FillPTE := PTE
    responseReg.FillVirtualAddress := virtualAddressReg
    responseReg.FillASID := satpASID(satpReg)
    fillLevelReg := fillLevelOf(levelReg)
    fillNapotReg := napot
    responseReg.FillValid := !fault &&
      (napot || fillSuperpage.B || levelReg === (levels - 1).U)
    state := done
  }

  def processPTE(PTE: UInt, raw: UInt, busFault: Bool): Unit = {
    // cache 保存原始 PTE；4B 时 raw 就是 32 位字，8B 时是低/高字拼接。
    // 总线错误的读数据不是有效 PTE，不能写入 cache。flush 当拍或该 walk
    // 已被 flush 丢弃时也不能回填，否则旧 PTE 会跨 sfence 残留。
    when(cacheEffective.B && !busFault && !io.flush && !discard) {
      cache.fill(PTEAddressReg, raw)
    }
    when(busFault || rawReservedFault(raw) || walkFault(PTE)) {
      // 总线故障是 access fault；保留位/权限/几何错误仍是 page fault。
      setFault(Mux(busFault, accessFaultCause, pageFaultCause))
    }.elsewhen(isLeaf(PTE)) {
      when(needsAdUpdate(PTE)) {
        writeDataReg := adRaw(raw)
        state := writeState
      }.otherwise {
        finishLeaf(PTE, leafFaultOf(PTE), napotLeaf(raw))
      }
    }.otherwise {
      tableReg := (ppnValue(PTE) << pageBits).pad(addressWidth)
      levelReg := levelReg + 1.U
      state := requestState
    }
  }

  when(state === idle && io.request.fire) {
    IDReg := io.request.bits.ID
    virtualAddressReg := io.request.bits.vaddr
    satpReg := io.request.bits.satp
    privRegOpt.foreach { r => r := io.request.bits.privilege }
    accessReg := io.request.bits.access
    sumRegOpt.foreach { r => r := io.request.bits.sum }
    mxrReg := io.request.bits.mxr
    levelReg := 0.U
    tableReg := tableBaseOf(io.request.bits.satp)
    PTEAddressReg := 0.U
    when(!canonicalOk(io.request.bits.vaddr) || !satpRootFits(io.request.bits.satp)) {
      // 非规范虚拟地址或无法装入物理地址的 satp.PPN 在查页表前就产生
      // page fault，避免高位别名或根表地址截断。
      responseReg := 0.U.asTypeOf(
        new ysyx_26030103_PTWResponsePayload(XLEN, addressWidth, PTEWidth)
      )
      responseReg.paddr := 0.U
      responseReg.fault := true.B
      responseReg.cause := causeOf(io.request.bits.access)
      responseReg.tval := io.request.bits.vaddr
        state := done
    }.otherwise {
      state := requestState
    }
  }

  switch(state) {
    is(requestState) {
      // 计算当前级页表项地址。
      PTEAddressReg := NextPTEAddress
      when(discard) {
        state := idle
        discard := false.B
      }.elsewhen(cacheHit) {
        // cache 中保存的是原始 PTE，命中路径与总线读路径一样先归一化。
        val CachePTE = normalizePTE(cacheData)
        when(rawReservedFault(cacheData) || walkFault(CachePTE)) {
          setFault()
        }.elsewhen(isLeaf(CachePTE)) {
          // cache 命中与总线读路径必须执行同一套 A/D 处理：命中项可能
          // 是之前 load 留下的 A=1,D=0，同页 store 仍要把 D 写回页表。
          when(needsAdUpdate(CachePTE)) {
            writeDataReg := adRaw(cacheData)
            state := writeState
          }.otherwise {
            finishLeaf(CachePTE, leafFaultOf(CachePTE), napotLeaf(cacheData))
          }
        }.otherwise {
          tableReg := (ppnValue(CachePTE) << pageBits).pad(addressWidth)
          levelReg := levelReg + 1.U
          state := requestState
        }
      }.elsewhen(io.bus.request.fire) {
        state := waitState
      }
    }
    is(waitState) {
      when(io.bus.response.valid) {
        when(discard) {
          // 旧 walk 的响应只用于排空总线，不再解析/缓存。
          state := idle
          discard := false.B
        }.elsewhen(io.bus.response.bits.fault) {
          setFault(accessFaultCause)
        }.otherwise {
          if (PTEBytes == 8) {
            PTELowReg := io.bus.response.bits.data
            state := reqHigh
          } else {
            processPTE(normalizePTE(io.bus.response.bits.data),
              io.bus.response.bits.data, false.B)
          }
        }
      }
    }
    is(reqHigh) {
      when(discard) {
        state := idle
        discard := false.B
      }.elsewhen(io.bus.request.fire) {
        state := waitHigh
      }
    }
    is(waitHigh) {
      when(io.bus.response.valid) {
        when(discard) {
          state := idle
          discard := false.B
        }.otherwise {
          val raw = Cat(io.bus.response.bits.data, PTELowReg)
          processPTE(normalizePTE(raw), raw, io.bus.response.bits.fault)
        }
      }
    }
    is(writeState) {
      when(discard) {
        state := idle
        discard := false.B
      }.elsewhen(io.bus.request.fire) {
        state := waitWrite
      }
    }
    is(waitWrite) {
      when(io.bus.response.valid) {
        when(discard) {
          state := idle
          discard := false.B
        }.otherwise {
          val written = normalizePTE(writeDataReg)
          // A/D 写回只更新低 32 位；cache 保存完整 PTE，8B 时高字保留原值。
          // 写总线出错时不更新 cache，避免缓存未成功写回的表项。
          when(cacheEffective.B && !io.bus.response.bits.fault && !io.flush &&
              !discard) {
            cache.fill(PTEAddressReg, writeDataReg)
          }
          responseReg.paddr := paddrOf(written, levelReg)
          responseReg.fault := io.bus.response.bits.fault || leafFaultOf(written)
          responseReg.cause := Mux(io.bus.response.bits.fault,
            accessFaultCause, pageFaultCause)
          responseReg.tval := virtualAddressReg
          responseReg.FillPTE := written
          responseReg.FillVirtualAddress := virtualAddressReg
          responseReg.FillASID := satpASID(satpReg)
          fillLevelReg := fillLevelOf(levelReg)
          fillNapotReg := false.B
          responseReg.FillValid := !leafFaultOf(written) &&
            !io.bus.response.bits.fault &&
            (fillSuperpage.B || levelReg === (levels - 1).U)
          state := done
        }
      }
    }
    is(done) {
      when(io.response.ready || io.flush || discard) {
        state := idle
        discard := false.B
      }
    }
    is(idle) {
      discard := false.B
    }
  }
}
