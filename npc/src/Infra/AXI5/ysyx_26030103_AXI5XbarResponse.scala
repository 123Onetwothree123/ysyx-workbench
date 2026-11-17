package ysyx_26030103.infra

import chisel3._
import chisel3.util._

private[infra] trait AXI5XbarResponse {
  this: ysyx_26030103_AXI5Xbar =>

  def responseBody(): Unit = {
    val rBufferPop = rQueue.io.deq.fire
    val rBufferAvailable = rQueue.io.enq.ready || rBufferPop
    val socResponseExpected =
      (readSlotState(io.SoCBus.R.RID) === readSlotResponse &&
        readTargetReg(io.SoCBus.R.RID) === targetSoCBus) ||
        (socRequestFire && socRequestID === io.SoCBus.R.RID)
    val clintResponseExpected =
      (readSlotState(io.CLINT.R.RID) === readSlotResponse &&
        readTargetReg(io.CLINT.R.RID) === targetCLINT) ||
        (clintRequestFire && clintRequestID === io.CLINT.R.RID)
    val plicResponseExpected =
      (readSlotState(io.PLIC.R.RID) === readSlotResponse &&
        readTargetReg(io.PLIC.R.RID) === targetPLIC) ||
        (plicRequestFire && plicRequestID === io.PLIC.R.RID)
    val socResponseValid = io.SoCBus.R.RVALID && socResponseExpected
    val clintResponseValid = io.CLINT.R.RVALID && clintResponseExpected
    val plicResponseValid = io.PLIC.R.RVALID && plicResponseExpected

    val localResponseMask = VecInit((0 until readSlotCount).map { id =>
      readSlotState(id) === readSlotDECERR
    }).asUInt
    val localResponseValid = localResponseMask.orR
    val localResponseID = PriorityEncoder(localResponseMask)
    val localResponseLast =
      readDECERRBeatReg(localResponseID) === arLenReg(localResponseID)

    // AXI突发长度有限，固定源优先级安全；仲裁失败的源RREADY为低并保持载荷。
    val selectSoC = socResponseValid
    val selectCLINT = !selectSoC && clintResponseValid
    val selectPLIC = !selectSoC && !selectCLINT && plicResponseValid
    val selectLocal = !selectSoC && !selectCLINT && !selectPLIC && localResponseValid
    io.SoCBus.R.RREADY := rBufferAvailable && selectSoC
    io.CLINT.R.RREADY := rBufferAvailable && selectCLINT
    io.PLIC.R.RREADY := rBufferAvailable && selectPLIC
    val captureSoC = io.SoCBus.R.RVALID && io.SoCBus.R.RREADY
    val captureCLINT = io.CLINT.R.RVALID && io.CLINT.R.RREADY
    val capturePLIC = io.PLIC.R.RVALID && io.PLIC.R.RREADY
    val captureLocal = rBufferAvailable && selectLocal

    when(captureSoC) {
      rQueue.io.enq.valid := true.B
      rQueue.io.enq.bits.id := io.SoCBus.R.RID
      rQueue.io.enq.bits.data := io.SoCBus.R.RDATA
      rQueue.io.enq.bits.response := io.SoCBus.R.RRESP
      rQueue.io.enq.bits.last := io.SoCBus.R.RLAST
    }.elsewhen(captureCLINT) {
      rQueue.io.enq.valid := true.B
      rQueue.io.enq.bits.id := io.CLINT.R.RID
      rQueue.io.enq.bits.data := io.CLINT.R.RDATA
      rQueue.io.enq.bits.response := io.CLINT.R.RRESP
      rQueue.io.enq.bits.last := io.CLINT.R.RLAST
    }.elsewhen(capturePLIC) {
      rQueue.io.enq.valid := true.B
      rQueue.io.enq.bits.id := io.PLIC.R.RID
      rQueue.io.enq.bits.data := io.PLIC.R.RDATA
      rQueue.io.enq.bits.response := io.PLIC.R.RRESP
      rQueue.io.enq.bits.last := io.PLIC.R.RLAST
    }.elsewhen(captureLocal) {
      rQueue.io.enq.valid := true.B
      rQueue.io.enq.bits.id := localResponseID
      rQueue.io.enq.bits.data := 0.U
      rQueue.io.enq.bits.response := DECERR
      rQueue.io.enq.bits.last := localResponseLast
      when(localResponseLast) {
        readSlotState(localResponseID) := readSlotLocalLast
      }.otherwise {
        readDECERRBeatReg(localResponseID) :=
          readDECERRBeatReg(localResponseID) + 1.U
      }
    }

    // 本地DECERR状态在数据拍进FIFO时推进，而不是该拍被消费时。若SoC/CLINT
    // 响应在本地拍出队的同拍替换它，再推进旧槽位会跳过一拍甚至跳过RLAST。

    when(rBufferPop && rQueue.io.deq.bits.last) {
      readSlotState(rQueue.io.deq.bits.id) := readSlotFree
    }

    when(io.SoCBus.R.RVALID) {
      assert(socResponseExpected, "SoCBus returned an inactive or misrouted RID")
    }
    when(io.CLINT.R.RVALID) {
      assert(clintResponseExpected, "CLINT returned an inactive or misrouted RID")
    }
    when(io.PLIC.R.RVALID) {
      assert(plicResponseExpected, "PLIC returned an inactive or misrouted RID")
    }
  }
}
