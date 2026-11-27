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

  def responseBody(): Unit = {
    val rBufferPop = rQueue.io.deq.fire
    val rBufferAvailable = rQueue.io.enq.ready || rBufferPop
    val ReadResponseExpecteds = ReadTargets.indices.map { Index =>
      ReadResponseExpected(
        ReadTargets(Index).downstream,
        ReadTargets(Index).target,
        ReadRequestFires(Index),
        ReadRequestIDs(Index)
      )
    }
    val ReadResponseValids = ReadTargets.indices.map { Index =>
      ReadTargets(Index).downstream.R.RVALID && ReadResponseExpecteds(Index)
    }
    val ReadResponseSelects = ReadTargets.indices.map { Index =>
      val HigherSelected =
        (0 until Index).map(Source => ReadResponseValids(Source))
          .foldLeft(false.B)(_ || _)
      !HigherSelected && ReadResponseValids(Index)
    }

    val localResponseMask = VecInit((0 until readSlotCount).map { ID =>
      readSlotState(ID) === readSlotDECERR
    }).asUInt
    val localResponseValid = localResponseMask.orR
    val LocalResponseID = PriorityEncoder(localResponseMask)
    val localResponseLast = ysyx_26030103_AXI5Beat.IsLast(
      readDECERRBeatReg(LocalResponseID),
      ARBurstShapes(LocalResponseID).length)

    // AXI突发长度有限，固定源优先级安全；仲裁失败的源RREADY为低并保持载荷。
    val RemoteSelected = ReadResponseSelects.reduce(_ || _)
    val SelectLocal = !RemoteSelected && localResponseValid
    ReadTargets.indices.foreach { Index =>
      ReadTargets(Index).downstream.R.RREADY :=
        rBufferAvailable && ReadResponseSelects(Index)
    }
    val CaptureLocal = rBufferAvailable && SelectLocal
    val AllSelects = ReadResponseSelects :+ SelectLocal
    val AllIDs = ReadTargets.map(_.downstream.R.RID) :+ LocalResponseID
    val AllData = ReadTargets.map(_.downstream.R.RDATA) :+ 0.U(DataWidth.W)
    val AllResponses = ReadTargets.map(_.downstream.R.RRESP) :+ DECERR
    val AllLasts = ReadTargets.map(_.downstream.R.RLAST) :+ localResponseLast

    rQueue.io.enq.valid := rBufferAvailable && AllSelects.reduce(_ || _)
    rQueue.io.enq.bits.ID := Mux1H(AllSelects, AllIDs)
    rQueue.io.enq.bits.data := Mux1H(AllSelects, AllData)
    rQueue.io.enq.bits.response := Mux1H(AllSelects, AllResponses)
    rQueue.io.enq.bits.last := Mux1H(AllSelects, AllLasts)
    when(CaptureLocal) {
      when(localResponseLast) {
        readSlotState(LocalResponseID) := readSlotLocalLast
      }.otherwise {
        readDECERRBeatReg(LocalResponseID) :=
          ysyx_26030103_AXI5Beat.Next(readDECERRBeatReg(LocalResponseID))
      }
    }

    // 本地DECERR状态在数据拍进FIFO时推进，而不是该拍被消费时。若SoC/CLINT
    // 响应在本地拍出队的同拍替换它，再推进旧槽位会跳过一拍甚至跳过RLAST。

    when(rBufferPop && rQueue.io.deq.bits.last) {
      readSlotState(rQueue.io.deq.bits.ID) := readSlotFree
    }

    ReadTargets.indices.foreach { Index =>
      when(ReadTargets(Index).downstream.R.RVALID) {
        assert(
          ReadResponseExpecteds(Index),
          s"${ReadTargets(Index).name} returned an inactive or misrouted RID"
        )
      }
    }
  }
}
