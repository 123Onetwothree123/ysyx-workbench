package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile

/** Sv32 MMU：TLB 命中直接返回，miss 走 PTW。
  *
  * 批次 1 是单请求、单 PTW 在途；TLB 可关闭，关闭后每次访问都查页表。
  * 该模块暂不直接连接 IFU/LSU，先用独立单元测试验证地址转换和页错误。
  */
class ysyx_26030103_MMU(
    profile: ysyx_26030103_MMUProfile = ysyx_26030103_MMUProfile()
) extends Module {
  require(
    profile.pageSizeBytes == 4096 && profile.levels == 2 &&
      profile.pteSizeBytes == 4 && profile.pageTableSizeBytes == 4096,
    "批次 1 MMU 只实现标准 Sv32 4KiB/2 级/4B PTE/4KiB 页表"
  )

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new ysyx_26030103_MMURequest))
    val resp = Decoupled(new ysyx_26030103_MMUResponse)
    val bus = new ysyx_26030103_PTEBusIO
    val flush = Input(Bool())
  })

  val idle :: lookup :: walk :: respond :: Nil = Enum(4)
  val state = RegInit(idle)
  val reqReg = RegInit(0.U.asTypeOf(new ysyx_26030103_MMURequest))
  val respReg = RegInit(0.U.asTypeOf(new ysyx_26030103_MMUResponse))

  val enabled = if (profile.enabled) true.B else false.B

  val tlbOpt =
    if (profile.tlbEnabled)
      Some(Module(new ysyx_26030103_TLB(
        entries = if (profile.tlbOrganization == "unified") profile.utlbEntries else profile.itlbEntries,
        ways = if (profile.tlbOrganization == "unified") profile.utlbWays else profile.itlbWays,
        asidBits = profile.tlbAsidBits,
        globalEnable = profile.tlbGlobal,
        replacement = profile.tlbReplacement,
        adMode = profile.adMode
      )))
    else None

  val ptwOpt =
    if (profile.enabled) Some(Module(new ysyx_26030103_PTW(profile.adMode)))
    else None

  io.req.ready := state === idle
  io.resp.valid := state === respond
  io.resp.bits := respReg

  tlbOpt.foreach { tlb =>
    tlb.io.lookup.vaddr := reqReg.vaddr
    tlb.io.lookup.asid := reqReg.satp(30, 22)
    tlb.io.lookup.privilege := reqReg.privilege
    tlb.io.lookup.access := reqReg.access
    tlb.io.lookup.sum := reqReg.sum
    tlb.io.lookup.mxr := reqReg.mxr
    tlb.io.flush := io.flush
  }

  ptwOpt match {
    case Some(ptw) =>
      ptw.io.req.valid := false.B
      ptw.io.req.bits := 0.U.asTypeOf(new ysyx_26030103_PTWRequest)
      ptw.io.resp.ready := state === walk

      // MMU 是 PTW 总线的主设备；这里显式展开，避免两个同方向 Bundle 直接 <>。
      io.bus.req.valid := ptw.io.bus.req.valid
      io.bus.req.bits := ptw.io.bus.req.bits
      ptw.io.bus.req.ready := io.bus.req.ready
      ptw.io.bus.resp.valid := io.bus.resp.valid
      ptw.io.bus.resp.bits := io.bus.resp.bits
      io.bus.resp.ready := ptw.io.bus.resp.ready

      tlbOpt.foreach { tlb =>
        tlb.io.fillValid := ptw.io.resp.valid && ptw.io.resp.bits.fillValid
        tlb.io.fillVaddr := ptw.io.resp.bits.fillVaddr
        tlb.io.fillAsid := ptw.io.resp.bits.fillAsid
        tlb.io.fillPte := ptw.io.resp.bits.fillPte
      }
    case None =>
      io.bus.req.valid := false.B
      io.bus.req.bits := 0.U.asTypeOf(new ysyx_26030103_PTEBusRequest)
      io.bus.resp.ready := false.B
  }

  when(state === idle && io.req.fire) {
    reqReg := io.req.bits
    when(!enabled || io.req.bits.satp(31) === 0.U ||
         io.req.bits.privilege === 3.U) {
      respReg.paddr := io.req.bits.vaddr
      respReg.fault := false.B
      respReg.cause := 0.U
      respReg.tval := io.req.bits.vaddr
      state := respond
    }.otherwise {
      state := lookup
    }
  }

  switch(state) {
    is(lookup) {
      val hit = tlbOpt.map(_.io.result.hit).getOrElse(false.B)
      when(hit) {
        tlbOpt.foreach { tlb =>
          respReg.paddr := tlb.io.result.paddr
          respReg.fault := tlb.io.result.fault
          respReg.cause := tlb.io.result.cause
          respReg.tval := reqReg.vaddr
        }
        state := respond
      }.otherwise {
        ptwOpt.foreach { ptw =>
          ptw.io.req.valid := true.B
          ptw.io.req.bits.vaddr := reqReg.vaddr
          ptw.io.req.bits.satp := reqReg.satp
          ptw.io.req.bits.privilege := reqReg.privilege
          ptw.io.req.bits.access := reqReg.access
          ptw.io.req.bits.sum := reqReg.sum
          ptw.io.req.bits.mxr := reqReg.mxr
          when(ptw.io.req.fire) { state := walk }
        }
      }
    }
    is(walk) {
      ptwOpt.foreach { ptw =>
        when(ptw.io.resp.valid) {
          respReg.paddr := ptw.io.resp.bits.paddr
          respReg.fault := ptw.io.resp.bits.fault
          respReg.cause := ptw.io.resp.bits.cause
          respReg.tval := ptw.io.resp.bits.tval
          state := respond
        }
      }
    }
    is(respond) {
      when(io.resp.ready) { state := idle }
    }
  }
}
