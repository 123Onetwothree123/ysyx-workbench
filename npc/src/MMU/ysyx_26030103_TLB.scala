package ysyx_26030103.mmu

import chisel3._
import chisel3.util._

class ysyx_26030103_TLBWrite extends Bundle {
  val valid = Bool()
  val vaddr = UInt(32.W)
  val asid = UInt(9.W)
  val pte = UInt(32.W)
  val level = UInt(2.W)
}

/** 单端口、组相联的 Sv32 TLB。
  *
  * 这是批次 1 的默认 RTL：split 模式下例化两份（I/D），unified 模式下
  * 例化一份。当前实现使用寄存器阵列和 round-robin 填充；storage/replacement
  * 参数保留，后续可扩展 SRAM/PLRU/LRU，而不改变外部接口。
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
    refillPorts: Int = 1,
    hasU: Boolean = true
) extends Module {
  require(entries > 0 && ways > 0 && entries % ways == 0, "TLB entries 必须能被 ways 整除")
  require((entries & (entries - 1)) == 0, "TLB entries 必须是 2 的幂")
  require(asidBits >= 0 && asidBits <= 9, "ASID 位数必须在 0..9")
  require((ways & (ways - 1)) == 0, "TLB ways 必须是 2 的幂")
  require(Set("reg", "sram").contains(storage), "TLB storage 必须是 reg/sram")

  private val sets = entries / ways
  private val setBits = if (sets == 1) 0 else log2Ceil(sets)
  private val asidWidth = asidBits.max(1)
  private val vpnBits = 32 - pageBits
  private val ppnBits = 32 - pageBits
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
    val u = Bool()
    val a = Bool()
    val d = Bool()
  }

  val io = IO(new Bundle {
    val lookup = Input(new Bundle {
      val vaddr = UInt(32.W)
      val asid = UInt(asidWidth.W)
      val privilege = UInt(2.W)
      val access = UInt(2.W)
      val sum = Bool()
      val mxr = Bool()
    })
    val result = Output(new Bundle {
      val hit = Bool()
      val fault = Bool()
      val paddr = UInt(32.W)
      val cause = UInt(32.W)
      val resultValid = Bool()
    })
    val fillValid = Input(Bool())
    val fillVaddr = Input(UInt(32.W))
    val fillAsid = Input(UInt(asidWidth.W))
    val fillPte = Input(UInt(32.W))
    val fillLevel = Input(UInt(2.W))
    // 第 2..refillPorts 个写回端口；端口 0 保持上面的兼容接口。
    val fillExtra = if (refillPorts > 1)
      Some(Input(Vec(refillPorts - 1, new ysyx_26030103_TLBWrite))) else None
    val flush = Input(Bool())
  })

  val entriesVec = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new Entry))))
  val rr = RegInit(0.U(log2Ceil(ways.max(2)).W))
  private val superEntries = if (superpage) 4 else 0
  class SuperEntry extends Bundle {
    val valid = Bool()
    val vpn1 = UInt(10.W)
    val asid = UInt(asidWidth.W)
    val global = Bool()
    val ppn = UInt(ppnBits.W)
    val r = Bool(); val w = Bool(); val x = Bool(); val u = Bool()
    val a = Bool(); val d = Bool()
  }
  val superVec = if (superpage)
    Some(RegInit(VecInit(Seq.fill(superEntries)(0.U.asTypeOf(new SuperEntry)))))
  else None
  val superRR = if (superpage) Some(RegInit(0.U(2.W))) else None
  private val sramStorage = storage == "sram"
  val pteMems =
    if (sramStorage) Some((0 until ways).map(_ => SyncReadMem(sets, UInt(32.W))))
    else None
  val lastLookupVaddr = RegInit(0.U(32.W))
  lastLookupVaddr := io.lookup.vaddr
  io.result.resultValid := !sramStorage.B || lastLookupVaddr === io.lookup.vaddr
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

  private def vpnOf(addr: UInt): UInt = addr(31, pageBits)
  private def setOf(vpn: UInt): UInt =
    if (setBits == 0) 0.U else vpn(setBits - 1, 0)
  private def tagOf(vpn: UInt): UInt =
    if (setBits == 0) vpn else vpn(vpnBits - 1, setBits)
  private def entryIndex(set: UInt, way: UInt): UInt =
    if (entryBits == 0) 0.U else (set * ways.U + way)(entryBits - 1, 0)

  val lookupVpn = vpnOf(io.lookup.vaddr)
  val lookupSet = setOf(lookupVpn)
  val lookupTag = tagOf(lookupVpn)
  val sramPtes = pteMems.map(_.map(m => m.read(lookupSet))).getOrElse(Seq.fill(ways)(0.U(32.W)))
  val lookupWays = (0 until ways).map { w =>
    val e = entriesVec(entryIndex(lookupSet, w.U))
    val asidMatch = if (asidBits == 0) true.B else e.asid === io.lookup.asid
    val globalMatch = if (globalEnable) e.global else false.B
    e.valid && e.tag === lookupTag && (globalMatch || asidMatch)
  }
  val lookupHit = lookupWays.reduce(_ || _)
  val hitWay = Mux1H(lookupWays, (0 until ways).map(w => w.U(log2Ceil(ways.max(2)).W)))
  val selected = Mux1H(lookupWays, (0 until ways).map(w => entriesVec(entryIndex(lookupSet, w.U))))
  val selectedPte = Mux1H(lookupWays, sramPtes)
  val superHits = if (superpage) {
    (0 until superEntries).map { i =>
      val e = superVec.get(i)
      val asidMatch = if (asidBits == 0) true.B else e.asid === io.lookup.asid
      val globalMatch = if (globalEnable) e.global else false.B
      e.valid && e.vpn1 === io.lookup.vaddr(31, 22) && (globalMatch || asidMatch)
    }
  } else Seq.empty
  val superHit = if (superpage) superHits.reduce(_ || _) else false.B
  val superSel = if (superpage)
    Mux1H(superHits, (0 until superEntries).map(i => superVec.get(i)))
  else 0.U.asTypeOf(new SuperEntry)
  val selR = Mux(superHit, superSel.r,
    if (sramStorage) selectedPte(1) else selected.r)
  val selW = Mux(superHit, superSel.w,
    if (sramStorage) selectedPte(2) else selected.w)
  val selX = Mux(superHit, superSel.x,
    if (sramStorage) selectedPte(3) else selected.x)
  val selU = Mux(superHit, superSel.u,
    if (sramStorage) selectedPte(4) else selected.u)
  val selA = Mux(superHit, superSel.a,
    if (sramStorage) selectedPte(6) else selected.a)
  val selD = Mux(superHit, superSel.d,
    if (sramStorage) selectedPte(7) else selected.d)
  val selPpn = Mux(superHit, superSel.ppn,
    if (sramStorage) selectedPte(31, 10) else selected.ppn)

  val isFetch = io.lookup.access === ysyx_26030103_AccessType.Fetch
  val isStore = io.lookup.access === ysyx_26030103_AccessType.Store
  val privilegeU = if (hasU) io.lookup.privilege === 0.U else false.B
  val privilegeS = io.lookup.privilege === 1.U
  // HAS_U=n 时 U 级和 SUM 语义不存在：U 位检查固定为不适用，
  // S 模式可正常访问 U=1 的页，fetch/read/write 权限仍按 R/W/X 检查。
  val fetchOk = selX && !(hasU.B && privilegeS && selU)
  val readOk = selR || (io.lookup.mxr && selX)
  val writeOk = selW
  val userOk = if (hasU) !privilegeU || selU else true.B
  val sumOk =
    if (hasU) !(privilegeS && selU) || (io.lookup.sum && !isFetch) else true.B
  val accessOk = Mux(isFetch, fetchOk, Mux(isStore, writeOk, readOk))
  val adOk = adMode match {
    case "fault" => selA && (!isStore || selD)
    case _       => true.B
  }
  val permissionOk = userOk && sumOk && accessOk && adOk
  val faultCause = Mux(isFetch, 12.U, Mux(isStore, 15.U, 13.U))

  when(lookupHit) {
    plruUpdate(lookupSet, hitWay)
    lruUpdate(lookupSet, hitWay)
  }
  val anyHit = lookupHit || superHit
  io.result.hit := anyHit
  io.result.fault := anyHit && !permissionOk
  io.result.paddr := Mux(superHit, Cat(selPpn(19, 10), io.lookup.vaddr(21, 0)),
    Cat(selPpn(ppnBits - 1, 0), io.lookup.vaddr(pageBits - 1, 0)))
  io.result.cause := faultCause

  when(io.flush) {
    for (i <- 0 until entries) {
      entriesVec(i).valid := false.B
    }
    superVec.foreach { v => for (i <- 0 until superEntries) { v(i).valid := false.B } }
    plruOpt.foreach { tree =>
      for (i <- 0 until sets * (ways - 1)) { tree(i) := false.B }
    }
    lruOpt.foreach { ages =>
      for (i <- 0 until entries) { ages(i) := 0.U }
    }
  }
  def doFill(valid: Bool, fillVaddrVal: UInt, fillAsidVal: UInt,
      fillPteVal: UInt, fillLevelVal: UInt): Unit = {
    if (superpage) {
      when(!io.flush && valid && fillLevelVal === 1.U) {
        val hit = (0 until superEntries).map(i => superVec.get(i).valid &&
          superVec.get(i).vpn1 === fillVaddrVal(31, 22))
        val victim = Mux(hit.reduce(_ || _), PriorityEncoder(hit), superRR.get)
        val e = Wire(new SuperEntry)
        e := 0.U.asTypeOf(new SuperEntry)
        e.valid := true.B
        e.vpn1 := fillVaddrVal(31, 22)
        e.asid := fillAsidVal
        e.global := (if (globalEnable) fillPteVal(5) else false.B)
        e.ppn := Cat(fillPteVal(29, 20), 0.U(10.W))
        e.r := fillPteVal(1); e.w := fillPteVal(2); e.x := fillPteVal(3)
        // HAS_U=n 时不保存 U 位，避免保留无用的 U 状态。
        e.u := (if (hasU) fillPteVal(4) else false.B)
        e.a := fillPteVal(6); e.d := fillPteVal(7)
        superVec.get(victim) := e
        superRR.get := Mux(superRR.get === (superEntries - 1).U, 0.U, superRR.get + 1.U)
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
      fillEntry.ppn := fillPteVal(31, 10)(ppnBits - 1, 0)
      fillEntry.r := fillPteVal(1)
      fillEntry.w := fillPteVal(2)
      fillEntry.x := fillPteVal(3)
      // HAS_U=n 时不保存 U 位。
      fillEntry.u := (if (hasU) fillPteVal(4) else false.B)
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
