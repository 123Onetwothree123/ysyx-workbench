package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_PteConfig

class ysyx_26030103_PTWRequest extends Bundle {
  val id = UInt(4.W)
  val vaddr = UInt(32.W)
  val satp = UInt(32.W)
  val privilege = UInt(2.W)
  val access = UInt(2.W)
  val sum = Bool()
  val mxr = Bool()
}

class ysyx_26030103_PTWResponse extends Bundle {
  val id = UInt(4.W)
  val paddr = UInt(32.W)
  val fault = Bool()
  val cause = UInt(32.W)
  val tval = UInt(32.W)
  val fillValid = Bool()
  val fillPte = UInt(32.W)
  val fillVaddr = UInt(32.W)
  val fillAsid = UInt(9.W)
  // 0=基础页，1=一级超级页，2=二级超级页……
  val fillLevel = UInt(2.W)
}

/** 可配置页大小/级数/页表项数的 page-table walker。
  *
  * 地址位宽固定 32；要求 pageBits + levels * indexBits == 32。PTE 宽度
  * 当前按 32 位页表项处理，页表大小决定每级索引位数。
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
    fillSuperpage: Boolean = false
) extends Module {
  require(Set("fault", "ignore", "hardware").contains(adMode), "未知 PTW A/D 模式")
  require(pageBits >= 2 && pageBits < 32, "pageBits 非法")
  require(levels >= 1 && levels <= 4, "页表级数必须在 1..4")
  require(tableEntries > 0 && (tableEntries & (tableEntries - 1)) == 0,
    "每级页表项数必须是 2 的幂")
  private val indexBits = log2Ceil(tableEntries)
  require(pageBits + levels * indexBits == 32,
    s"pageBits($pageBits) + levels($levels)*indexBits($indexBits) 必须等于 32")
  if (cacheEnable) {
    require(cacheEntries > 0 && (cacheEntries & (cacheEntries - 1)) == 0,
      "PTW cache entries 必须是 2 的幂")
    require(cacheWays > 0 && cacheEntries % cacheWays == 0,
      "PTW cache ways 必须整除 entries")
    require((cacheWays & (cacheWays - 1)) == 0, "PTW cache ways 必须是 2 的幂")
  }

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new ysyx_26030103_PTWRequest))
    val resp = Decoupled(new ysyx_26030103_PTWResponse)
    val bus = new ysyx_26030103_PTEBusIO
    val flush = Input(Bool())
  })

  val idle :: reqState :: waitState :: writeState :: waitWrite :: done :: Nil = Enum(6)
  val state = RegInit(idle)

  val idReg = RegInit(0.U(4.W))
  val vaddrReg = RegInit(0.U(32.W))
  val satpReg = RegInit(0.U(32.W))
  val privReg = RegInit(0.U(2.W))
  val accessReg = RegInit(0.U(2.W))
  val sumReg = RegInit(false.B)
  val mxrReg = RegInit(false.B)
  val levelReg = RegInit(0.U(2.W))
  val tableReg = RegInit(0.U(32.W))
  val pteAddrReg = RegInit(0.U(32.W))
  val respReg = RegInit(0.U.asTypeOf(new ysyx_26030103_PTWResponse))
  val fillPulse = RegInit(false.B)
  val fillLevelReg = RegInit(0.U(2.W))
  val writeDataReg = RegInit(0.U(32.W))

  class CacheEntry extends Bundle {
    val valid = Bool()
    val tag = UInt(32.W)
    val data = UInt(32.W)
  }
  private val cacheSets = if (cacheEnable) cacheEntries / cacheWays else 1
  private val cacheSetBits = if (cacheSets <= 1) 0 else log2Ceil(cacheSets)
  private val cacheWayBits = log2Ceil(cacheWays.max(2))
  private val cacheIndexBits = if (cacheEnable) log2Ceil(cacheEntries) else 1
  private def cacheIndex(set: UInt, way: UInt): UInt =
    if (!cacheEnable) 0.U
    else (set * cacheWays.U + way)(cacheIndexBits - 1, 0)
  val cache = if (cacheEnable)
    Some(RegInit(VecInit(Seq.fill(cacheEntries)(0.U.asTypeOf(new CacheEntry)))))
  else None
  val cacheRR = if (cacheEnable)
    Some(RegInit(VecInit(Seq.fill(cacheSets)(0.U(cacheWayBits.W)))))
  else None

  private def cacheSetOf(addr: UInt): UInt =
    if (cacheSetBits == 0) 0.U else addr(cacheSetBits + 1, 2)

  def cacheLookup(addr: UInt): (Bool, UInt) = {
    if (!cacheEnable) (false.B, 0.U)
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
    if (cacheEnable) {
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
  val pageFaultCause = Mux(isFetch, 12.U, Mux(isStore, 15.U, 13.U))

  private def normalizePte(d: UInt): UInt = {
    val flags = Cat(
      d(pteConfig.dBit), d(pteConfig.aBit), d(pteConfig.globalBit),
      d(pteConfig.userBit), d(pteConfig.executeBit), d(pteConfig.writeBit),
      d(pteConfig.readBit), d(pteConfig.validBit)
    )
    val ppn = d(pteConfig.ppnHigh, pteConfig.ppnLow)
    Cat(ppn.pad(22)(21, 0), 0.U(2.W), flags)
  }
  private def adRaw(d: UInt): UInt =
    d | (1.U << pteConfig.aBit) |
      Mux(isStore, 1.U << pteConfig.dBit, 0.U(32.W))

  def isLeaf(d: UInt): Bool = d(3, 1) =/= 0.U
  def leafAllowed(d: UInt): Bool = superpage.B || levelReg === (levels - 1).U
  def walkFault(d: UInt): Bool =
    !d(0) || (isLeaf(d) && !leafAllowed(d)) ||
      (!isLeaf(d) && levelReg === (levels - 1).U)
  def leafFaultOf(d: UInt): Bool = {
    val userFault = privReg === 0.U && !d(4)
    val sumFault = privReg === 1.U && d(4) && (!sumReg || isFetch)
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
  def adUpdated(d: UInt): UInt =
    d | (1.U << pteConfig.aBit) |
      Mux(isStore, 1.U << pteConfig.dBit, 0.U(32.W))

  /** 当前级在虚拟地址中的索引；第 0 级是根页表，使用最高 VPN。 */
  def vpnIndex(level: UInt): UInt = {
    val high = pageBits + (levels - 1) * indexBits
    MuxLookup(level, vaddrReg(31, pageBits))(
      (0 until levels).map { l =>
        val lo = pageBits + (levels - 1 - l) * indexBits
        l.U -> vaddrReg(lo + indexBits - 1, lo)
      }
    )
  }
  def tableAddress(base: UInt, index: UInt): UInt =
    base + (index << log2Ceil(4))

  def superOffsetBits(level: UInt): UInt =
    pageBits.U + ((levels - 1).U - level) * indexBits.U
  def paddrOf(d: UInt, level: UInt): UInt = {
    val ppn = d(31, 10)
    val shift = superOffsetBits(level)
    val offsetMask = (1.U << shift) - 1.U
    (ppn << pageBits) | (vaddrReg & offsetMask)
  }

  val nextPteAddr = tableAddress(tableReg, vpnIndex(levelReg))
  val (cacheHit, cacheData) = cacheLookup(nextPteAddr)
  val useBus = state === reqState && !cacheHit
  io.req.ready := state === idle
  io.bus.req.valid := useBus || state === writeState
  io.bus.req.bits.addr := Mux(state === writeState, pteAddrReg,
    Mux(state === reqState, nextPteAddr, pteAddrReg))
  io.bus.req.bits.write := state === writeState
  io.bus.req.bits.data := Mux(state === writeState, writeDataReg, 0.U)
  io.bus.req.bits.strb := Mux(state === writeState, "hf".U(4.W), 0.U)
  io.bus.resp.ready := state === waitState || state === waitWrite
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

  when(state === idle && io.req.fire) {
    idReg := io.req.bits.id
    vaddrReg := io.req.bits.vaddr
    satpReg := io.req.bits.satp
    privReg := io.req.bits.privilege
    accessReg := io.req.bits.access
    sumReg := io.req.bits.sum
    mxrReg := io.req.bits.mxr
    levelReg := 0.U
    tableReg := Cat(io.req.bits.satp(21, 0), 0.U(pageBits.W))
    pteAddrReg := 0.U
    state := reqState
  }

  switch(state) {
    is(reqState) {
      // 计算当前级页表项地址。
      pteAddrReg := nextPteAddr
      when(cacheHit) {
        when(walkFault(cacheData)) {
          setFault()
        }.elsewhen(isLeaf(cacheData)) {
          respReg.paddr := paddrOf(cacheData, levelReg)
          respReg.fault := leafFaultOf(cacheData)
          respReg.cause := pageFaultCause
          respReg.tval := vaddrReg
          respReg.fillPte := cacheData
          respReg.fillVaddr := vaddrReg
          respReg.fillAsid := satpReg(30, 22)
          fillLevelReg := levelReg
          respReg.fillValid := false.B
          fillPulse := !leafFaultOf(cacheData) &&
            (fillSuperpage.B || levelReg === (levels - 1).U)
          state := done
        }.otherwise {
          tableReg := Cat(cacheData(31, 10), 0.U(pageBits.W))
          levelReg := levelReg + 1.U
          state := reqState
        }
      }.elsewhen(io.bus.req.ready) {
        state := waitState
      }
    }
    is(waitState) {
      when(io.bus.resp.valid) {
        cacheFill(pteAddrReg, normalizePte(io.bus.resp.bits.data))
        val d = normalizePte(io.bus.resp.bits.data)
        when(io.bus.resp.bits.fault || walkFault(d)) {
          setFault()
        }.elsewhen(isLeaf(d)) {
          when(needsAdUpdate(d)) {
            writeDataReg := adRaw(io.bus.resp.bits.data)
            state := writeState
          }.otherwise {
            respReg.paddr := paddrOf(d, levelReg)
            respReg.fault := leafFaultOf(d)
            respReg.cause := pageFaultCause
            respReg.tval := vaddrReg
            respReg.fillPte := d
            respReg.fillVaddr := vaddrReg
            respReg.fillAsid := satpReg(30, 22)
            fillLevelReg := levelReg
            respReg.fillValid := false.B
            fillPulse := !leafFaultOf(d) &&
              (fillSuperpage.B || levelReg === (levels - 1).U)
            state := done
          }
        }.otherwise {
          tableReg := Cat(d(31, 10), 0.U(pageBits.W))
          levelReg := levelReg + 1.U
          state := reqState
        }
      }
    }
    is(writeState) {
      when(io.bus.req.ready) { state := waitWrite }
    }
    is(waitWrite) {
      when(io.bus.resp.valid) {
        val written = normalizePte(writeDataReg)
        cacheFill(pteAddrReg, written)
        respReg.paddr := paddrOf(written, levelReg)
        respReg.fault := io.bus.resp.bits.fault || leafFaultOf(written)
        respReg.cause := pageFaultCause
        respReg.tval := vaddrReg
        respReg.fillPte := written
        respReg.fillVaddr := vaddrReg
        respReg.fillAsid := satpReg(30, 22)
        fillLevelReg := levelReg
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
