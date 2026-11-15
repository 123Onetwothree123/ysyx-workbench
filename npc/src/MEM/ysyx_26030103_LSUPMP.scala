package ysyx_26030103.mem

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._

private[mem] trait LSUPMP {
  this: ysyx_26030103_LSU =>

  def dCacheBusBody(): Unit = {
    if (DCacheEnable) {
      when(dataCacheBusActive) {
        io.DataBus.AR.ARVALID := DCache.io.AXI.AR.ARVALID
        io.DataBus.AR.ARID := DCache.io.AXI.AR.ARID
        io.DataBus.AR.ARADDR := DCache.io.AXI.AR.ARADDR
        io.DataBus.AR.ARLEN := DCache.io.AXI.AR.ARLEN
        io.DataBus.AR.ARSIZE := DCache.io.AXI.AR.ARSIZE
        io.DataBus.AR.ARBURST := DCache.io.AXI.AR.ARBURST
        io.DataBus.AR.ARPROT := DCache.io.AXI.AR.ARPROT
        io.DataBus.AR.ARCACHE := DCache.io.AXI.AR.ARCACHE
        io.DataBus.AR.ARQOS := DCache.io.AXI.AR.ARQOS
      }
      DCache.io.AXI.AR.ARREADY := Mux(
        dataCacheBusActive,
        io.DataBus.AR.ARREADY,
        false.B
      )
      DCache.io.AXI.R.RID := io.DataBus.R.RID
      DCache.io.AXI.R.RDATA := io.DataBus.R.RDATA
      DCache.io.AXI.R.RRESP := io.DataBus.R.RRESP
      DCache.io.AXI.R.RLAST := io.DataBus.R.RLAST
      DCache.io.AXI.R.RVALID := io.DataBus.R.RVALID
      when(dataCacheBusActive) {
        io.DataBus.R.RREADY := DCache.io.AXI.R.RREADY
      }
    } else {
      DCache.io.AXI.AR.ARREADY := false.B
      DCache.io.AXI.R.RID := 0.U
      DCache.io.AXI.R.RDATA := 0.U
      DCache.io.AXI.R.RRESP := 0.U
      DCache.io.AXI.R.RLAST := false.B
      DCache.io.AXI.R.RVALID := false.B
    }
  }

  def pmpAndStallBody(): Unit = {
    // reservation 失效规则：复位、任何同 hart store 提交（含 AMO/SC）、
    // 精确异常或 CSR 提交（上下文切换）都会清除。放在 FSM 之后，
    // 保证清除优先于同拍的 LR 建立。
    when(io.ReservationClear || wbPop || memTrapCommit) {
      reservationValid := false.B
    }
    io.Active := state =/= stateIdle
    // 无 MMU 的 PMP 检查对象：写缓冲非空时是队首 store（唯一可能在本拍
    // 产生写副作用的项），否则是本拍即将受理的访存指令。store 在写缓冲
    // 中始终保持架构顺序，因此队首地址也对应最老未完成 store。
    val pmpBase =
      Mux(!wbufEmpty, wbHeadEffectiveAddress, addressOf(stageInputBits.ALUResult))
    // 宽度编码与架构 WidthSelect 一致：0/1/2/3 = 1/2/4/8 字节。写缓冲
    // 队首的 wbHeadSize 是 AXI size（8B 访问仍编码为 2），须用 wbHeadWide
    // 单独区分 8 字节访问。
    val pmpWidth = Mux(
      !wbufEmpty,
      Mux(wbHeadWide, 3.U, wbHeadSize),
      stageInputBits.WidthSelect
    )
    val pmpLastOffset = MuxLookup(pmpWidth, 0.U(AddressWidth.W))(
      Seq(
        0.U -> 0.U(AddressWidth.W),
        1.U -> 1.U(AddressWidth.W),
        2.U -> 3.U(AddressWidth.W),
        3.U -> 7.U(AddressWidth.W)
      )
    )
    io.PMPAddress := pmpBase
    io.PMPLastAddress := pmpBase + pmpLastOffset
    io.PMPWrite := Mux(!wbufEmpty, true.B, stageInputBits.MemoryWrite)
    // AMO 同时检查读/写权限；LR 只读，SC/普通 store 只写。
    io.PMPRead := Mux(
      !wbufEmpty,
      false.B,
      !stageInputBits.MemoryWrite ||
        (stageInputBits.IsAtomic &&
          !ysyx_26030103_AtomicOp.IsSc(stageInputBits.AtomicOp))
    )
    io.IsStore := isStoreTransaction
    io.StallReadAR := state === stateReadRequest
    io.StallReadR := state === stateReadResponse || state === stateReadDrain
    io.StallWriteRequest := wbufFull && io.in.valid &&
      io.in.bits.MemoryValid && io.in.bits.MemoryWrite && !io.in.ready
    // 等 B 时仍可继续吸收连续 store；其它指令为保持精确异常顺序而等待。
    io.StallWriteB := wbState === wbWaitB
    // VIPT 并行 load 的 MMU 翻译故障：LSU 已经先于 PA 受理该 load，
    // 这里必须撤销流水级和 DCache 等待请求，不能把故障 load 当作正常
    // load 退休；精确 page fault 由顶层 LSUMMU 的 faultCommit 提交。
    if (dataVIPTParallel) {
      when(io.VIPTKill) {
        // 翻译故障发生在 store 已进入写缓冲、但 PA 尚未确认的阶段。此时
        // 队首绝不可能已经发过 AW/W（由 wbHeadReady 门控），因此可以连同
        // 已经排队的年轻 store 一起整体撤销，保持精确异常。
        stageState := stageIdle
        dataCacheRequestActive := false.B
        state := stateIdle
        misalignedReg := false.B
        memFaultReg := false.B
        accessFaultReg := false.B
        accessFaultResponseReg := 0.U
        storeFaultReg := false.B
        isStoreTransaction := false.B
        pendingStoreCount := 0.U
        pendingStoreHead := 0.U
        pendingStoreTail := 0.U
        wbCount := 0.U
        wbHead := 0.U
        wbTail := 0.U
        wbState := wbIdle
        wbAWDone := false.B
        wbWDone := false.B
        atomicActive := false.B
        atomicReadFaultReg := false.B
        reservationValid := false.B
        wbPhysicalAddressValid.foreach(_ := false.B)
      }
    }
  }
}
