package ysyx_26030103.infra

import chisel3._
import chisel3.util._

private[infra] trait AXI5XbarResponse {
  this: ysyx_26030103_AXI5Xbar =>

  def responseBody(): Unit = {
    val rBufferPop = rQueue.io.deq.fire
    val rBufferAvailable = rQueue.io.enq.ready || rBufferPop
    val SOCResponseExpected =
      (readSlotState(io.SoCBus.R.RID) === readSlotResponse &&
        readTargetReg(io.SoCBus.R.RID) === targetSoCBus) ||
        (SOCRequestFire && SOCRequestID === io.SoCBus.R.RID)
    val CLINTResponseExpected =
      (readSlotState(io.CLINT.R.RID) === readSlotResponse &&
        readTargetReg(io.CLINT.R.RID) === TargetCLINT) ||
        (CLINTRequestFire && CLINTRequestID === io.CLINT.R.RID)
    val PLICResponseExpected =
      (readSlotState(io.PLIC.R.RID) === readSlotResponse &&
        readTargetReg(io.PLIC.R.RID) === TargetPLIC) ||
        (PLICRequestFire && PLICRequestID === io.PLIC.R.RID)
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
      rQueue.io.enq.valid := true.B
      rQueue.io.enq.bits.ID := io.SoCBus.R.RID
      rQueue.io.enq.bits.data := io.SoCBus.R.RDATA
      rQueue.io.enq.bits.response := io.SoCBus.R.RRESP
      rQueue.io.enq.bits.last := io.SoCBus.R.RLAST
    }.elsewhen(CaptureCLINT) {
      rQueue.io.enq.valid := true.B
      rQueue.io.enq.bits.ID := io.CLINT.R.RID
      rQueue.io.enq.bits.data := io.CLINT.R.RDATA
      rQueue.io.enq.bits.response := io.CLINT.R.RRESP
      rQueue.io.enq.bits.last := io.CLINT.R.RLAST
    }.elsewhen(CapturePLIC) {
      rQueue.io.enq.valid := true.B
      rQueue.io.enq.bits.ID := io.PLIC.R.RID
      rQueue.io.enq.bits.data := io.PLIC.R.RDATA
      rQueue.io.enq.bits.response := io.PLIC.R.RRESP
      rQueue.io.enq.bits.last := io.PLIC.R.RLAST
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
