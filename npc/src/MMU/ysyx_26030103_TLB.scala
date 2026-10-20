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
    adMode: String = "fault"
) extends Module {
  require(entries > 0 && ways > 0 && entries % ways == 0, "TLB entries 必须能被 ways 整除")
  require((entries & (entries - 1)) == 0, "TLB entries 必须是 2 的幂")
  require(asidBits >= 0 && asidBits <= 9, "ASID 位数必须在 0..9")

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
    })
    val fillValid = Input(Bool())
    val fillVaddr = Input(UInt(32.W))
    val fillAsid = Input(UInt(asidWidth.W))
    val fillPte = Input(UInt(32.W))
    val flush = Input(Bool())
  })

  val entriesVec = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new Entry))))
  val rr = RegInit(0.U(log2Ceil(ways.max(2)).W))

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
  val lookupWays = (0 until ways).map { w =>
    val e = entriesVec(entryIndex(lookupSet, w.U))
    val asidMatch = if (asidBits == 0) true.B else e.asid === io.lookup.asid
    val globalMatch = if (globalEnable) e.global else false.B
    e.valid && e.tag === lookupTag && (globalMatch || asidMatch)
  }
  val lookupHit = lookupWays.reduce(_ || _)
  val selected = Mux1H(lookupWays, (0 until ways).map(w => entriesVec(entryIndex(lookupSet, w.U))))

  val isFetch = io.lookup.access === ysyx_26030103_AccessType.Fetch
  val isStore = io.lookup.access === ysyx_26030103_AccessType.Store
  val privilegeU = io.lookup.privilege === 0.U
  val privilegeS = io.lookup.privilege === 1.U
  val fetchOk = selected.x && !(privilegeS && selected.u)
  val readOk = selected.r || (io.lookup.mxr && selected.x)
  val writeOk = selected.w
  val userOk = !privilegeU || selected.u
  val sumOk = !(privilegeS && selected.u) || (io.lookup.sum && !isFetch)
  val accessOk = Mux(isFetch, fetchOk, Mux(isStore, writeOk, readOk))
  val adOk = adMode match {
    case "fault" => selected.a && (!isStore || selected.d)
    case _       => true.B
  }
  val permissionOk = userOk && sumOk && accessOk && adOk
  val faultCause = Mux(isFetch, 12.U, Mux(isStore, 15.U, 13.U))

  io.result.hit := lookupHit
  io.result.fault := lookupHit && !permissionOk
  io.result.paddr := Cat(selected.ppn(19, 0), io.lookup.vaddr(11, 0))
  io.result.cause := faultCause

  when(io.flush) {
    for (i <- 0 until entries) {
      entriesVec(i).valid := false.B
    }
  }.elsewhen(io.fillValid) {
    val fillVpn = vpnOf(io.fillVaddr)
    val fillSet = setOf(fillVpn)
    val idx = entryIndex(fillSet, if (ways == 1) 0.U else rr)
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
    if (ways > 1) {
      rr := Mux(rr === (ways - 1).U, 0.U, rr + 1.U)
    }
  }
}
