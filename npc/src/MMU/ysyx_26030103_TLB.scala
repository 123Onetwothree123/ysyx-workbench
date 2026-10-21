package ysyx_26030103.mmu

import chisel3._
import chisel3.util._

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
    storage: String = "reg"
) extends Module {
  require(entries > 0 && ways > 0 && entries % ways == 0, "TLB entries 必须能被 ways 整除")
  require((entries & (entries - 1)) == 0, "TLB entries 必须是 2 的幂")
  require(asidBits >= 0 && asidBits <= 9, "ASID 位数必须在 0..9")
  require((ways & (ways - 1)) == 0, "TLB ways 必须是 2 的幂")
  require(Set("reg", "sram").contains(storage), "TLB storage 必须是 reg/sram")

  private val sets = entries / ways
  private val setBits = if (sets == 1) 0 else log2Ceil(sets)
  private val asidWidth = asidBits.max(1)
  private val vpnBits = 20
  private val entryBits = log2Ceil(entries)

  class Entry extends Bundle {
    val valid = Bool()
    val tag = UInt((vpnBits - setBits).W)
    val asid = UInt(asidWidth.W)
    val global = Bool()
    val ppn = UInt(22.W)
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
    val flush = Input(Bool())
  })

  val entriesVec = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new Entry))))
  val rr = RegInit(0.U(log2Ceil(ways.max(2)).W))
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

  private def vpnOf(addr: UInt): UInt = addr(31, 12)
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
  val selR = if (sramStorage) selectedPte(1) else selected.r
  val selW = if (sramStorage) selectedPte(2) else selected.w
  val selX = if (sramStorage) selectedPte(3) else selected.x
  val selU = if (sramStorage) selectedPte(4) else selected.u
  val selA = if (sramStorage) selectedPte(6) else selected.a
  val selD = if (sramStorage) selectedPte(7) else selected.d
  val selPpn = if (sramStorage) selectedPte(31, 10) else selected.ppn

  val isFetch = io.lookup.access === ysyx_26030103_AccessType.Fetch
  val isStore = io.lookup.access === ysyx_26030103_AccessType.Store
  val privilegeU = io.lookup.privilege === 0.U
  val privilegeS = io.lookup.privilege === 1.U
  val fetchOk = selX && !(privilegeS && selU)
  val readOk = selR || (io.lookup.mxr && selX)
  val writeOk = selW
  val userOk = !privilegeU || selU
  val sumOk = !(privilegeS && selU) || (io.lookup.sum && !isFetch)
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
  io.result.hit := lookupHit
  io.result.fault := lookupHit && !permissionOk
  io.result.paddr := Cat(selPpn(19, 0), io.lookup.vaddr(11, 0))
  io.result.cause := faultCause

  when(io.flush) {
    for (i <- 0 until entries) {
      entriesVec(i).valid := false.B
    }
    plruOpt.foreach { tree =>
      for (i <- 0 until sets * (ways - 1)) { tree(i) := false.B }
    }
    lruOpt.foreach { ages =>
      for (i <- 0 until entries) { ages(i) := 0.U }
    }
  }.elsewhen(io.fillValid) {
    val fillVpn = vpnOf(io.fillVaddr)
    val fillSet = setOf(fillVpn)
    val victimWay =
      if (replacement == "plru" && ways > 1) plruVictim(fillSet)
      else if (replacement == "lru") lruVictim(fillSet)
      else if (ways == 1) 0.U
      else rr
    val idx = entryIndex(fillSet, victimWay)
    pteMems.foreach { mems =>
      mems.zipWithIndex.foreach { case (m, w) =>
        when(victimWay === w.U) { m.write(fillSet, io.fillPte) }
      }
    }
    val fillEntry = Wire(new Entry)
    fillEntry := 0.U.asTypeOf(new Entry)
    fillEntry.valid := true.B
    fillEntry.tag := tagOf(fillVpn)
    fillEntry.asid := io.fillAsid
    fillEntry.global := (if (globalEnable) io.fillPte(5) else false.B)
    fillEntry.ppn := io.fillPte(31, 10)
    fillEntry.r := io.fillPte(1)
    fillEntry.w := io.fillPte(2)
    fillEntry.x := io.fillPte(3)
    fillEntry.u := io.fillPte(4)
    fillEntry.a := io.fillPte(6)
    fillEntry.d := io.fillPte(7)
    entriesVec(idx) := fillEntry
    plruUpdate(fillSet, victimWay)
    lruUpdate(fillSet, victimWay)
    if (ways > 1) {
      rr := Mux(rr === (ways - 1).U, 0.U, rr + 1.U)
    }
  }
}
