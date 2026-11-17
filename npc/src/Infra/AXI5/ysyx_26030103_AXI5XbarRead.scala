package ysyx_26030103.infra

import chisel3._
import chisel3.util._

private[infra] trait AXI5XbarRead {
  this: ysyx_26030103_AXI5Xbar =>

  def readBody(): Unit = {
    when(inARFire) {
      val target = decode(
        io.in.AR.ARADDR,
        io.in.AR.ARLEN,
        io.in.AR.ARSIZE,
        io.in.AR.ARBURST,
        isWrite = false
      )
      readTargetReg(incomingReadID) := target
      arAddressReg(incomingReadID) := io.in.AR.ARADDR
      arLenReg(incomingReadID) := io.in.AR.ARLEN
      arSizeReg(incomingReadID) := io.in.AR.ARSIZE
      arBurstReg(incomingReadID) := io.in.AR.ARBURST
      arProtReg(incomingReadID) := io.in.AR.ARPROT
      arCacheReg(incomingReadID) := io.in.AR.ARCACHE
      arQosReg(incomingReadID) := io.in.AR.ARQOS
      readDECERRBeatReg(incomingReadID) := 0.U
      readSlotState(incomingReadID) := Mux(
        target === targetInvalid,
        readSlotDECERR,
        readSlotRequest
      )
    }

    when(socRequestValid) {
      io.SoCBus.AR.ARVALID := true.B
      io.SoCBus.AR.ARID := socRequestID
      io.SoCBus.AR.ARADDR := arAddressReg(socRequestID)
      io.SoCBus.AR.ARLEN := arLenReg(socRequestID)
      io.SoCBus.AR.ARSIZE := arSizeReg(socRequestID)
      io.SoCBus.AR.ARBURST := arBurstReg(socRequestID)
      io.SoCBus.AR.ARPROT := arProtReg(socRequestID)
      io.SoCBus.AR.ARCACHE := arCacheReg(socRequestID)
      io.SoCBus.AR.ARQOS := arQosReg(socRequestID)
      when(socRequestFire) {
        readSlotState(socRequestID) := readSlotResponse
      }
    }
    when(clintRequestValid) {
      io.CLINT.AR.ARVALID := true.B
      io.CLINT.AR.ARID := clintRequestID
      io.CLINT.AR.ARADDR := arAddressReg(clintRequestID)
      io.CLINT.AR.ARLEN := arLenReg(clintRequestID)
      io.CLINT.AR.ARSIZE := arSizeReg(clintRequestID)
      io.CLINT.AR.ARBURST := arBurstReg(clintRequestID)
      io.CLINT.AR.ARPROT := arProtReg(clintRequestID)
      io.CLINT.AR.ARCACHE := arCacheReg(clintRequestID)
      io.CLINT.AR.ARQOS := arQosReg(clintRequestID)
      when(clintRequestFire) {
        readSlotState(clintRequestID) := readSlotResponse
      }
    }
    when(plicRequestValid) {
      io.PLIC.AR.ARVALID := true.B
      io.PLIC.AR.ARID := plicRequestID
      io.PLIC.AR.ARADDR := arAddressReg(plicRequestID)
      io.PLIC.AR.ARLEN := arLenReg(plicRequestID)
      io.PLIC.AR.ARSIZE := arSizeReg(plicRequestID)
      io.PLIC.AR.ARBURST := arBurstReg(plicRequestID)
      io.PLIC.AR.ARPROT := arProtReg(plicRequestID)
      io.PLIC.AR.ARCACHE := arCacheReg(plicRequestID)
      io.PLIC.AR.ARQOS := arQosReg(plicRequestID)
      when(plicRequestFire) {
        readSlotState(plicRequestID) := readSlotResponse
      }
    }
    when(!socARHeld && socRequestValid && !io.SoCBus.AR.ARREADY) {
      socARHeld := true.B
      socARHeldID := socRequestID
    }.elsewhen(socRequestFire) {
      socARHeld := false.B
    }
    when(!clintARHeld && clintRequestValid && !io.CLINT.AR.ARREADY) {
      clintARHeld := true.B
      clintARHeldID := clintRequestID
    }.elsewhen(clintRequestFire) {
      clintARHeld := false.B
    }
    when(!plicARHeld && plicRequestValid && !io.PLIC.AR.ARREADY) {
      plicARHeld := true.B
      plicARHeldID := plicRequestID
    }.elsewhen(plicRequestFire) {
      plicARHeld := false.B
    }
  }
}
