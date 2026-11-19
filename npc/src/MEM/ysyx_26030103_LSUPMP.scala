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
    when(io.ReservationClear || wbPop || memTrapCommit) {
      reservationValid := false.B
    }
    io.Active := state =/= stateIdle
    val PMPBase =
      Mux(!WBufEmpty, wbHeadEffectiveAddress, addressOf(stageInputBits.ALUResult))
    val PMPWidth = Mux(
      !WBufEmpty,
      Mux(wbHeadWide, 3.U, wbHeadSize),
      stageInputBits.WidthSelect
    )
    val PMPLastOffset = MuxLookup(PMPWidth, 0.U(AddressWidth.W))(
      Seq(
        0.U -> 0.U(AddressWidth.W),
        1.U -> 1.U(AddressWidth.W),
        2.U -> 3.U(AddressWidth.W),
        3.U -> 7.U(AddressWidth.W)
      )
    )
    io.PMPAddress := PMPBase
    io.PMPLastAddress := PMPBase + PMPLastOffset
    io.PMPWrite := Mux(!WBufEmpty, true.B, stageInputBits.MemoryWrite)
    // AMO 同时检查读/写权限；LR 只读，SC/普通 store 只写。
    io.PMPRead := Mux(
      !WBufEmpty,
      false.B,
      !stageInputBits.MemoryWrite ||
        (stageInputBits.IsAtomic &&
          !ysyx_26030103_AtomicOp.IsSc(stageInputBits.AtomicOp))
    )
    io.IsStore := isStoreTransaction
    io.StallReadAR := state === stateReadRequest
    io.StallReadR := state === stateReadResponse || state === stateReadDrain
    io.StallWriteRequest := WBufFull && io.in.valid &&
      io.in.bits.MemoryValid && io.in.bits.MemoryWrite && !io.in.ready
    // 等 B 时仍可继续吸收连续 store；其它指令为保持精确异常顺序而等待。
    io.StallWriteB := wbState === wbWaitB
    if (dataVIPTParallel) {
      // VIPT 翻译故障整体撤销已排队 store。
      when(io.VIPTKill) {
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
        WbAWDone := false.B
        wbWDone := false.B
        atomicActive := false.B
        atomicReadFaultReg := false.B
        reservationValid := false.B
        wbPhysicalAddressValid.foreach(_ := false.B)
      }
    }
  }
}
