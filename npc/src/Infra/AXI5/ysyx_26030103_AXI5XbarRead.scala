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
      downstream.AR.ARADDR := ARAddressReg(RequestID)
      downstream.AR.ARLEN := ARLengthReg(RequestID)
      downstream.AR.ARSIZE := ARSizeReg(RequestID)
      downstream.AR.ARBURST := ARBurstReg(RequestID)
      downstream.AR.ARPROT := ARProtReg(RequestID)
      downstream.AR.ARCACHE := ARCacheReg(RequestID)
      downstream.AR.ARQOS := ARQosReg(RequestID)
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
      ARAddressReg(IncomingReadID) := io.in.AR.ARADDR
      ARLengthReg(IncomingReadID) := io.in.AR.ARLEN
      ARSizeReg(IncomingReadID) := io.in.AR.ARSIZE
      ARBurstReg(IncomingReadID) := io.in.AR.ARBURST
      ARProtReg(IncomingReadID) := io.in.AR.ARPROT
      ARCacheReg(IncomingReadID) := io.in.AR.ARCACHE
      ARQosReg(IncomingReadID) := io.in.AR.ARQOS
      readDECERRBeatReg(IncomingReadID) := 0.U
      readSlotState(IncomingReadID) := Mux(
        target === targetInvalid,
        readSlotDECERR,
        readSlotRequest
      )
    }

    DriveReadRequest(io.SoCBus, SOCRequestValid, SOCRequestID, SOCRequestFire)
    DriveReadRequest(io.CLINT, CLINTRequestValid, CLINTRequestID, CLINTRequestFire)
    DriveReadRequest(io.PLIC, PLICRequestValid, PLICRequestID, PLICRequestFire)

    HoldReadRequest(
      SOCARHeld,
      SOCARHeldID,
      SOCRequestValid,
      SOCRequestID,
      SOCRequestFire,
      io.SoCBus.AR.ARREADY
    )
    HoldReadRequest(
      CLINTARHeld,
      CLINTARHeldID,
      CLINTRequestValid,
      CLINTRequestID,
      CLINTRequestFire,
      io.CLINT.AR.ARREADY
    )
    HoldReadRequest(
      PLICARHeld,
      PLICARHeldID,
      PLICRequestValid,
      PLICRequestID,
      PLICRequestFire,
      io.PLIC.AR.ARREADY
    )
  }
}
