package ysyx_26030103.mmu
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults

import chisel3._
import chisel3.util._

/** 取指地址翻译。PIPT 下由顶层用 translationValid 门控 ICache.FetchValid；
  * VIPT（ParallelVIPT=true）下翻译结果附带请求 VA 作为标签，ICache 仅标签
  * 匹配时才接收 PA，取指 MMU 不再跑在已受理请求之前。
  */
class ysyx_26030103_IFUMMU(
    ParallelVIPT: Boolean = false,
    XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth
) extends Module {
  require(XLEN == 32 || XLEN == 64, s"IFUMMU XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val fetchValid = Input(Bool())
    val fetchReady = Output(Bool())
    val fetchVirtualAddress = Input(UInt(XLEN.W))
    val fetchPhysicalAddress = Output(UInt(AddressWidth.W))
    // 该 PA 对应的虚拟地址。VIPT 下 ICache 用它校验“迟到的 PA”属于
    // 当前已受理的请求，而不是上一笔/错路请求。
    val fetchPhysicalAddressVirtualAddress = Output(UInt(XLEN.W))
    val translationValid = Output(Bool())
    val cacheReady = Input(Bool())
    val fault = Output(Bool())
    val faultCause = Output(UInt(4.W))
    val faultVirtualAddress = Output(UInt(XLEN.W))
    val PMPFault = Input(Bool())
    val privilege = Input(UInt(2.W))
    val satp = Input(UInt(XLEN.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())
    val flush = Input(Bool())
    val request = Decoupled(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
    val response = Flipped(Decoupled(new ysyx_26030103_MMUResponse(XLEN, AddressWidth)))
  })
  val idle :: xwait :: done :: fault :: discard :: Nil = Enum(5)
  val state = RegInit(idle)
  val physicalAddressReg = RegInit(0.U(AddressWidth.W))
  val physicalAddressVirtualAddressReg = RegInit(0.U(XLEN.W))
  val requestVirtualAddressReg = RegInit(0.U(XLEN.W))
  val faultReg = RegInit(false.B)
  val faultCauseReg = RegInit(0.U(4.W))
  val faultVirtualAddressReg = RegInit(0.U(XLEN.W))
  // RV64 satp.MODE 在 bit 63:60；RV32 在 bit 31。
  private def satpMode(satp: UInt): UInt =
    if (XLEN == 32) satp(31) else satp(63, 60)
  val bypass = satpMode(io.satp) === 0.U || io.privilege === 3.U

  io.request.valid := false.B
  io.request.bits := 0.U.asTypeOf(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
  // flush 后旧 walk 的响应仍可能返回；非等待状态也要接收并丢弃，避免
  // MMU 请求仲裁器卡在响应转发状态。
  io.response.ready := true.B
  io.fetchPhysicalAddress := Mux(bypass, io.fetchVirtualAddress, physicalAddressReg)
  io.fetchPhysicalAddressVirtualAddress := Mux(bypass, io.fetchVirtualAddress, physicalAddressVirtualAddressReg)
  // 重定向后 IFU 的虚拟 PC 已改变，即使 flush 晚一拍，旧 paddr 也绝不能
  // 再当作新 PC 的物理地址。PMP 必须在当拍门控 translationValid（顶层用它
  // 直接门控 ICache.FetchValid），晚一拍撤销就已经受理了故障取指。
  io.translationValid :=
    (bypass && state === idle && !io.PMPFault) ||
      (state === done && physicalAddressVirtualAddressReg === io.fetchVirtualAddress && !io.PMPFault)
  io.fetchReady := io.translationValid && io.cacheReady
  io.fault := faultReg
  io.faultCause := faultCauseReg
  io.faultVirtualAddress := faultVirtualAddressReg

  when(io.flush) {
    state := idle
    faultReg := false.B
  }.otherwise {
    switch(state) {
      is(idle) {
        when(io.fetchValid) {
          when(bypass) {
            // Bare/M 模式不做页表翻译，但 PMP 仍必须生效。故障取指
            // 不能进入 ICache，也不能进入 done（没有翻译响应可等）。
            when(io.PMPFault) {
              faultReg := true.B
              faultCauseReg := 1.U
              faultVirtualAddressReg := io.fetchVirtualAddress
              state := fault
            }
          }.otherwise {
            io.request.valid := true.B
            io.request.bits.ID := 0.U // IFU
            io.request.bits.vaddr := io.fetchVirtualAddress
            io.request.bits.access := ysyx_26030103_AccessType.Fetch
            io.request.bits.privilege := io.privilege
            io.request.bits.sum := io.sum
            io.request.bits.mxr := io.mxr
            io.request.bits.satp := io.satp
            when(io.request.fire) {
              requestVirtualAddressReg := io.fetchVirtualAddress
              state := xwait
            }
          }
        }
      }
      is(xwait) {
        when(io.response.valid) {
          // 响应只带原始 VA(tval)，不校验的话 redirect/flush 后迟到的旧 walk
          // 会把旧 PA 当成当前 PC 的翻译结果。
          when(io.response.bits.tval =/= requestVirtualAddressReg) {
            // 丢弃迟到响应，继续等待本请求的翻译结果。
          }.elsewhen(io.response.bits.fault) {
            faultReg := true.B
            faultCauseReg := io.response.bits.cause(3, 0)
            faultVirtualAddressReg := requestVirtualAddressReg
            state := fault
          }.otherwise {
            physicalAddressReg := io.response.bits.paddr
            physicalAddressVirtualAddressReg := requestVirtualAddressReg
            state := done
          }
        }
      }
      is(done) {
        when(ParallelVIPT.B && io.fetchValid &&
            physicalAddressVirtualAddressReg =/= io.fetchVirtualAddress) {
          // VIPT: 已完成的翻译属于旧请求，直接丢弃，下一拍重新为当前
          // ICache 请求发起翻译。
          state := idle
        }.elsewhen(io.PMPFault) {
          faultReg := true.B
          faultCauseReg := 1.U
          faultVirtualAddressReg := physicalAddressVirtualAddressReg
          state := fault
        }.elsewhen(!ParallelVIPT.B && io.fetchValid && io.cacheReady &&
             physicalAddressVirtualAddressReg === io.fetchVirtualAddress) { state := idle }
        // VIPT 下只有 ICache 消费 PA 后 fetchValid 才会撤销；不能因为
        // cacheReady 提前回 idle，否则会对同一请求重复发起翻译。
        when(!io.fetchValid && !io.PMPFault) { state := idle }
      }
      is(fault) {
        // 保持故障直到 EXU 的 trap 冲刷；faultReg 是单拍事件，避免重复注入。
        faultReg := false.B
      }
      is(discard) {
        when(io.response.valid) { state := idle }
      }
    }
    when(state === xwait && io.fetchValid === false.B) {
      state := discard
    }
  }
}
