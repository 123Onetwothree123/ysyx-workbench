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
  // 0=基础页，k=第 k 级超级页；TLB 用 pageBits + k*indexBits 推导 shift。
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
    fillSuperpage: Boolean = false,
    pteSizeBytes: Int = 4,
    hasU: Boolean = true,
    // 该 walker 在页表总线上的固定事务 ID；多 walker 簇用 walker 编号，
    // 单 walker/直连测试保持 0。
    busId: Int = 0
) extends Module {
  require(Set("fault", "ignore", "hardware").contains(adMode), "未知 PTW A/D 模式")
  require(busId >= 0 && busId <= 15, "PTW busId 必须在 0..15")
  require(pageBits >= 2 && pageBits < 32, "pageBits 非法")
  require(levels >= 1 && levels <= 4, "页表级数必须在 1..4")
  require(tableEntries > 0 && (tableEntries & (tableEntries - 1)) == 0,
    "每级页表项数必须是 2 的幂")
  require(pteSizeBytes == 4 || pteSizeBytes == 8, "PTE 大小只支持 4/8 字节")
  private val pteShift = log2Ceil(pteSizeBytes)
  private val pteBytes = pteSizeBytes
  private val pteWidth = pteBytes * 8
  // 4B/8B PTE 都可以进入 cache：8B 表项保存低/高两个 32 位字，命中时
  // 不再产生两次页表读。
  private val cacheEffective = cacheEnable
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

  val idle :: reqState :: waitState :: reqHigh :: waitHigh :: writeState :: waitWrite :: done :: Nil = Enum(8)
  val state = RegInit(idle)

  val idReg = RegInit(0.U(4.W))
  val vaddrReg = RegInit(0.U(32.W))
  val satpReg = RegInit(0.U(32.W))
  // HAS_U=n 时 PTW 不需要保存/检查 U 特权级和 SUM；U/S 权限检查固定
  // 不适用，只保留 R/W/X/A/D 检查。
  val privRegOpt = if (hasU) Some(RegInit(0.U(2.W))) else None
  val accessReg = RegInit(0.U(2.W))
  val sumRegOpt = if (hasU) Some(RegInit(false.B)) else None
  val mxrReg = RegInit(false.B)
  val levelReg = RegInit(0.U(2.W))
  val tableReg = RegInit(0.U(32.W))
  val pteAddrReg = RegInit(0.U(32.W))
  val pteLowReg = RegInit(0.U(32.W))
  val respReg = RegInit(0.U.asTypeOf(new ysyx_26030103_PTWResponse))
  val fillPulse = RegInit(false.B)
  val fillLevelReg = RegInit(0.U(2.W))
  val writeDataReg = RegInit(0.U(32.W))

  class CacheEntry extends Bundle {
    val valid = Bool()
    val tag = UInt(32.W)
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
    base + (index << pteShift)

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
  io.bus.req.valid := useBus || state === reqHigh || state === writeState
  // 单 walker 同时只有一笔页表事务；ID 在簇内唯一，用于响应回收。
  io.bus.req.bits.id := busId.U(4.W)
  io.bus.req.bits.addr := Mux(state === writeState, pteAddrReg,
    Mux(state === reqState, nextPteAddr,
      Mux(state === reqHigh, pteAddrReg + 4.U, pteAddrReg)))
  io.bus.req.bits.write := state === writeState
  io.bus.req.bits.data := Mux(state === writeState, writeDataReg, 0.U)
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

  def processPte(d: UInt, raw: UInt, busFault: Bool): Unit = {
    // cache 保存原始 PTE；4B 时 raw 就是 32 位字，8B 时是低/高字拼接。
    when(cacheEffective.B) { cacheFill(pteAddrReg, raw) }
    when(busFault || walkFault(d)) {
      setFault()
    }.elsewhen(isLeaf(d)) {
      when(needsAdUpdate(d)) {
        writeDataReg := adRaw(raw)
        state := writeState
      }.otherwise {
        respReg.paddr := paddrOf(d, levelReg)
        respReg.fault := leafFaultOf(d)
        respReg.cause := pageFaultCause
        respReg.tval := vaddrReg
        respReg.fillPte := d
        respReg.fillVaddr := vaddrReg
        respReg.fillAsid := satpReg(30, 22)
        fillLevelReg := fillLevelOf(levelReg)
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

  when(state === idle && io.req.fire) {
    idReg := io.req.bits.id
    vaddrReg := io.req.bits.vaddr
    satpReg := io.req.bits.satp
    privRegOpt.foreach { r => r := io.req.bits.privilege }
    accessReg := io.req.bits.access
    sumRegOpt.foreach { r => r := io.req.bits.sum }
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
        // cache 中保存的是原始 PTE，命中路径与总线读路径一样先归一化。
        val cachePte = normalizePte(cacheData)
        when(walkFault(cachePte)) {
          setFault()
        }.elsewhen(isLeaf(cachePte)) {
          respReg.paddr := paddrOf(cachePte, levelReg)
          respReg.fault := leafFaultOf(cachePte)
          respReg.cause := pageFaultCause
          respReg.tval := vaddrReg
          respReg.fillPte := cachePte
          respReg.fillVaddr := vaddrReg
          respReg.fillAsid := satpReg(30, 22)
          fillLevelReg := fillLevelOf(levelReg)
          respReg.fillValid := false.B
          fillPulse := !leafFaultOf(cachePte) &&
            (fillSuperpage.B || levelReg === (levels - 1).U)
          state := done
        }.otherwise {
          tableReg := Cat(cachePte(31, 10), 0.U(pageBits.W))
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
        // 写回只更新低 32 位（A/D 位在低字）；cache 保存原始 PTE，
        // 8B 时高字补零，不影响当前 PTE 字段编码语义。
        if (pteWidth > 32) {
          cacheFill(pteAddrReg, Cat(0.U((pteWidth - 32).W), writeDataReg))
        } else {
          cacheFill(pteAddrReg, writeDataReg)
        }
        respReg.paddr := paddrOf(written, levelReg)
        respReg.fault := io.bus.resp.bits.fault || leafFaultOf(written)
        respReg.cause := pageFaultCause
        respReg.tval := vaddrReg
        respReg.fillPte := written
        respReg.fillVaddr := vaddrReg
        respReg.fillAsid := satpReg(30, 22)
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
