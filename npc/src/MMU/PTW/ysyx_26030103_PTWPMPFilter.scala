package ysyx_26030103.mmu
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.infra.ysyx_26030103_PMP

/** PTW 页表访问的 PMP 过滤层。
  *
  * RISC-V 要求隐式页表访问按 S 权限接受 PMP 检查；PTW 直接接 AXI 时
  * 会绕过 PMP。这里在 PTW 总线与 AXI 适配器之间逐笔检查 PTE 地址，
  * 被拒绝的请求不进入 AXI，直接返回 fault 响应。
  */
class ysyx_26030103_PTWPMPFilter(
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    entries: Int = ysyx_26030103_KconfigDefaults.MmuPmpEntries
) extends Module {
  require(AddressWidth == 32 || AddressWidth == 64,
    s"PTWPMPFilter AddressWidth 非法: $AddressWidth")
  require(entries >= 0 && entries <= 16, "PTWPMPFilter entries 非法")

  val io = IO(new Bundle {
    val in = Flipped(new ysyx_26030103_PTEBusIO(AddressWidth))
    val out = new ysyx_26030103_PTEBusIO(AddressWidth)
    val cfg = Input(Vec(16, UInt(8.W)))
    val addr = Input(Vec(16, UInt(AddressWidth.W)))
  })

  val pmp = Module(new ysyx_26030103_PMP(entries, AddressWidth))
  pmp.io.cfg := io.cfg
  pmp.io.addr := io.addr
  pmp.io.fetchAddr := 0.U
  pmp.io.fetchPriv := 3.U
  pmp.io.dataAddr := io.in.req.bits.addr
  pmp.io.dataLastAddr := io.in.req.bits.addr + 3.U
  pmp.io.dataPriv := 1.U // 隐式页表访问使用 S 权限
  pmp.io.dataRead := !io.in.req.bits.write
  pmp.io.dataWrite := io.in.req.bits.write

  val fault = pmp.io.dataFault
  val faultResp = Module(new Queue(new ysyx_26030103_PTEBusResponse, 8))
  faultResp.io.enq.valid := io.in.req.valid && fault
  faultResp.io.enq.bits.id := io.in.req.bits.id
  faultResp.io.enq.bits.data := 0.U
  faultResp.io.enq.bits.fault := true.B

  io.in.req.ready := Mux(fault, faultResp.io.enq.ready, io.out.req.ready)
  io.out.req.valid := io.in.req.valid && !fault
  io.out.req.bits := io.in.req.bits

  io.in.resp.valid := faultResp.io.deq.valid || io.out.resp.valid
  io.in.resp.bits := Mux(
    faultResp.io.deq.valid,
    faultResp.io.deq.bits,
    io.out.resp.bits
  )
  faultResp.io.deq.ready := io.in.resp.ready && faultResp.io.deq.valid
  io.out.resp.ready := io.in.resp.ready && !faultResp.io.deq.valid
}
