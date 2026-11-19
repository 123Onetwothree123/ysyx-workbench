package ysyx_26030103.mmu
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults

import chisel3._
import chisel3.util._

// IFU/LSU 双端口 MMU 仲裁器：每个客户端最多一笔在途翻译，响应按请求 ID
// 回到对应客户端，不要求 IFU 先完成。
class ysyx_26030103_MMURequestArbiter(
    XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth
) extends Module {
  val io = IO(new Bundle {
    val iRequest = Flipped(Decoupled(new ysyx_26030103_MMURequest(XLEN, AddressWidth)))
    val iResponse = Decoupled(new ysyx_26030103_MMUResponse(XLEN, AddressWidth))
    val dRequest = Flipped(Decoupled(new ysyx_26030103_MMURequest(XLEN, AddressWidth)))
    val dResponse = Decoupled(new ysyx_26030103_MMUResponse(XLEN, AddressWidth))
    val MMURequest = Decoupled(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
    val MMUResponse = Flipped(Decoupled(new ysyx_26030103_MMUResponse(XLEN, AddressWidth)))
    // TLB 全量刷新会清空 MMU 内所有在途翻译且不再回响应；仲裁器必须同步
    // 清掉 outstanding，否则客户端永远等不到翻译结果。
    val flush = Input(Bool())
  })

  val iOutstanding = RegInit(false.B)
  val dOutstanding = RegInit(false.B)
  io.iRequest.ready := false.B
  io.dRequest.ready := false.B
  io.MMURequest.valid := false.B
  io.MMURequest.bits := 0.U.asTypeOf(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
  io.iResponse.valid := false.B
  io.iResponse.bits := 0.U.asTypeOf(new ysyx_26030103_MMUResponse(XLEN, AddressWidth))
  io.dResponse.valid := false.B
  io.dResponse.bits := 0.U.asTypeOf(new ysyx_26030103_MMUResponse(XLEN, AddressWidth))
  io.MMUResponse.ready := false.B

  // 选择一笔尚未在途的客户端请求送入 MMU。flush 当拍不接收新请求，
  // 避免请求刚发出就被清空却没有任何响应。
  val chooseData = !io.flush && !dOutstanding && io.dRequest.valid &&
    (iOutstanding || !io.iRequest.valid)
  val chooseInstruction = !io.flush && !iOutstanding && io.iRequest.valid && !chooseData
  when(chooseData) {
    io.dRequest.ready := io.MMURequest.ready
    io.MMURequest.valid := io.dRequest.valid
    io.MMURequest.bits := io.dRequest.bits
    when(io.dRequest.fire) { dOutstanding := true.B }
  }.elsewhen(chooseInstruction) {
    io.iRequest.ready := io.MMURequest.ready
    io.MMURequest.valid := io.iRequest.valid
    io.MMURequest.bits := io.iRequest.bits
    when(io.iRequest.fire) { iOutstanding := true.B }
  }

  // MMU 响应携带客户端 ID：0=IFU，1=LSU。
  when(io.MMUResponse.valid) {
    when(io.MMUResponse.bits.ID === 0.U) {
      io.iResponse.valid := true.B
      io.iResponse.bits := io.MMUResponse.bits
      io.MMUResponse.ready := io.iResponse.ready
      when(io.iResponse.fire) { iOutstanding := false.B }
    }.otherwise {
      io.dResponse.valid := true.B
      io.dResponse.bits := io.MMUResponse.bits
      io.MMUResponse.ready := io.dResponse.ready
      when(io.dResponse.fire) { dOutstanding := false.B }
    }
  }
  // MMU 在 flush 时直接丢弃在途翻译，不会回响应；这里同步撤销
  // outstanding，让客户端可以在下一拍重新发起翻译。
  when(io.flush) {
    iOutstanding := false.B
    dOutstanding := false.B
  }
}
