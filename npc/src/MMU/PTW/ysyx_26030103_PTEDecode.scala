package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_RISCVFields
import _root_.ysyx_26030103.config.ysyx_26030103_PTEConfig

private[mmu] object ysyx_26030103_PTEDecode {
  private def indexBitsOf(pageBits: Int, levels: Int, vaBits: Int): Int =
    (vaBits - pageBits) / levels
  private def pteShiftOf(pteWidth: Int): Int = log2Ceil(pteWidth / 8)
  private def ptePPNBitsOf(PTEConfig: ysyx_26030103_PTEConfig): Int =
    PTEConfig.PPNHigh - PTEConfig.PPNLow + 1
  private def physicalPPNBitsOf(PTEConfig: ysyx_26030103_PTEConfig,
      pageBits: Int, addressWidth: Int): Int =
    math.min(ptePPNBitsOf(PTEConfig), addressWidth - pageBits)
  private def satpPPNBitsOf(pageBits: Int, addressWidth: Int): Int =
    math.min(44, addressWidth - pageBits)

  def normalizePTE(PTE: UInt, PTEConfig: ysyx_26030103_PTEConfig,
      pteWidth: Int): UInt = {
    val flags = Cat(
      PTE(PTEConfig.dBit), PTE(PTEConfig.aBit), PTE(PTEConfig.globalBit),
      PTE(PTEConfig.userBit), PTE(PTEConfig.executeBit), PTE(PTEConfig.writeBit),
      PTE(PTEConfig.readBit), PTE(PTEConfig.validBit)
    )
    val PPN = PTE(PTEConfig.PPNHigh, PTEConfig.PPNLow)
    Cat(PPN.pad(pteWidth - PTEConfig.PPNLow),
      if (PTEConfig.PPNLow > 8) 0.U((PTEConfig.PPNLow - 8).W) else 0.U,
      flags)
  }

  def adRaw(PTE: UInt, isStore: Bool, PTEConfig: ysyx_26030103_PTEConfig,
      pteWidth: Int): UInt =
    PTE | (1.U << PTEConfig.aBit) |
      Mux(isStore, 1.U << PTEConfig.dBit, 0.U(pteWidth.W))

  def isLeaf(PTE: UInt): Bool =
    Cat(PTE(2), PTE(1), PTE(3)) =/= 0.U

  def reservedEncoding(PTE: UInt): Bool = !PTE(1) && PTE(2)

  def reservedFault(PTE: UInt, PTEConfig: ysyx_26030103_PTEConfig,
      pteWidth: Int): Bool =
    if (PTEConfig.PPNHigh + 1 >= pteWidth) false.B
    else PTE(pteWidth - 1, PTEConfig.PPNHigh + 1) =/= 0.U

  def rawReservedFault(raw: UInt, PTEConfig: ysyx_26030103_PTEConfig,
      xlen: Int, pteWidth: Int, svnapotSupported: Boolean,
      svnapotLeaf: Bool): Bool =
    if (xlen <= 32 || pteWidth <= 32 || PTEConfig.PPNHigh + 1 >= pteWidth) false.B
    else {
      val high = raw(pteWidth - 1, PTEConfig.PPNHigh + 1)
      if (!svnapotSupported) high =/= 0.U
      else {
        val highWithoutN = raw(pteWidth - 2, PTEConfig.PPNHigh + 1)
        Mux(svnapotLeaf, highWithoutN =/= 0.U, high =/= 0.U)
      }
    }

  def superpageMisaligned(PTE: UInt, level: UInt,
      PTEConfig: ysyx_26030103_PTEConfig, pageBits: Int, levels: Int,
      vaBits: Int): Bool = {
    if (levels <= 1) false.B
    else {
      val indexBits = indexBitsOf(pageBits, levels, vaBits)
      val allLowerBits = (levels - 1) * indexBits
      val shift = level * indexBits.U
      val mask = ((BigInt(1) << allLowerBits) - 1)
        .U(ptePPNBitsOf(PTEConfig).W) >> shift
      (PTE(PTEConfig.PPNHigh, PTEConfig.PPNLow) & mask) =/= 0.U
    }
  }

  def ppnFits(PTE: UInt, PTEConfig: ysyx_26030103_PTEConfig, pageBits: Int,
      addressWidth: Int): Bool = {
    val ptePPNBits = ptePPNBitsOf(PTEConfig)
    val physicalPPNBits = physicalPPNBitsOf(PTEConfig, pageBits, addressWidth)
    if (physicalPPNBits >= ptePPNBits) true.B
    else PTE(PTEConfig.PPNLow + ptePPNBits - 1,
      PTEConfig.PPNLow + physicalPPNBits) === 0.U
  }

  def ppnValue(PTE: UInt, PTEConfig: ysyx_26030103_PTEConfig, pageBits: Int,
      addressWidth: Int): UInt = {
    val physicalPPNBits = physicalPPNBitsOf(PTEConfig, pageBits, addressWidth)
    PTE(PTEConfig.PPNLow + physicalPPNBits - 1, PTEConfig.PPNLow)
  }

  def walkFault(PTE: UInt, level: UInt,
      PTEConfig: ysyx_26030103_PTEConfig, pteWidth: Int, pageBits: Int,
      levels: Int, vaBits: Int, addressWidth: Int, superpage: Boolean): Bool = {
    val leafAllowed = superpage.B || level === (levels - 1).U
    !PTE(0) || !ppnFits(PTE, PTEConfig, pageBits, addressWidth) ||
      reservedFault(PTE, PTEConfig, pteWidth) ||
      reservedEncoding(PTE) ||
      (isLeaf(PTE) && (!leafAllowed ||
        superpageMisaligned(PTE, level, PTEConfig, pageBits, levels, vaBits))) ||
      (!isLeaf(PTE) && (PTE(6) || PTE(7) || PTE(4))) ||
      (!isLeaf(PTE) && level === (levels - 1).U)
  }

  def leafFaultOf(PTE: UInt, privilege: UInt, access: UInt, sum: Bool,
      mxr: Bool, adMode: String, hasU: Boolean): Bool = {
    val isFetch = access === ysyx_26030103_AccessType.Fetch
    val isStore = access === ysyx_26030103_AccessType.Store
    val userFault =
      if (hasU) privilege === 0.U && !PTE(4) else false.B
    val sumFault =
      if (hasU) privilege === 1.U && PTE(4) && (!sum || isFetch) else false.B
    val fetchFault = isFetch && !PTE(3)
    val readFault = !isFetch && !isStore && !PTE(1) && !(mxr && PTE(3))
    val writeFault = isStore && !PTE(2)
    val adFault = adMode match {
      case "fault" => !PTE(6) || (isStore && !PTE(7))
      case _       => false.B
    }
    reservedEncoding(PTE) ||
      userFault || sumFault || fetchFault || readFault || writeFault || adFault
  }

  def needsAdUpdate(PTE: UInt, isStore: Bool, adMode: String): Bool =
    adMode match {
      case "hardware" => PTE(0) && (!PTE(6) || (isStore && !PTE(7)))
      case _          => false.B
    }

  def vpnIndex(virtualAddress: UInt, level: UInt, pageBits: Int, levels: Int,
      vaBits: Int): UInt = {
    val indexBits = indexBitsOf(pageBits, levels, vaBits)
    MuxLookup(level, virtualAddress(vaBits - 1, pageBits))(
      (0 until levels).map { levelIndex =>
        val lo = pageBits + (levels - 1 - levelIndex) * indexBits
        levelIndex.U -> virtualAddress(lo + indexBits - 1, lo)
      }
    )
  }

  def tableAddress(tableBase: UInt, index: UInt, addressWidth: Int,
      pteWidth: Int): UInt =
    tableBase + (index << pteShiftOf(pteWidth)).pad(addressWidth)

  def superOffsetBits(level: UInt, pageBits: Int, levels: Int,
      vaBits: Int): UInt = {
    val indexBits = indexBitsOf(pageBits, levels, vaBits)
    pageBits.U + ((levels - 1).U - level) * indexBits.U
  }

  def paddrOf(PTE: UInt, level: UInt, virtualAddress: UInt, napot: Bool,
      svnapotSupported: Boolean, PTEConfig: ysyx_26030103_PTEConfig,
      pageBits: Int, levels: Int, vaBits: Int, xlen: Int,
      addressWidth: Int): UInt = {
    val PPN = ppnValue(PTE, PTEConfig, pageBits, addressWidth)
    val shift = superOffsetBits(level, pageBits, levels, vaBits)
    val offsetMask = ((BigInt(1) << vaBits) - 1).U(vaBits.W) >> (vaBits.U - shift)
    val normal = (PPN << pageBits).pad(addressWidth) |
      (virtualAddress & offsetMask).pad(addressWidth)
    if (xlen == 32 || !svnapotSupported) normal
    else {
      val physicalPPNBits = physicalPPNBitsOf(PTEConfig, pageBits, addressWidth)
      val lowMask = ((BigInt(1) << 4) - 1).U(physicalPPNBits.W)
      val napotBase = ((PPN & ~lowMask) << pageBits).pad(addressWidth)
      Mux(napot, napotBase | virtualAddress(15, 0).pad(addressWidth), normal)
    }
  }

  def canonicalOk(virtualAddress: UInt, vaBits: Int, xlen: Int): Bool =
    ysyx_26030103_RISCVFields.CanonicalOk(virtualAddress, vaBits, xlen)

  def satpPPN(satp: UInt, xlen: Int): UInt =
    ysyx_26030103_RISCVFields.SatpPPN(satp, xlen)

  def satpASID(satp: UInt, xlen: Int): UInt =
    ysyx_26030103_RISCVFields.SatpASID(satp, xlen)

  def satpRootFits(satp: UInt, xlen: Int, pageBits: Int,
      addressWidth: Int): Bool = {
    val PPN = satpPPN(satp, xlen)
    val satpPPNBits = satpPPNBitsOf(pageBits, addressWidth)
    if (satpPPNBits >= (if (xlen == 32) 22 else 44)) true.B
    else PPN((if (xlen == 32) 21 else 43), satpPPNBits) === 0.U
  }

  def tableBaseOf(satp: UInt, xlen: Int, pageBits: Int,
      addressWidth: Int): UInt = {
    val satpPPNBits = satpPPNBitsOf(pageBits, addressWidth)
    (satpPPN(satp, xlen)(satpPPNBits - 1, 0) << pageBits).pad(addressWidth)
  }

  def fillLevelOf(level: UInt, levels: Int): UInt = (levels - 1).U - level

  def pageFaultCause(access: UInt): UInt =
    ysyx_26030103_RISCVFields.PageFaultCause(
      access === ysyx_26030103_AccessType.Fetch,
      access === ysyx_26030103_AccessType.Store)

  def accessFaultCause(access: UInt): UInt = {
    val isFetch = access === ysyx_26030103_AccessType.Fetch
    val isStore = access === ysyx_26030103_AccessType.Store
    Mux(isFetch, 1.U, Mux(isStore, 7.U, 5.U))
  }
}

private[mmu] class ysyx_26030103_PTECache(
    enable: Boolean,
    entryCount: Int,
    wayCount: Int,
    addressWidth: Int,
    pteWidth: Int,
    pteShift: Int
) {
  class Entry extends Bundle {
    val valid = Bool()
    val tag = UInt(addressWidth.W)
    val data = UInt(pteWidth.W)
  }

  private val setCount = if (enable) entryCount / wayCount else 1
  private val setBits = if (setCount <= 1) 0 else log2Ceil(setCount)
  private val wayBits = log2Ceil(wayCount.max(2))
  private val entryIndexBits = if (enable) log2Ceil(entryCount) else 1

  private def entryIndex(set: UInt, way: UInt): UInt =
    if (!enable) 0.U
    else (set * wayCount.U + way)(entryIndexBits - 1, 0)

  private val storage = if (enable)
    Some(RegInit(VecInit(Seq.fill(entryCount)(0.U.asTypeOf(new Entry)))))
  else None
  private val roundRobin = if (enable)
    Some(RegInit(VecInit(Seq.fill(setCount)(0.U(wayBits.W)))))
  else None

  private def setOf(address: UInt): UInt =
    if (setBits == 0) 0.U else address(setBits + pteShift - 1, pteShift)

  def lookup(address: UInt): (Bool, UInt) = {
    if (!enable) (false.B, 0.U(pteWidth.W))
    else {
      val set = setOf(address)
      val hits = (0 until wayCount).map { way =>
        val entry = storage.get(entryIndex(set, way.U))
        entry.valid && entry.tag === address
      }
      val data = Mux1H(hits, (0 until wayCount).map { way =>
        storage.get(entryIndex(set, way.U)).data
      })
      (hits.reduce(_ || _), data)
    }
  }

  def fill(address: UInt, data: UInt): Unit = {
    if (enable) {
      val set = setOf(address)
      val index = entryIndex(set, roundRobin.get(set))
      val entry = Wire(new Entry)
      entry := 0.U.asTypeOf(new Entry)
      entry.valid := true.B
      entry.tag := address
      entry.data := data
      storage.get(index) := entry
      roundRobin.get(set) := Mux(
        roundRobin.get(set) === (wayCount - 1).U,
        0.U,
        roundRobin.get(set) + 1.U
      )
    }
  }

  def invalidate(): Unit = {
    if (enable) storage.get.foreach { entry => entry.valid := false.B }
  }
}
