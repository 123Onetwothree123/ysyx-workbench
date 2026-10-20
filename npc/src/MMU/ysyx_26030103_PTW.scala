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
  * 当前批次实现单笔在途 walk；多笔在途/流水结构由后续的 PTW 包装层扩展，
  * 但这个模块的外部请求/响应和 page fault 语义保持稳定。
  */
class ysyx_26030103_PTW(adMode: String = "fault") extends Module {
  require(Set("fault", "hardware", "ignore").contains(adMode), "未知 A/D 模式")

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new ysyx_26030103_PTWRequest))
    val resp = Decoupled(new ysyx_26030103_PTWResponse)
    val bus = new ysyx_26030103_PTEBusIO
  })

  val idle :: req1 :: wait1 :: req0 :: wait0 :: done :: Nil = Enum(6)
  val state = RegInit(idle)

  val vaddrReg = RegInit(0.U(32.W))
  val satpReg = RegInit(0.U(32.W))
  val privReg = RegInit(0.U(2.W))
  val accessReg = RegInit(0.U(2.W))
  val sumReg = RegInit(false.B)
  val mxrReg = RegInit(false.B)
  val pte1Reg = RegInit(0.U(32.W))
  val pgtblReg = RegInit(0.U(32.W))
  val respReg = RegInit(0.U.asTypeOf(new ysyx_26030103_PTWResponse))
  val fillPulse = RegInit(false.B)

  val isFetch = accessReg === ysyx_26030103_AccessType.Fetch
  val isStore = accessReg === ysyx_26030103_AccessType.Store
  val pageFaultCause = Mux(isFetch, 12.U, Mux(isStore, 15.U, 13.U))

  val vpn1 = vaddrReg(31, 22)
  val vpn0 = vaddrReg(21, 12)
  val root = Cat(satpReg(21, 0), 0.U(12.W))
  val pte1Addr = root + (vpn1 << 2)
  val pte0Addr = pgtblReg + (vpn0 << 2)

  io.req.ready := state === idle
  io.bus.req.valid := state === req1 || state === req0
  io.bus.req.bits.addr := Mux(state === req1, pte1Addr, pte0Addr)
  io.bus.req.bits.write := false.B
  io.bus.req.bits.data := 0.U
  io.bus.req.bits.strb := 0.U
  io.bus.resp.ready := state === wait1 || state === wait0
  io.resp.valid := state === done
  io.resp.bits := respReg

  val pte1 = io.bus.resp.bits.data
  val pte0 = io.bus.resp.bits.data
  val pte1Valid = pte1(0)
  val pte1Leaf = pte1(3, 1) =/= 0.U
  val pte0Valid = pte0(0)
  val pte0Access = pte0(3, 1)

  val userFault = privReg === 0.U && !pte0(4)
  val sumFault = privReg === 1.U && pte0(4) && (!sumReg || isFetch)
  val fetchFault = isFetch && !pte0(3)
  val readFault = !isFetch && !isStore && !pte0(1) && !(mxrReg && pte0(3))
  val writeFault = isStore && !pte0(2)
  val adFault = adMode match {
    case "fault" => !pte0(6) || (isStore && !pte0(7))
    case _       => false.B
  }
  val pte0Fault = userFault || sumFault || fetchFault || readFault || writeFault || adFault

  val fillFire = (state === wait0) && io.bus.resp.valid &&
    pte0Valid && !io.bus.resp.bits.fault && !pte0Fault

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
      when(io.bus.req.ready) { state := wait1 }
    }
    is(wait1) {
      when(io.bus.resp.valid) {
        pte1Reg := io.bus.resp.bits.data
        pgtblReg := Cat(io.bus.resp.bits.data(31, 10), 0.U(12.W))
        when(io.bus.resp.bits.fault || !pte1Valid || pte1Leaf) {
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
          state := req0
        }
      }
    }
    is(req0) {
      when(io.bus.req.ready) { state := wait0 }
    }
    is(wait0) {
      when(io.bus.resp.valid) {
        respReg.paddr := Cat(io.bus.resp.bits.data(29, 10), vaddrReg(11, 0))
        respReg.fault := io.bus.resp.bits.fault || !pte0Valid || pte0Fault
        respReg.cause := pageFaultCause
        respReg.tval := vaddrReg
        respReg.fillValid := false.B
        fillPulse := fillFire
        respReg.fillPte := io.bus.resp.bits.data
        respReg.fillVaddr := vaddrReg
        respReg.fillAsid := satpReg(30, 22)
        state := done
      }
    }
    is(done) {
      fillPulse := false.B
      when(io.resp.ready) { state := idle }
    }
  }

  // TLB 在 PTE0 成功返回的下一拍（done 首拍）填充；这是单拍脉冲。
  io.resp.bits.fillValid := fillPulse
}
