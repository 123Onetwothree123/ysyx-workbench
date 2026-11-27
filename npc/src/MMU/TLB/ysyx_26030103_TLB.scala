package ysyx_26030103.mmu
import _root_.ysyx_26030103.common.ysyx_26030103_XLENRequire
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import _root_.ysyx_26030103.common.ysyx_26030103_RISCVFields

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile

class ysyx_26030103_TLBWrite(
    val XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    val PTEWidth: Int = ysyx_26030103_KconfigDefaults.MMUPTEWidth
) extends Bundle {
  ysyx_26030103_XLENRequire(XLEN, "TLBWrite")
  require(PTEWidth == 32 || PTEWidth == 64, s"TLBWrite PTEWidth 非法: $PTEWidth")
  val valid = Bool()
  val VirtualAddress = UInt(XLEN.W)
  val ASID = UInt(16.W)
  val PTE = UInt(PTEWidth.W)
  // fillLevel 使用统一 3 位编码，支持最多 8 级页表。
  val level = UInt(ysyx_26030103_MMUProfile.LevelBits.W)
  val napot = Bool()
}

// 组相联 TLB，支持 Sv32/Sv39/Sv48 和 1..2 个同拍查找端口。split 模式下
// I/D TLB 各一份且只用 1 个端口；unified 模式 lookupPorts=2 可同拍并行查找。
// 寄存器阵列组合查找，SRAM 数据阵列每端口独立同步读并给出 resultValid；
// refill 端口独立。RV64 下 VPN 只用 vaBits-1:pageBits，PPN 为 bit53:10。
class ysyx_26030103_TLB(
    entries: Int = ysyx_26030103_KconfigDefaults.MMUItlbEntries,
    ways: Int = ysyx_26030103_KconfigDefaults.MMUItlbWays,
    ASIDBits: Int = ysyx_26030103_KconfigDefaults.MMUTLBASIDBits,
    globalEnable: Boolean = true,
    replacement: String = "rr",
    adMode: String = "fault",
    storage: String = "reg",
    superpage: Boolean = false,
    pageBits: Int = ysyx_26030103_KconfigDefaults.MMUPageBits,
    levels: Int = ysyx_26030103_KconfigDefaults.MMULevels,
    indexBits: Int = ysyx_26030103_KconfigDefaults.MMUIndexBits,
    refillPorts: Int = ysyx_26030103_KconfigDefaults.MMURefillPorts,
    superpageEntries: Int = ysyx_26030103_KconfigDefaults.MMUTLBSuperpageEntries,
    hasU: Boolean = true,
    lookupPorts: Int = ysyx_26030103_KconfigDefaults.MMUUtlbLookupPorts,
    XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    addressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    vaBits: Int = ysyx_26030103_KconfigDefaults.MMUVaBits,
    PTEWidth: Int = ysyx_26030103_KconfigDefaults.MMUPTEWidth,
    PTEPPNLow: Int = ysyx_26030103_KconfigDefaults.MMUPTEPPNLow,
    PTEPPNHigh: Int = ysyx_26030103_KconfigDefaults.MMUPTEPPNHigh
) extends Module {
  require(entries > 0 && ways > 0 && entries % ways == 0, "TLB entries 必须能被 ways 整除")
  require((entries & (entries - 1)) == 0, "TLB entries 必须是 2 的幂")
  require(ASIDBits >= 0 && ASIDBits <= 16, "ASID 位数必须在 0..16")
  require((ways & (ways - 1)) == 0, "TLB ways 必须是 2 的幂")
  require(Set("reg", "sram").contains(storage), "TLB storage 必须是 reg/sram")
  require(lookupPorts >= 1 && lookupPorts <= 2, "TLB lookupPorts 必须在 1..2")
  require(levels >= 1 && levels <= ysyx_26030103_MMUProfile.MaxLevels,
    s"TLB levels 必须在 1..${ysyx_26030103_MMUProfile.MaxLevels}")
  require(indexBits >= 1 && indexBits <= 30, "TLB indexBits 非法")
  ysyx_26030103_XLENRequire(XLEN, "TLB")
  require(addressWidth == 32 || addressWidth == 64,
    s"TLB addressWidth 非法: $addressWidth")
  require(vaBits > pageBits && vaBits <= XLEN, "TLB vaBits 非法")
  require(PTEWidth == 32 || PTEWidth == 64, "TLB PTEWidth 非法")
  require(PTEPPNLow >= 8 && PTEPPNHigh < PTEWidth && PTEPPNLow <= PTEPPNHigh,
    "TLB PTE PPN 位置非法")
  // 超级页的 shift/tag 由 pageBits/levels/indexBits 推导；非标准几何
  // 必须满足有效虚拟地址可完整划分，否则拒绝 elaboration 而不是静默错映射。
  require(!superpage || pageBits + levels * indexBits == vaBits,
    s"TLB 超级页要求 pageBits($pageBits) + levels($levels)*indexBits($indexBits) 等于 vaBits($vaBits)")

  private val sets = entries / ways
  private val setBits = if (sets == 1) 0 else log2Ceil(sets)
  private val ASIDWidth = ASIDBits.max(1)
  private val VPNBits = vaBits - pageBits
  // PTE 中可用的 PPN 位数（Sv32=22，Sv39/Sv48=44），再受物理地址宽度约束。
  private val PTEPPNBits = PTEPPNHigh - PTEPPNLow + 1
  private val PPNBits = math.min(PTEPPNBits, addressWidth - pageBits)
  require(PPNBits >= 1, "TLB 的 PPN 字段无法装入物理地址宽度")
  private val entryBits = log2Ceil(entries)

  class Entry extends Bundle {
    val valid = Bool()
    val tag = UInt((VPNBits - setBits).W)
    val ASID = UInt(ASIDWidth.W)
    val global = Bool()
    val PPN = UInt(PPNBits.W)
    val r = Bool()
    val w = Bool()
    val x = Bool()
    // HAS_U=n 时 U 位没有语义，连表项存储都省略。
    val u = if (hasU) Some(Bool()) else None
    val a = Bool()
    val d = Bool()
  }

  class Lookup extends Bundle {
    val vaddr = UInt(XLEN.W)
    val ASID = UInt(ASIDWidth.W)
    val privilege = UInt(2.W)
    val access = UInt(2.W)
    val sum = Bool()
    val mxr = Bool()
  }

  class Result extends Bundle {
    val hit = Bool()
    val fault = Bool()
    val paddr = UInt(addressWidth.W)
    val cause = UInt(32.W)
    val resultValid = Bool()
    val superpage = Bool()
    val ASIDHit = Bool()
  }

  val io = IO(new Bundle {
    // 端口 0 始终存在；lookupPorts=2 时端口 1 用于 unified TLB 的第二个上下文。
    val lookup = Input(Vec(lookupPorts, new Lookup))
    val result = Output(Vec(lookupPorts, new Result))
    val FillValid = Input(Bool())
    val FillVirtualAddress = Input(UInt(XLEN.W))
    val FillASID = Input(UInt(16.W))
    val FillPTE = Input(UInt(PTEWidth.W))
    val FillLevel = Input(UInt(ysyx_26030103_MMUProfile.LevelBits.W))
    val FillNapot = Input(Bool())
    // 第 2..refillPorts 个写回端口；端口 0 保持上面的兼容接口。
    val FillExtra = if (refillPorts > 1)
      Some(Input(Vec(refillPorts - 1,
        new ysyx_26030103_TLBWrite(XLEN, PTEWidth)))) else None
    val flush = Input(Bool())
  })

  val entriesVec = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new Entry))))
  val roundRobin = RegInit(0.U(log2Ceil(ways.max(2)).W))
  // 超级页表项不再硬编码 4MiB/vpn1。每个表项保存填充层级、完整虚拟
  // 地址和物理基址；比较时按该层级对应的 shift 对齐。fillLevel 约定：
  // 0=基础页，k=第 k 级超级页，shift = pageBits + k*indexBits。
  require(superpageEntries >= 1 && superpageEntries <= 16,
    "TLB superpageEntries 必须在 1..16")
  // RV64 需要超级页 CAM 才能缓存 64KiB Svnapot 页
  private val superEntryCount =
    if (levels > 1 && (superpage || XLEN == 64)) superpageEntries else 0
  class SuperEntry extends Bundle {
    val valid = Bool()
    val level = UInt(ysyx_26030103_MMUProfile.LevelBits.W)
    val napot = Bool()
    val vaddr = UInt(XLEN.W)
    val ASID = UInt(ASIDWidth.W)
    val global = Bool()
    val paddrBase = UInt(addressWidth.W)
    val r = Bool(); val w = Bool(); val x = Bool()
    val u = if (hasU) Some(Bool()) else None
    val a = Bool(); val d = Bool()
  }
  class SelectedFlags extends Bundle {
    val r = Bool()
    val w = Bool()
    val x = Bool()
    val u = Bool()
    val a = Bool()
    val d = Bool()
    val ppn = UInt(PPNBits.W)
  }
  class PteFlags extends Bundle {
    val r = Bool()
    val w = Bool()
    val x = Bool()
    val u = Bool()
    val g = Bool()
    val a = Bool()
    val d = Bool()
    val ppn = UInt(PTEPPNBits.W)
  }
  private val shiftBits = log2Ceil(XLEN.max(32))
  private val maxShift = pageBits + (levels - 1) * indexBits
  // 填充层级对应的地址 shift；level 超出配置范围时返回最大 shift。
  private def shiftOfLevel(level: UInt): UInt =
    MuxLookup(level, maxShift.U(shiftBits.W))(
      (0 until levels).map(l => l.U -> (pageBits + l * indexBits).U(shiftBits.W))
    )
  // 超级页偏移掩码：低 shift 位为 1。
  private def offsetMaskOf(shift: UInt): UInt =
    (~0.U(XLEN.W)) >> (XLEN.U - shift)
  private val NapotShift: Int = pageBits + 4
  private def superShiftOf(entryNapot: Bool, level: UInt): UInt =
    Mux(entryNapot, NapotShift.U(shiftBits.W), shiftOfLevel(level))
  // 判断虚拟地址是否落在同一超级页/NATOP 页。
  private def superTagMatch(shift: UInt, entryVirtualAddress: UInt, lookupVirtualAddress: UInt): Bool = {
    val mask = ~offsetMaskOf(shift)
    (entryVirtualAddress & mask) === (lookupVirtualAddress & mask)
  }
  val superVec = if (superEntryCount > 0)
    Some(RegInit(VecInit(Seq.fill(superEntryCount)(0.U.asTypeOf(new SuperEntry)))))
  else None
  val superRoundRobin = if (superEntryCount > 0)
    Some(RegInit(0.U(log2Ceil(superEntryCount.max(2)).W))) else None
  private val sramStorage = storage == "sram"
  val PTEMems =
    if (sramStorage) Some((0 until ways).map(_ => SyncReadMem(sets, UInt(PTEWidth.W))))
    else None
  // SRAM 读出一拍后才有效；每个查找端口分别记录上一拍地址。
  val lastLookupVirtualAddress =
    RegInit(VecInit(Seq.fill(lookupPorts)(0.U(XLEN.W))))
  // tree-PLRU：ways<=8 时使用 heap 索引的 way 选择树；RR 模式下不生成。
  private val plruLevels = if (ways > 1) log2Ceil(ways) else 0
  val plruOpt =
    if (replacement == "plru" && ways > 1)
      Some(RegInit(VecInit(Seq.fill(sets * (ways - 1))(false.B))))
    else None
  private val plruStride = (ways - 1).U
  private val plruVecBits = if (ways > 1) log2Ceil(sets * (ways - 1)) else 1
  private def plruIndex(set: UInt, node: UInt): UInt =
    (set * plruStride + node)(plruVecBits - 1, 0)
  private val lruBits = log2Ceil(ways.max(2))
  private val lruMax = (ways - 1).U(lruBits.W)
  val lruOpt =
    if (replacement == "lru")
      Some(RegInit(VecInit(Seq.fill(entries)(0.U(lruBits.W)))))
    else None
  def lruVictim(set: UInt): UInt = {
    val ages = VecInit((0 until ways).map(w => lruOpt.get(entryIndex(set, w.U))))
    var victim: UInt = 0.U(log2Ceil(ways.max(2)).W)
    for (w <- 1 until ways) {
      victim = Mux(ages(w) > ages(victim), w.U, victim)
    }
    victim
  }
  def lruUpdate(set: UInt, way: UInt): Unit = {
    if (replacement == "lru") {
      val ages = lruOpt.get
      for (w <- 0 until ways) {
        val index = entryIndex(set, w.U)
        when(way === w.U) { ages(index) := 0.U }
          .elsewhen(ages(index) =/= lruMax) { ages(index) := ages(index) + 1.U }
      }
    }
  }
  def plruVictim(set: UInt): UInt = {
    def rec(node: Int, depth: Int): UInt =
      if (depth == plruLevels) (node - (ways - 1)).U
      else {
        val bit = plruOpt.get(plruIndex(set, node.U))
        Mux(bit, rec(2 * node + 2, depth + 1), rec(2 * node + 1, depth + 1))
      }
    rec(0, 0)
  }
  def plruUpdate(set: UInt, way: UInt): Unit = {
    if (replacement == "plru" && ways > 1) {
      val tree = plruOpt.get
      var nodeExpr: UInt = 0.U
      for (depth <- 0 until plruLevels) {
        val bit = way(plruLevels - 1 - depth)
        tree(plruIndex(set, nodeExpr)) := !bit
        nodeExpr = (nodeExpr << 1) + 1.U + bit
      }
    }
  }

  private def vpnOf(address: UInt): UInt = address(vaBits - 1, pageBits)
  private def setOf(VPN: UInt): UInt =
    if (setBits == 0) 0.U else VPN(setBits - 1, 0)
  private def tagOf(VPN: UInt): UInt =
    if (setBits == 0) VPN else VPN(VPNBits - 1, setBits)
  private def entryIndex(set: UInt, way: UInt): UInt =
    if (entryBits == 0) 0.U else (set * ways.U + way)(entryBits - 1, 0)
  private def ptePPNOf(PTE: UInt): UInt =
    PTE(PTEPPNLow + PTEPPNBits - 1, PTEPPNLow)
  private def UnpackPte(pte: UInt): PteFlags = {
    val flags = Wire(new PteFlags)
    flags.r := pte(1)
    flags.w := pte(2)
    flags.x := pte(3)
    flags.u := pte(4)
    flags.g := pte(5)
    flags.a := pte(6)
    flags.d := pte(7)
    flags.ppn := ptePPNOf(pte)
    flags
  }

  // 每个查找端口独立做 tag/ASID/权限检查。refill 在本拍末尾写入寄存器，
  // 因而不影响本拍已经组合出来的旧表项结果。
  for (port <- 0 until lookupPorts) {
    val lookup = io.lookup(port)
    val LookupVPN = vpnOf(lookup.vaddr)
    val lookupSet = setOf(LookupVPN)
    val lookupTag = tagOf(LookupVPN)
    val sramPTEs = PTEMems
      .map(_.map(m => m.read(lookupSet)))
      .getOrElse(Seq.fill(ways)(0.U(PTEWidth.W)))
    val lookupWays = (0 until ways).map { w =>
      val entry = entriesVec(entryIndex(lookupSet, w.U))
      val ASIDMatch = if (ASIDBits == 0) true.B else entry.ASID === lookup.ASID
      val globalMatch = if (globalEnable) entry.global else false.B
      entry.valid && entry.tag === lookupTag && (globalMatch || ASIDMatch)
    }
    val lookupHit = lookupWays.reduce(_ || _)
    val hitWay = Mux1H(lookupWays, (0 until ways).map(w => w.U(log2Ceil(ways.max(2)).W)))
    val selected = Mux1H(lookupWays, (0 until ways).map(w => entriesVec(entryIndex(lookupSet, w.U))))
    val SelectedPTE = Mux1H(lookupWays, sramPTEs)
    val superHits = if (superEntryCount > 0) {
      (0 until superEntryCount).map { i =>
        val entry = superVec.get(i)
        val ASIDMatch = if (ASIDBits == 0) true.B else entry.ASID === lookup.ASID
        val globalMatch = if (globalEnable) entry.global else false.B
        entry.valid && (entry.level =/= 0.U || entry.napot) &&
          superTagMatch(superShiftOf(entry.napot, entry.level),
            entry.vaddr, lookup.vaddr) &&
          (globalMatch || ASIDMatch)
      }
    } else Seq.empty
    val superHit = if (superEntryCount > 0) superHits.reduce(_ || _) else false.B
    val superSelected = if (superEntryCount > 0)
      Mux1H(superHits, (0 until superEntryCount).map(i => superVec.get(i)))
    else 0.U.asTypeOf(new SuperEntry)
    val superPhysicalAddress = if (superEntryCount > 0) {
      val paddrs = (0 until superEntryCount).map { i =>
        val entry = superVec.get(i)
        entry.paddrBase |
          (lookup.vaddr & offsetMaskOf(superShiftOf(entry.napot, entry.level)))
      }
      Mux1H(superHits, paddrs)
    } else 0.U(addressWidth.W)
    val normalSelected = Wire(new SelectedFlags)
    if (sramStorage) {
      val sramFlags = UnpackPte(SelectedPTE)
      normalSelected.r := sramFlags.r
      normalSelected.w := sramFlags.w
      normalSelected.x := sramFlags.x
      normalSelected.u := (if (hasU) sramFlags.u else false.B)
      normalSelected.a := sramFlags.a
      normalSelected.d := sramFlags.d
      normalSelected.ppn := sramFlags.ppn(PPNBits - 1, 0)
    } else {
      normalSelected.r := selected.r
      normalSelected.w := selected.w
      normalSelected.x := selected.x
      normalSelected.u := (if (hasU) selected.u.get else false.B)
      normalSelected.a := selected.a
      normalSelected.d := selected.d
      normalSelected.ppn := selected.PPN
    }
    val selectedFlags = Wire(new SelectedFlags)
    selectedFlags := normalSelected
    when(superHit) {
      selectedFlags.r := superSelected.r
      selectedFlags.w := superSelected.w
      selectedFlags.x := superSelected.x
      selectedFlags.u := (if (hasU) superSelected.u.get else false.B)
      selectedFlags.a := superSelected.a
      selectedFlags.d := superSelected.d
      selectedFlags.ppn := 0.U(PPNBits.W)
    }
    val selectedR = selectedFlags.r
    val selectedW = selectedFlags.w
    val selectedX = selectedFlags.x
    val selectedU = selectedFlags.u
    val selectedA = selectedFlags.a
    val selectedD = selectedFlags.d
    val SelectedPPN = selectedFlags.ppn

    val isFetch = lookup.access === ysyx_26030103_AccessType.Fetch
    val isStore = lookup.access === ysyx_26030103_AccessType.Store
    val privilegeU = if (hasU) lookup.privilege === 0.U else false.B
    val privilegeS = lookup.privilege === 1.U
    // HAS_U=n 时 U 级和 SUM 语义不存在：U 位检查固定为不适用，
    // S 模式可正常访问 U=1 的页，fetch/read/write 权限仍按 R/W/X 检查。
    val fetchOk =
      if (hasU) selectedX && !(privilegeS && selectedU) else selectedX
    val readOk = selectedR || (lookup.mxr && selectedX)
    val writeOk = selectedW
    val userOk = if (hasU) !privilegeU || selectedU else true.B
    val sumOk =
      if (hasU) !(privilegeS && selectedU) || (lookup.sum && !isFetch) else true.B
    val accessOk = Mux(isFetch, fetchOk, Mux(isStore, writeOk, readOk))
    // hardware 模式的 A/D 写回由 PTW 在填表前完成，TLB 只缓存已处理的
    // PTE。若某表项由 load 填入（A=1,D=0），之后的 store 命中不能直接
    // 放行：这里把 A=0 或 store 且 D=0 的命中降级为 miss，交给带 A/D
    // 写端口的 PTW 重新 walk 并写回，再用新 PTE 回填。这样无需给 TLB
    // 增加页表写端口，也不会让 D 永远不更新。fault 模式仍按 A/D 位拒绝。
    val adOk = adMode match {
      case "fault" => selectedA && (!isStore || selectedD)
      case _       => true.B
    }
    // R=0 且 W=1 是保留编码；TLB 中已有的表项也不能绕过该检查。
    val ReservedPTE = !selectedR && selectedW
    val permissionOk = !ReservedPTE && userOk && sumOk && accessOk && adOk
    val faultCause = ysyx_26030103_RISCVFields.PageFaultCause(isFetch, isStore)

    when(lookupHit) {
      plruUpdate(lookupSet, hitWay)
      lruUpdate(lookupSet, hitWay)
    }
    val anyHit = lookupHit || superHit
    val adRefillNeeded =
      if (adMode == "hardware") anyHit && (!selectedA || (isStore && !selectedD))
      else false.B
    val effectiveHit = anyHit && !adRefillNeeded
    val normalGlobal = Mux1H(
      lookupWays,
      (0 until ways).map(w =>
        if (globalEnable)
          entriesVec(entryIndex(lookupSet, w.U)).global
        else false.B
      )
    )
    val superGlobal =
      if (superEntryCount > 0)
        Mux1H(superHits,
          (0 until superEntryCount).map(i => superVec.get(i).global))
      else false.B
    val selectedGlobal = Mux(superHit, superGlobal, normalGlobal)
    val ASIDHit =
      if (ASIDBits == 0) false.B
      else effectiveHit && !selectedGlobal
    io.result(port).hit := effectiveHit
    io.result(port).fault := effectiveHit && !permissionOk
    io.result(port).superpage := effectiveHit && superHit
    io.result(port).ASIDHit := ASIDHit
    val basePhysicalAddress = (Cat(SelectedPPN, lookup.vaddr(pageBits - 1, 0))).pad(addressWidth)
    io.result(port).paddr := Mux(superHit, superPhysicalAddress, basePhysicalAddress)
    io.result(port).cause := faultCause
    lastLookupVirtualAddress(port) := lookup.vaddr
    io.result(port).resultValid := !sramStorage.B || lastLookupVirtualAddress(port) === lookup.vaddr
  }

  when(io.flush) {
    for (i <- 0 until entries) {
      entriesVec(i).valid := false.B
    }
    superVec.foreach { v => for (i <- 0 until superEntryCount) { v(i).valid := false.B } }
    plruOpt.foreach { tree =>
      for (i <- 0 until sets * (ways - 1)) { tree(i) := false.B }
    }
    lruOpt.foreach { ages =>
      for (i <- 0 until entries) { ages(i) := 0.U }
    }
  }
  def doFill(valid: Bool, fillVirtualAddressVal: UInt, FillASIDVal: UInt,
      FillPTEVal: UInt, fillLevelVal: UInt, fillNapotVal: Bool): Unit = {
    val pteFlags = UnpackPte(FillPTEVal)
    if (superEntryCount > 0) {
      // fillLevel 0 是基础页；1..levels-1 是超级页层级；NATOP 单独标记。
      when(!io.flush && valid &&
          (fillNapotVal || (fillLevelVal =/= 0.U && fillLevelVal < levels.U))) {
        val fillShift = Mux(fillNapotVal, NapotShift.U(shiftBits.W),
          shiftOfLevel(fillLevelVal))
        val hit = (0 until superEntryCount).map { i =>
          val entry = superVec.get(i)
          entry.valid && entry.level === fillLevelVal &&
            entry.napot === fillNapotVal &&
            superTagMatch(fillShift, entry.vaddr, fillVirtualAddressVal)
        }
        val victim = Mux(hit.reduce(_ || _), PriorityEncoder(hit), superRoundRobin.get)
        val entry = Wire(new SuperEntry)
        entry := 0.U.asTypeOf(new SuperEntry)
        entry.valid := true.B
        entry.level := Mux(fillNapotVal, 0.U, fillLevelVal)
        entry.napot := fillNapotVal
        entry.vaddr := fillVirtualAddressVal
        entry.ASID := FillASIDVal
        entry.global := (if (globalEnable) pteFlags.g else false.B)
        // 与 PTW 的 paddrOf 保持一致：PPN 以基础页为单位，左移 pageBits
        // 得到超级页物理基址，低 shift 位由访问地址提供。只保留能装入
        // 物理地址宽度的低 PPNBits 位；PTW 已拒绝高位非零的 PTE。
        val low4Mask = ((BigInt(1) << 4) - 1).U(PPNBits.W)
        val ppnBase = Mux(fillNapotVal,
          pteFlags.ppn & ~low4Mask, pteFlags.ppn)
        entry.paddrBase := (ppnBase(PPNBits - 1, 0) << pageBits)
          .pad(addressWidth)
        entry.r := pteFlags.r; entry.w := pteFlags.w; entry.x := pteFlags.x
        // HAS_U=n 时不保存 U 位，避免保留无用的 U 状态。
        entry.u.foreach { u => u := pteFlags.u }
        entry.a := pteFlags.a; entry.d := pteFlags.d
        superVec.get(victim) := entry
        superRoundRobin.get := Mux(
          superRoundRobin.get === (superEntryCount - 1).U,
          0.U,
          superRoundRobin.get + 1.U
        )
      }
    }
    when(!io.flush && valid && !fillNapotVal &&
        (!superpage.B || fillLevelVal === 0.U)) {
      val FillVPN = vpnOf(fillVirtualAddressVal)
      val fillSet = setOf(FillVPN)
      val victimWay =
        if (replacement == "plru" && ways > 1) plruVictim(fillSet)
        else if (replacement == "lru") lruVictim(fillSet)
        else if (ways == 1) 0.U
        else roundRobin
      val index = entryIndex(fillSet, victimWay)
      PTEMems.foreach { mems =>
        mems.zipWithIndex.foreach { case (m, w) =>
          when(victimWay === w.U) { m.write(fillSet, FillPTEVal) }
        }
      }
      val fillEntry = Wire(new Entry)
      fillEntry := 0.U.asTypeOf(new Entry)
      fillEntry.valid := true.B
      fillEntry.tag := tagOf(FillVPN)
      fillEntry.ASID := FillASIDVal
      fillEntry.global := (if (globalEnable) pteFlags.g else false.B)
      fillEntry.PPN := pteFlags.ppn(PPNBits - 1, 0)
      fillEntry.r := pteFlags.r
      fillEntry.w := pteFlags.w
      fillEntry.x := pteFlags.x
      // HAS_U=n 时不保存 U 位。
      fillEntry.u.foreach { u => u := pteFlags.u }
      fillEntry.a := pteFlags.a
      fillEntry.d := pteFlags.d
      entriesVec(index) := fillEntry
      plruUpdate(fillSet, victimWay)
      lruUpdate(fillSet, victimWay)
      if (ways > 1) {
        roundRobin := Mux(roundRobin === (ways - 1).U, 0.U, roundRobin + 1.U)
      }
    }
  }

  doFill(io.FillValid, io.FillVirtualAddress, io.FillASID, io.FillPTE,
    io.FillLevel, io.FillNapot)
  io.FillExtra.foreach { extra =>
    for (port <- extra) {
      doFill(port.valid, port.VirtualAddress, port.ASID, port.PTE, port.level, port.napot)
    }
  }

}
