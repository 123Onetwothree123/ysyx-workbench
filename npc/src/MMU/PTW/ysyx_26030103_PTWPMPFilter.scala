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
    entries: Int = ysyx_26030103_KconfigDefaults.MMUPMPEntries,
    faultQueueDepth: Int =
      ysyx_26030103_KconfigDefaults.MMUPTWPMPFaultQueueDepth
) extends Module {
  require(AddressWidth == 32 || AddressWidth == 64,
    s"PTWPMPFilter AddressWidth 非法: $AddressWidth")
  require(entries >= 0 && entries <= 16, "PTWPMPFilter entries 非法")
  require(faultQueueDepth >= 1 && faultQueueDepth <= 16,
    s"PTWPMPFilter faultQueueDepth 非法: $faultQueueDepth")

  val io = IO(new Bundle {
    val in = Flipped(new ysyx_26030103_PTEBusIO(AddressWidth))
    val out = new ysyx_26030103_PTEBusIO(AddressWidth)
    val config = Input(Vec(16, UInt(8.W)))
    val address = Input(Vec(16, UInt(AddressWidth.W)))
  })

  val PMP = Module(new ysyx_26030103_PMP(entries, AddressWidth))
  PMP.io.config := io.config
  PMP.io.address := io.address
  PMP.io.fetchAddress := 0.U
  PMP.io.fetchPriv := 3.U
  PMP.io.dataAddress := io.in.request.bits.address
  PMP.io.dataLastAddress := io.in.request.bits.address + 3.U
  PMP.io.dataPriv := 1.U // 隐式页表访问使用 S 权限
  PMP.io.dataRead := !io.in.request.bits.write
  PMP.io.dataWrite := io.in.request.bits.write

  val fault = PMP.io.dataFault
  val faultResponse = Module(
    new Queue(new ysyx_26030103_PTEBusResponse, faultQueueDepth)
  )
  faultResponse.io.enq.valid := io.in.request.valid && fault
  faultResponse.io.enq.bits.ID := io.in.request.bits.ID
  faultResponse.io.enq.bits.data := 0.U
  faultResponse.io.enq.bits.fault := true.B

  io.in.request.ready := Mux(fault, faultResponse.io.enq.ready, io.out.request.ready)
  io.out.request.valid := io.in.request.valid && !fault
  io.out.request.bits := io.in.request.bits

  io.in.response.valid := faultResponse.io.deq.valid || io.out.response.valid
  io.in.response.bits := Mux(
    faultResponse.io.deq.valid,
    faultResponse.io.deq.bits,
    io.out.response.bits
  )
  faultResponse.io.deq.ready := io.in.response.ready && faultResponse.io.deq.valid
  io.out.response.ready := io.in.response.ready && !faultResponse.io.deq.valid
}
