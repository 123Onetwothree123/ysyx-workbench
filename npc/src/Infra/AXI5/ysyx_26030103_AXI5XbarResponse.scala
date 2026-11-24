package ysyx_26030103.infra

import chisel3._
import chisel3.util._

private[infra] trait AXI5XbarResponse {
  this: ysyx_26030103_AXI5Xbar =>

  private def ReadResponseExpected(
      downstream: ysyx_26030103_AXI5IO,
      target: UInt,
      RequestFire: Bool,
      RequestID: UInt
  ): Bool =
    (readSlotState(downstream.R.RID) === readSlotResponse &&
      readTargetReg(downstream.R.RID) === target) ||
      (RequestFire && RequestID === downstream.R.RID)

  private def EnqueueReadResponse(downstream: ysyx_26030103_AXI5IO): Unit = {
    rQueue.io.enq.valid := true.B
    rQueue.io.enq.bits.ID := downstream.R.RID
    rQueue.io.enq.bits.data := downstream.R.RDATA
    rQueue.io.enq.bits.response := downstream.R.RRESP
    rQueue.io.enq.bits.last := downstream.R.RLAST
  }

  def responseBody(): Unit = {
    val rBufferPop = rQueue.io.deq.fire
    val rBufferAvailable = rQueue.io.enq.ready || rBufferPop
    val SOCResponseExpected =
      ReadResponseExpected(io.SoCBus, targetSoCBus, SOCRequestFire, SOCRequestID)
    val CLINTResponseExpected =
      ReadResponseExpected(io.CLINT, TargetCLINT, CLINTRequestFire, CLINTRequestID)
    val PLICResponseExpected =
      ReadResponseExpected(io.PLIC, TargetPLIC, PLICRequestFire, PLICRequestID)
    val SOCResponseValid = io.SoCBus.R.RVALID && SOCResponseExpected
    val CLINTResponseValid = io.CLINT.R.RVALID && CLINTResponseExpected
    val PLICResponseValid = io.PLIC.R.RVALID && PLICResponseExpected

    val localResponseMask = VecInit((0 until readSlotCount).map { ID =>
      readSlotState(ID) === readSlotDECERR
    }).asUInt
    val localResponseValid = localResponseMask.orR
    val LocalResponseID = PriorityEncoder(localResponseMask)
    val localResponseLast =
      readDECERRBeatReg(LocalResponseID) === ARLengthReg(LocalResponseID)

    // AXI突发长度有限，固定源优先级安全；仲裁失败的源RREADY为低并保持载荷。
    val selectSoC = SOCResponseValid
    val SelectCLINT = !selectSoC && CLINTResponseValid
    val SelectPLIC = !selectSoC && !SelectCLINT && PLICResponseValid
    val selectLocal = !selectSoC && !SelectCLINT && !SelectPLIC && localResponseValid
    io.SoCBus.R.RREADY := rBufferAvailable && selectSoC
    io.CLINT.R.RREADY := rBufferAvailable && SelectCLINT
    io.PLIC.R.RREADY := rBufferAvailable && SelectPLIC
    val captureSoC = io.SoCBus.R.RVALID && io.SoCBus.R.RREADY
    val CaptureCLINT = io.CLINT.R.RVALID && io.CLINT.R.RREADY
    val CapturePLIC = io.PLIC.R.RVALID && io.PLIC.R.RREADY
    val captureLocal = rBufferAvailable && selectLocal

    when(captureSoC) {
      EnqueueReadResponse(io.SoCBus)
    }.elsewhen(CaptureCLINT) {
      EnqueueReadResponse(io.CLINT)
    }.elsewhen(CapturePLIC) {
      EnqueueReadResponse(io.PLIC)
    }.elsewhen(captureLocal) {
      rQueue.io.enq.valid := true.B
      rQueue.io.enq.bits.ID := LocalResponseID
      rQueue.io.enq.bits.data := 0.U
      rQueue.io.enq.bits.response := DECERR
      rQueue.io.enq.bits.last := localResponseLast
      when(localResponseLast) {
        readSlotState(LocalResponseID) := readSlotLocalLast
      }.otherwise {
        readDECERRBeatReg(LocalResponseID) :=
          readDECERRBeatReg(LocalResponseID) + 1.U
      }
    }

    // 本地DECERR状态在数据拍进FIFO时推进，而不是该拍被消费时。若SoC/CLINT
    // 响应在本地拍出队的同拍替换它，再推进旧槽位会跳过一拍甚至跳过RLAST。

    when(rBufferPop && rQueue.io.deq.bits.last) {
      readSlotState(rQueue.io.deq.bits.ID) := readSlotFree
    }

    when(io.SoCBus.R.RVALID) {
      assert(SOCResponseExpected, "SoCBus returned an inactive or misrouted RID")
    }
    when(io.CLINT.R.RVALID) {
      assert(CLINTResponseExpected, "CLINT returned an inactive or misrouted RID")
    }
    when(io.PLIC.R.RVALID) {
      assert(PLICResponseExpected, "PLIC returned an inactive or misrouted RID")
    }
  }
}
