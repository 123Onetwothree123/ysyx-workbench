package ysyx_26030103.infra

import chisel3._
import chisel3.util._

private[infra] trait AXI5XbarRead {
  this: ysyx_26030103_AXI5Xbar =>

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

    when(SOCRequestValid) {
      io.SoCBus.AR.ARVALID := true.B
      io.SoCBus.AR.ARID := SOCRequestID
      io.SoCBus.AR.ARADDR := ARAddressReg(SOCRequestID)
      io.SoCBus.AR.ARLEN := ARLengthReg(SOCRequestID)
      io.SoCBus.AR.ARSIZE := ARSizeReg(SOCRequestID)
      io.SoCBus.AR.ARBURST := ARBurstReg(SOCRequestID)
      io.SoCBus.AR.ARPROT := ARProtReg(SOCRequestID)
      io.SoCBus.AR.ARCACHE := ARCacheReg(SOCRequestID)
      io.SoCBus.AR.ARQOS := ARQosReg(SOCRequestID)
      when(SOCRequestFire) {
        readSlotState(SOCRequestID) := readSlotResponse
      }
    }
    when(CLINTRequestValid) {
      io.CLINT.AR.ARVALID := true.B
      io.CLINT.AR.ARID := CLINTRequestID
      io.CLINT.AR.ARADDR := ARAddressReg(CLINTRequestID)
      io.CLINT.AR.ARLEN := ARLengthReg(CLINTRequestID)
      io.CLINT.AR.ARSIZE := ARSizeReg(CLINTRequestID)
      io.CLINT.AR.ARBURST := ARBurstReg(CLINTRequestID)
      io.CLINT.AR.ARPROT := ARProtReg(CLINTRequestID)
      io.CLINT.AR.ARCACHE := ARCacheReg(CLINTRequestID)
      io.CLINT.AR.ARQOS := ARQosReg(CLINTRequestID)
      when(CLINTRequestFire) {
        readSlotState(CLINTRequestID) := readSlotResponse
      }
    }
    when(PLICRequestValid) {
      io.PLIC.AR.ARVALID := true.B
      io.PLIC.AR.ARID := PLICRequestID
      io.PLIC.AR.ARADDR := ARAddressReg(PLICRequestID)
      io.PLIC.AR.ARLEN := ARLengthReg(PLICRequestID)
      io.PLIC.AR.ARSIZE := ARSizeReg(PLICRequestID)
      io.PLIC.AR.ARBURST := ARBurstReg(PLICRequestID)
      io.PLIC.AR.ARPROT := ARProtReg(PLICRequestID)
      io.PLIC.AR.ARCACHE := ARCacheReg(PLICRequestID)
      io.PLIC.AR.ARQOS := ARQosReg(PLICRequestID)
      when(PLICRequestFire) {
        readSlotState(PLICRequestID) := readSlotResponse
      }
    }
    when(!SOCARHeld && SOCRequestValid && !io.SoCBus.AR.ARREADY) {
      SOCARHeld := true.B
      SOCARHeldID := SOCRequestID
    }.elsewhen(SOCRequestFire) {
      SOCARHeld := false.B
    }
    when(!CLINTARHeld && CLINTRequestValid && !io.CLINT.AR.ARREADY) {
      CLINTARHeld := true.B
      CLINTARHeldID := CLINTRequestID
    }.elsewhen(CLINTRequestFire) {
      CLINTARHeld := false.B
    }
    when(!PLICARHeld && PLICRequestValid && !io.PLIC.AR.ARREADY) {
      PLICARHeld := true.B
      PLICARHeldID := PLICRequestID
    }.elsewhen(PLICRequestFire) {
      PLICARHeld := false.B
    }
  }
}
