package ysyx_26030103.mmu

import chisel3._
import chisel3.util._

class ysyx_26030103_PTWRequest extends Bundle {
  val vaddr = UInt(32.W)
  val satp = UInt(32.W)
  val privilege = UInt(2.W)
  val access = UInt(2.W)
  val sum = Bool()
  val mxr = Bool()
}

class ysyx_26030103_PTWResponse extends Bundle {
  val paddr = UInt(32.W)
  val fault = Bool()
  val cause = UInt(32.W)
  val tval = UInt(32.W)
  val fillValid = Bool()
  val fillPte = UInt(32.W)
  val fillVaddr = UInt(32.W)
  val fillAsid = UInt(9.W)
}

/** 标准 Sv32 两级 page table walker。
  *
  * 默认单笔在途 walk；可选 PTE cache 只缓存页表项，不改变 TLB 语义。
  * 多笔在途/流水结构仍由后续包装层扩展。
  */
class ysyx_26030103_PTW(
    adMode: String = "fault",
    cacheEnable: Boolean = false,
    cacheEntries: Int = 16,
    cacheWays: Int = 2
) extends Module {
  require(Set("fault", "ignore").contains(adMode), "PTW 只实现 fault/ignore A/D 模式")
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

  val idle :: req1 :: wait1 :: req0 :: wait0 :: done :: Nil = Enum(6)
  val state = RegInit(idle)

  val vaddrReg = RegInit(0.U(32.W))
  val satpReg = RegInit(0.U(32.W))
  val privReg = RegInit(0.U(2.W))
  val accessReg = RegInit(0.U(2.W))
  val sumReg = RegInit(false.B)
  val mxrReg = RegInit(false.B)
  val pgtblReg = RegInit(0.U(32.W))
  val respReg = RegInit(0.U.asTypeOf(new ysyx_26030103_PTWResponse))
  val fillPulse = RegInit(false.B)

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

  val vpn1 = vaddrReg(31, 22)
  val vpn0 = vaddrReg(21, 12)
  val root = Cat(satpReg(21, 0), 0.U(12.W))
  val pte1Addr = root + (vpn1 << 2)
  val pte0Addr = pgtblReg + (vpn0 << 2)

  def pte1Bad(d: UInt): Bool = !d(0) || d(3, 1) =/= 0.U
  def pte0FaultOf(d: UInt): Bool = {
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
  def pte0Good(d: UInt): Bool = d(0) && !pte0FaultOf(d)

  val (cacheHit1, cacheData1) = cacheLookup(pte1Addr)
  val (cacheHit0, cacheData0) = cacheLookup(pte0Addr)
  val useBus1 = state === req1 && !cacheHit1
  val useBus0 = state === req0 && !cacheHit0

  io.req.ready := state === idle
  io.bus.req.valid := useBus1 || useBus0
  io.bus.req.bits.addr := Mux(state === req1, pte1Addr, pte0Addr)
  io.bus.req.bits.write := false.B
  io.bus.req.bits.data := 0.U
  io.bus.req.bits.strb := 0.U
  io.bus.resp.ready := state === wait1 || state === wait0
  io.resp.valid := state === done
  io.resp.bits := respReg

  val pte1Bus = io.bus.resp.bits.data
  val pte0Bus = io.bus.resp.bits.data

  when(state === idle && io.req.fire) {
    vaddrReg := io.req.bits.vaddr
    satpReg := io.req.bits.satp
    privReg := io.req.bits.privilege
    accessReg := io.req.bits.access
    sumReg := io.req.bits.sum
    mxrReg := io.req.bits.mxr
    state := req1
  }

  switch(state) {
    is(req1) {
      when(cacheHit1) {
        when(pte1Bad(cacheData1)) {
          respReg.paddr := 0.U
          respReg.fault := true.B
          respReg.cause := pageFaultCause
          respReg.tval := vaddrReg
          respReg.fillValid := false.B
          respReg.fillPte := 0.U
          respReg.fillVaddr := 0.U
          respReg.fillAsid := 0.U
          state := done
        }.otherwise {
          pgtblReg := Cat(cacheData1(31, 10), 0.U(12.W))
          state := req0
        }
      }.elsewhen(io.bus.req.ready) { state := wait1 }
    }
    is(wait1) {
      when(io.bus.resp.valid) {
        cacheFill(pte1Addr, pte1Bus)
        when(io.bus.resp.bits.fault || pte1Bad(pte1Bus)) {
          respReg.paddr := 0.U
          respReg.fault := true.B
          respReg.cause := pageFaultCause
          respReg.tval := vaddrReg
          respReg.fillValid := false.B
          respReg.fillPte := 0.U
          respReg.fillVaddr := 0.U
          respReg.fillAsid := 0.U
          state := done
        }.otherwise {
          pgtblReg := Cat(pte1Bus(31, 10), 0.U(12.W))
          state := req0
        }
      }
    }
    is(req0) {
      when(cacheHit0) {
        respReg.paddr := Cat(cacheData0(29, 10), vaddrReg(11, 0))
        respReg.fault := !pte0Good(cacheData0)
        respReg.cause := pageFaultCause
        respReg.tval := vaddrReg
        respReg.fillValid := false.B
        respReg.fillPte := cacheData0
        respReg.fillVaddr := vaddrReg
        respReg.fillAsid := satpReg(30, 22)
        fillPulse := pte0Good(cacheData0)
        state := done
      }.elsewhen(io.bus.req.ready) { state := wait0 }
    }
    is(wait0) {
      when(io.bus.resp.valid) {
        cacheFill(pte0Addr, pte0Bus)
        respReg.paddr := Cat(pte0Bus(29, 10), vaddrReg(11, 0))
        respReg.fault := io.bus.resp.bits.fault || !pte0Good(pte0Bus)
        respReg.cause := pageFaultCause
        respReg.tval := vaddrReg
        respReg.fillValid := false.B
        respReg.fillPte := pte0Bus
        respReg.fillVaddr := vaddrReg
        respReg.fillAsid := satpReg(30, 22)
        fillPulse := pte0Good(pte0Bus) && !io.bus.resp.bits.fault
        state := done
      }
    }
    is(done) {
      fillPulse := false.B
      when(io.resp.ready) { state := idle }
    }
  }

  // TLB 在成功页表项返回后的 done 首拍填充；这是单拍脉冲。
  io.resp.bits.fillValid := fillPulse
}
