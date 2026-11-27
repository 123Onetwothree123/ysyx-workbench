package ysyx_26030103.infra

import chisel3._
import chisel3.util._

private[infra] trait AXI5XbarRead {
  this: ysyx_26030103_AXI5Xbar =>

  private def DriveReadRequest(
      downstream: ysyx_26030103_AXI5IO,
      RequestValid: Bool,
      RequestID: UInt,
      RequestFire: Bool
  ): Unit = {
    when(RequestValid) {
      downstream.AR.ARVALID := true.B
      downstream.AR.ARID := RequestID
      downstream.AR.ARADDR := ARBurstShapes(RequestID).address
      downstream.AR.ARLEN := ARBurstShapes(RequestID).length
      downstream.AR.ARSIZE := ARBurstShapes(RequestID).size
      downstream.AR.ARBURST := ARBurstShapes(RequestID).burst
      downstream.AR.ARPROT := ARBurstShapes(RequestID).prot
      downstream.AR.ARCACHE := ARBurstShapes(RequestID).cache
      downstream.AR.ARQOS := ARBurstShapes(RequestID).qos
      when(RequestFire) {
        readSlotState(RequestID) := readSlotResponse
      }
    }
  }

  // 下游未接收时保持已发出的 AR；接收后释放 hold。
  private def HoldReadRequest(
      held: Bool,
      HeldID: UInt,
      RequestValid: Bool,
      RequestID: UInt,
      RequestFire: Bool,
      DownstreamReady: Bool
  ): Unit = {
    when(!held && RequestValid && !DownstreamReady) {
      held := true.B
      HeldID := RequestID
    }.elsewhen(RequestFire) {
      held := false.B
    }
  }

  def readBody(): Unit = {
    when(InARFire) {
      val target = decode(
        io.in.AR.ARADDR,
        io.in.AR.ARLEN,
        io.in.AR.ARSIZE,
        io.in.AR.ARBURST,
        isWrite = false
      )
      readTargetReg(IncomingReadID) := target
      ARBurstShapes(IncomingReadID).ID := io.in.AR.ARID
      ARBurstShapes(IncomingReadID).address := io.in.AR.ARADDR
      ARBurstShapes(IncomingReadID).length := io.in.AR.ARLEN
      ARBurstShapes(IncomingReadID).size := io.in.AR.ARSIZE
      ARBurstShapes(IncomingReadID).burst := io.in.AR.ARBURST
      ARBurstShapes(IncomingReadID).prot := io.in.AR.ARPROT
      ARBurstShapes(IncomingReadID).cache := io.in.AR.ARCACHE
      ARBurstShapes(IncomingReadID).qos := io.in.AR.ARQOS
      readDECERRBeatReg(IncomingReadID) := 0.U
      readSlotState(IncomingReadID) := Mux(
        target === targetInvalid,
        readSlotDECERR,
        readSlotRequest
      )
    }

    ReadTargets.indices.foreach { Index =>
      DriveReadRequest(
        ReadTargets(Index).downstream,
        ReadRequestValids(Index),
        ReadRequestIDs(Index),
        ReadRequestFires(Index)
      )
    }

    ReadTargets.indices.foreach { Index =>
      HoldReadRequest(
        ReadARHelds(Index),
        ReadARHeldIDs(Index),
        ReadRequestValids(Index),
        ReadRequestIDs(Index),
        ReadRequestFires(Index),
        ReadTargets(Index).downstream.AR.ARREADY
      )
    }
  }
}
