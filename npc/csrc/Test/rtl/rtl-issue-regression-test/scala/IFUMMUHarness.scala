package ysyx_26030103.test

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.mmu._

/** 取指 MMU 的定向测试外壳：暴露 fetch/request/response 端口，不接真实 MMU。 */
class IFUMMUHarness extends Module {
  val io = IO(new Bundle {
    val fetchValid = Input(Bool())
    val fetchReady = Output(Bool())
    val fetchVirtualAddress = Input(UInt(64.W))
    val fetchPhysicalAddress = Output(UInt(64.W))
    val fetchPhysicalAddressVirtualAddress = Output(UInt(64.W))
    val translationValid = Output(Bool())
    val cacheReady = Input(Bool())
    val fault = Output(Bool())
    val faultCause = Output(UInt(4.W))
    val faultVirtualAddress = Output(UInt(64.W))
    val PMPFault = Input(Bool())
    val privilege = Input(UInt(2.W))
    val satp = Input(UInt(64.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())
    val flush = Input(Bool())
    val request = Decoupled(new ysyx_26030103_MMURequest(64, 64))
    val response = Flipped(Decoupled(new ysyx_26030103_MMUResponse(64, 64)))
  })

  val dut = Module(new ysyx_26030103_IFUMMU(
    ParallelVIPT = false,
    XLEN = 64,
    AddressWidth = 64
  ))

  dut.io.fetchValid := io.fetchValid
  dut.io.fetchVirtualAddress := io.fetchVirtualAddress
  dut.io.cacheReady := io.cacheReady
  dut.io.PMPFault := io.PMPFault
  dut.io.privilege := io.privilege
  dut.io.satp := io.satp
  dut.io.sum := io.sum
  dut.io.mxr := io.mxr
  dut.io.flush := io.flush

  io.fetchReady := dut.io.fetchReady
  io.fetchPhysicalAddress := dut.io.fetchPhysicalAddress
  io.fetchPhysicalAddressVirtualAddress := dut.io.fetchPhysicalAddressVirtualAddress
  io.translationValid := dut.io.translationValid
  io.fault := dut.io.fault
  io.faultCause := dut.io.faultCause
  io.faultVirtualAddress := dut.io.faultVirtualAddress

  // 两个都是 producer：手工连到 C++ 驱动的 ready。
  io.request.valid := dut.io.request.valid
  io.request.bits := dut.io.request.bits
  dut.io.request.ready := io.request.ready

  dut.io.response.valid := io.response.valid
  dut.io.response.bits := io.response.bits
  io.response.ready := dut.io.response.ready
}

object IFUMMUHarnessElaborate extends App {
  val targetDir = args
    .sliding(2)
    .collectFirst { case Array("--target-dir", value) => value }
    .getOrElse("build/ifu-mmu-rtl")
  emitVerilog(new IFUMMUHarness, Array("--target-dir", targetDir))
}
