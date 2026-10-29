package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile

class ysyx_26030103_TLBWrite(
    val XLEN: Int = 32,
    val pteWidth: Int = 32
) extends Bundle {
  require(XLEN == 32 || XLEN == 64, s"TLBWrite XLEN 非法: $XLEN")
  require(pteWidth == 32 || pteWidth == 64, s"TLBWrite pteWidth 非法: $pteWidth")
  val valid = Bool()
  val vaddr = UInt(XLEN.W)
  val asid = UInt(16.W)
  val pte = UInt(pteWidth.W)
  // fillLevel 使用统一 3 位编码，支持最多 8 级页表。
  val level = UInt(ysyx_26030103_MMUProfile.LevelBits.W)
}

/** 组相联 TLB，支持 Sv32/Sv39/Sv48 和 1..2 个同拍查找端口。
  *
  * split 模式下 I/D TLB 各例化一份且只使用 1 个查找端口；unified 模式下
  * 可以通过 lookupPorts=2 让两个上下文在同一拍并行查找。寄存器阵列的
  * 查找是组合逻辑；SRAM 数据阵列每端口有独立的同步读端口和结果有效
  * 指示（resultValid）。refill 端口仍然独立于查找端口。
  *
  * RV64 下虚拟地址为 64 位，VPN 只使用 vaBits-1:pageBits；PTE 为 64 位，
  * PPN 使用标准 bit 53:10（44 位）。RV32/Sv32 的默认参数保持原行为。
  */
class ysyx_26030103_TLB(
    entries: Int = 16,
    ways: Int = 2,
    asidBits: Int = 9,
    globalEnable: Boolean = true,
    replacement: String = "rr",
    adMode: String = "fault",
    storage: String = "reg",
    superpage: Boolean = false,
    pageBits: Int = 12,
    levels: Int = 2,
    indexBits: Int = 10,
    refillPorts: Int = 1,
    superpageEntries: Int = 4,
    hasU: Boolean = true,
    lookupPorts: Int = 1,
    XLEN: Int = 32,
    addressWidth: Int = 32,
    vaBits: Int = 32,
    pteWidth: Int = 32,
    ptePpnLow: Int = 10,
    ptePpnHigh: Int = 31
) extends Module {
  require(entries > 0 && ways > 0 && entries % ways == 0, "TLB entries 必须能被 ways 整除")
  require((entries & (entries - 1)) == 0, "TLB entries 必须是 2 的幂")
  require(asidBits >= 0 && asidBits <= 16, "ASID 位数必须在 0..16")
  require((ways & (ways - 1)) == 0, "TLB ways 必须是 2 的幂")
  require(Set("reg", "sram").contains(storage), "TLB storage 必须是 reg/sram")
  require(lookupPorts >= 1 && lookupPorts <= 2, "TLB lookupPorts 必须在 1..2")
  require(levels >= 1 && levels <= ysyx_26030103_MMUProfile.MaxLevels,
    s"TLB levels 必须在 1..${ysyx_26030103_MMUProfile.MaxLevels}")
  require(indexBits >= 1 && indexBits <= 30, "TLB indexBits 非法")
  require(XLEN == 32 || XLEN == 64, s"TLB XLEN 非法: $XLEN")
  require(addressWidth == 32 || addressWidth == 64,
    s"TLB addressWidth 非法: $addressWidth")
  require(vaBits > pageBits && vaBits <= XLEN, "TLB vaBits 非法")
  require(pteWidth == 32 || pteWidth == 64, "TLB pteWidth 非法")
  require(ptePpnLow >= 8 && ptePpnHigh < pteWidth && ptePpnLow <= ptePpnHigh,
    "TLB PTE PPN 位置非法")
  // 超级页的 shift/tag 由 pageBits/levels/indexBits 推导；非标准几何
  // 必须满足有效虚拟地址可完整划分，否则拒绝 elaboration 而不是静默错映射。
  require(!superpage || pageBits + levels * indexBits == vaBits,
    s"TLB 超级页要求 pageBits($pageBits) + levels($levels)*indexBits($indexBits) 等于 vaBits($vaBits)")

  private val sets = entries / ways
  private val setBits = if (sets == 1) 0 else log2Ceil(sets)
  private val asidWidth = asidBits.max(1)
  private val vpnBits = vaBits - pageBits
  // PTE 中可用的 PPN 位数（Sv32=22，Sv39/Sv48=44），再受物理地址宽度约束。
  private val ptePpnBits = ptePpnHigh - ptePpnLow + 1
  private val ppnBits = math.min(ptePpnBits, addressWidth - pageBits)
  require(ppnBits >= 1, "TLB 的 PPN 字段无法装入物理地址宽度")
  private val entryBits = log2Ceil(entries)

  class Entry extends Bundle {
    val valid = Bool()
    val tag = UInt((vpnBits - setBits).W)
    val asid = UInt(asidWidth.W)
    val global = Bool()
    val ppn = UInt(ppnBits.W)
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
    val asid = UInt(asidWidth.W)
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
  }

  val io = IO(new Bundle {
    // 端口 0 始终存在；lookupPorts=2 时端口 1 用于 unified TLB 的第二个上下文。
    val lookup = Input(Vec(lookupPorts, new Lookup))
    val result = Output(Vec(lookupPorts, new Result))
    val fillValid = Input(Bool())
    val fillVaddr = Input(UInt(XLEN.W))
    val fillAsid = Input(UInt(16.W))
    val fillPte = Input(UInt(pteWidth.W))
    val fillLevel = Input(UInt(ysyx_26030103_MMUProfile.LevelBits.W))
    // 第 2..refillPorts 个写回端口；端口 0 保持上面的兼容接口。
    val fillExtra = if (refillPorts > 1)
      Some(Input(Vec(refillPorts - 1,
        new ysyx_26030103_TLBWrite(XLEN, pteWidth)))) else None
    val flush = Input(Bool())
  })

  val entriesVec = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new Entry))))
  val rr = RegInit(0.U(log2Ceil(ways.max(2)).W))
  // 超级页表项不再硬编码 4MiB/vpn1。每个表项保存填充层级、完整虚拟
  // 地址和物理基址；比较时按该层级对应的 shift 对齐。fillLevel 约定：
  // 0=基础页，k=第 k 级超级页，shift = pageBits + k*indexBits。
  require(superpageEntries >= 1 && superpageEntries <= 16,
    "TLB superpageEntries 必须在 1..16")
  private val superEntryCount =
    if (superpage && levels > 1) superpageEntries else 0
  class SuperEntry extends Bundle {
    val valid = Bool()
    val level = UInt(ysyx_26030103_MMUProfile.LevelBits.W)
    val vaddr = UInt(XLEN.W)
    val asid = UInt(asidWidth.W)
    val global = Bool()
    val paddrBase = UInt(addressWidth.W)
    val r = Bool(); val w = Bool(); val x = Bool()
    val u = if (hasU) Some(Bool()) else None
    val a = Bool(); val d = Bool()
  }
  private val shiftBits = log2Ceil(XLEN.max(32))
  private val maxShift = pageBits + (levels - 1) * indexBits
  /** 填充层级对应的地址 shift；level 超出配置范围时返回最大 shift。 */
  private def shiftOfLevel(level: UInt): UInt =
    MuxLookup(level, maxShift.U(shiftBits.W))(
      (0 until levels).map(l => l.U -> (pageBits + l * indexBits).U(shiftBits.W))
    )
  /** 超级页偏移掩码：低 shift 位为 1。 */
  private def offsetMaskOf(shift: UInt): UInt =
    (~0.U(XLEN.W)) >> (XLEN.U - shift)
  /** 判断虚拟地址是否落在同一超级页。 */
  private def superTagMatch(level: UInt, entryVaddr: UInt, lookupVaddr: UInt): Bool = {
    val mask = ~offsetMaskOf(shiftOfLevel(level))
    (entryVaddr & mask) === (lookupVaddr & mask)
  }
  val superVec = if (superEntryCount > 0)
    Some(RegInit(VecInit(Seq.fill(superEntryCount)(0.U.asTypeOf(new SuperEntry)))))
  else None
  val superRR = if (superEntryCount > 0)
    Some(RegInit(0.U(log2Ceil(superEntryCount.max(2)).W))) else None
  private val sramStorage = storage == "sram"
  val pteMems =
    if (sramStorage) Some((0 until ways).map(_ => SyncReadMem(sets, UInt(pteWidth.W))))
    else None
  // SRAM 读出一拍后才有效；每个查找端口分别记录上一拍地址。
  val lastLookupVaddr =
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
        val idx = entryIndex(set, w.U)
        when(way === w.U) { ages(idx) := 0.U }
          .elsewhen(ages(idx) =/= lruMax) { ages(idx) := ages(idx) + 1.U }
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

  private def vpnOf(addr: UInt): UInt = addr(vaBits - 1, pageBits)
  private def setOf(vpn: UInt): UInt =
    if (setBits == 0) 0.U else vpn(setBits - 1, 0)
  private def tagOf(vpn: UInt): UInt =
    if (setBits == 0) vpn else vpn(vpnBits - 1, setBits)
  private def entryIndex(set: UInt, way: UInt): UInt =
    if (entryBits == 0) 0.U else (set * ways.U + way)(entryBits - 1, 0)
  private def ptePpnOf(pte: UInt): UInt =
    pte(ptePpnLow + ptePpnBits - 1, ptePpnLow)

  // 每个查找端口独立做 tag/asid/权限检查。refill 在本拍末尾写入寄存器，
  // 因而不影响本拍已经组合出来的旧表项结果。
  for (p <- 0 until lookupPorts) {
    val lookup = io.lookup(p)
    val lookupVpn = vpnOf(lookup.vaddr)
    val lookupSet = setOf(lookupVpn)
    val lookupTag = tagOf(lookupVpn)
    val sramPtes = pteMems
      .map(_.map(m => m.read(lookupSet)))
      .getOrElse(Seq.fill(ways)(0.U(pteWidth.W)))
    val lookupWays = (0 until ways).map { w =>
      val e = entriesVec(entryIndex(lookupSet, w.U))
      val asidMatch = if (asidBits == 0) true.B else e.asid === lookup.asid
      val globalMatch = if (globalEnable) e.global else false.B
      e.valid && e.tag === lookupTag && (globalMatch || asidMatch)
    }
    val lookupHit = lookupWays.reduce(_ || _)
    val hitWay = Mux1H(lookupWays, (0 until ways).map(w => w.U(log2Ceil(ways.max(2)).W)))
    val selected = Mux1H(lookupWays, (0 until ways).map(w => entriesVec(entryIndex(lookupSet, w.U))))
    val selectedPte = Mux1H(lookupWays, sramPtes)
    val superHits = if (superEntryCount > 0) {
      (0 until superEntryCount).map { i =>
        val e = superVec.get(i)
        val asidMatch = if (asidBits == 0) true.B else e.asid === lookup.asid
        val globalMatch = if (globalEnable) e.global else false.B
        e.valid && e.level =/= 0.U &&
          superTagMatch(e.level, e.vaddr, lookup.vaddr) &&
          (globalMatch || asidMatch)
      }
    } else Seq.empty
    val superHit = if (superEntryCount > 0) superHits.reduce(_ || _) else false.B
    val superSel = if (superEntryCount > 0)
      Mux1H(superHits, (0 until superEntryCount).map(i => superVec.get(i)))
    else 0.U.asTypeOf(new SuperEntry)
    val superPaddr = if (superEntryCount > 0) {
      val paddrs = (0 until superEntryCount).map { i =>
        val e = superVec.get(i)
        e.paddrBase | (lookup.vaddr & offsetMaskOf(shiftOfLevel(e.level)))
      }
      Mux1H(superHits, paddrs)
    } else 0.U(addressWidth.W)
    val selR = Mux(superHit, superSel.r,
      if (sramStorage) selectedPte(1) else selected.r)
    val selW = Mux(superHit, superSel.w,
      if (sramStorage) selectedPte(2) else selected.w)
    val selX = Mux(superHit, superSel.x,
      if (sramStorage) selectedPte(3) else selected.x)
    val selU =
      if (hasU)
        Mux(superHit, superSel.u.get,
          if (sramStorage) selectedPte(4) else selected.u.get)
      else false.B
    val selA = Mux(superHit, superSel.a,
      if (sramStorage) selectedPte(6) else selected.a)
    val selD = Mux(superHit, superSel.d,
      if (sramStorage) selectedPte(7) else selected.d)
    val selPpn = Mux(superHit, 0.U(ppnBits.W),
      if (sramStorage) ptePpnOf(selectedPte)(ppnBits - 1, 0) else selected.ppn)

    val isFetch = lookup.access === ysyx_26030103_AccessType.Fetch
    val isStore = lookup.access === ysyx_26030103_AccessType.Store
    val privilegeU = if (hasU) lookup.privilege === 0.U else false.B
    val privilegeS = lookup.privilege === 1.U
    // HAS_U=n 时 U 级和 SUM 语义不存在：U 位检查固定为不适用，
    // S 模式可正常访问 U=1 的页，fetch/read/write 权限仍按 R/W/X 检查。
    val fetchOk =
      if (hasU) selX && !(privilegeS && selU) else selX
    val readOk = selR || (lookup.mxr && selX)
    val writeOk = selW
    val userOk = if (hasU) !privilegeU || selU else true.B
    val sumOk =
      if (hasU) !(privilegeS && selU) || (lookup.sum && !isFetch) else true.B
    val accessOk = Mux(isFetch, fetchOk, Mux(isStore, writeOk, readOk))
    // hardware 模式的 A/D 写回由 PTW 在填表前完成，TLB 只缓存已处理的
    // PTE。若某表项由 load 填入（A=1,D=0），之后的 store 命中不会单独
    // 更新 D；要补齐这一点需要给 TLB 增加页表写回端口，属于已知限制，
    // 不在本次修改范围内。fault 模式仍按 A/D 位拒绝访问。
    val adOk = adMode match {
      case "fault" => selA && (!isStore || selD)
      case _       => true.B
    }
    // R=0 且 W=1 是保留编码；TLB 中已有的表项也不能绕过该检查。
    val reservedPte = !selR && selW
    val permissionOk = !reservedPte && userOk && sumOk && accessOk && adOk
    val faultCause = Mux(isFetch, 12.U, Mux(isStore, 15.U, 13.U))

    when(lookupHit) {
      plruUpdate(lookupSet, hitWay)
      lruUpdate(lookupSet, hitWay)
    }
    val anyHit = lookupHit || superHit
    io.result(p).hit := anyHit
    io.result(p).fault := anyHit && !permissionOk
    val basePaddr = (Cat(selPpn, lookup.vaddr(pageBits - 1, 0))).pad(addressWidth)
    io.result(p).paddr := Mux(superHit, superPaddr, basePaddr)
    io.result(p).cause := faultCause
    lastLookupVaddr(p) := lookup.vaddr
    io.result(p).resultValid := !sramStorage.B || lastLookupVaddr(p) === lookup.vaddr
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
  def doFill(valid: Bool, fillVaddrVal: UInt, fillAsidVal: UInt,
      fillPteVal: UInt, fillLevelVal: UInt): Unit = {
    if (superEntryCount > 0) {
      // fillLevel 0 是基础页；1..levels-1 是超级页层级。TLB 只缓存
      // 配置范围内的层级，超出范围的填充请求被忽略（PTW 不会产生）。
      when(!io.flush && valid && fillLevelVal =/= 0.U &&
          fillLevelVal < levels.U) {
        val hit = (0 until superEntryCount).map { i =>
          val e = superVec.get(i)
          e.valid && e.level === fillLevelVal &&
            superTagMatch(e.level, e.vaddr, fillVaddrVal)
        }
        val victim = Mux(hit.reduce(_ || _), PriorityEncoder(hit), superRR.get)
        val e = Wire(new SuperEntry)
        e := 0.U.asTypeOf(new SuperEntry)
        e.valid := true.B
        e.level := fillLevelVal
        e.vaddr := fillVaddrVal
        e.asid := fillAsidVal
        e.global := (if (globalEnable) fillPteVal(5) else false.B)
        // 与 PTW 的 paddrOf 保持一致：PPN 以基础页为单位，左移 pageBits
        // 得到超级页物理基址，低 shift 位由访问地址提供。只保留能装入
        // 物理地址宽度的低 ppnBits 位；PTW 已拒绝高位非零的 PTE。
        e.paddrBase := (ptePpnOf(fillPteVal)(ppnBits - 1, 0) << pageBits)
          .pad(addressWidth)
        e.r := fillPteVal(1); e.w := fillPteVal(2); e.x := fillPteVal(3)
        // HAS_U=n 时不保存 U 位，避免保留无用的 U 状态。
        e.u.foreach { u => u := fillPteVal(4) }
        e.a := fillPteVal(6); e.d := fillPteVal(7)
        superVec.get(victim) := e
        superRR.get := Mux(
          superRR.get === (superEntryCount - 1).U,
          0.U,
          superRR.get + 1.U
        )
      }
    }
    when(!io.flush && valid && (!superpage.B || fillLevelVal === 0.U)) {
      val fillVpn = vpnOf(fillVaddrVal)
      val fillSet = setOf(fillVpn)
      val victimWay =
        if (replacement == "plru" && ways > 1) plruVictim(fillSet)
        else if (replacement == "lru") lruVictim(fillSet)
        else if (ways == 1) 0.U
        else rr
      val idx = entryIndex(fillSet, victimWay)
      pteMems.foreach { mems =>
        mems.zipWithIndex.foreach { case (m, w) =>
          when(victimWay === w.U) { m.write(fillSet, fillPteVal) }
        }
      }
      val fillEntry = Wire(new Entry)
      fillEntry := 0.U.asTypeOf(new Entry)
      fillEntry.valid := true.B
      fillEntry.tag := tagOf(fillVpn)
      fillEntry.asid := fillAsidVal
      fillEntry.global := (if (globalEnable) fillPteVal(5) else false.B)
      fillEntry.ppn := ptePpnOf(fillPteVal)(ppnBits - 1, 0)
      fillEntry.r := fillPteVal(1)
      fillEntry.w := fillPteVal(2)
      fillEntry.x := fillPteVal(3)
      // HAS_U=n 时不保存 U 位。
      fillEntry.u.foreach { u => u := fillPteVal(4) }
      fillEntry.a := fillPteVal(6)
      fillEntry.d := fillPteVal(7)
      entriesVec(idx) := fillEntry
      plruUpdate(fillSet, victimWay)
      lruUpdate(fillSet, victimWay)
      if (ways > 1) {
        rr := Mux(rr === (ways - 1).U, 0.U, rr + 1.U)
      }
    }
  }

  doFill(io.fillValid, io.fillVaddr, io.fillAsid, io.fillPte, io.fillLevel)
  io.fillExtra.foreach { extra =>
    for (port <- extra) {
      doFill(port.valid, port.vaddr, port.asid, port.pte, port.level)
    }
  }

}
