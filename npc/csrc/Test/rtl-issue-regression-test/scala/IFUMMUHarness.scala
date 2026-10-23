package ysyx_26030103.test

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.mmu._

/** 取指 MMU 的定向测试外壳：暴露 fetch/req/resp 端口，不接真实 MMU。 */
class IFUMMUHarness extends Module {
  val io = IO(new Bundle {
    val fetchValid = Input(Bool())
    val fetchReady = Output(Bool())
    val fetchVaddr = Input(UInt(64.W))
    val fetchPaddr = Output(UInt(64.W))
    val fetchPaddrVaddr = Output(UInt(64.W))
    val translationValid = Output(Bool())
    val cacheReady = Input(Bool())
    val fault = Output(Bool())
    val faultCause = Output(UInt(4.W))
    val faultVaddr = Output(UInt(64.W))
    val pmpFault = Input(Bool())
    val privilege = Input(UInt(2.W))
    val satp = Input(UInt(64.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())
    val flush = Input(Bool())
    val req = Decoupled(new ysyx_26030103_MMURequest(64, 64))
    val resp = Flipped(Decoupled(new ysyx_26030103_MMUResponse(64, 64)))
  })

  val dut = Module(new ysyx_26030103_IFUMMU(
    ParallelVipt = false,
    XLEN = 64,
    AddressWidth = 64
  ))

  dut.io.fetchValid := io.fetchValid
  dut.io.fetchVaddr := io.fetchVaddr
  dut.io.cacheReady := io.cacheReady
  dut.io.pmpFault := io.pmpFault
  dut.io.privilege := io.privilege
  dut.io.satp := io.satp
  dut.io.sum := io.sum
  dut.io.mxr := io.mxr
  dut.io.flush := io.flush

  io.fetchReady := dut.io.fetchReady
  io.fetchPaddr := dut.io.fetchPaddr
  io.fetchPaddrVaddr := dut.io.fetchPaddrVaddr
  io.translationValid := dut.io.translationValid
  io.fault := dut.io.fault
  io.faultCause := dut.io.faultCause
  io.faultVaddr := dut.io.faultVaddr

  // 两个都是 producer：手工连到 C++ 驱动的 ready。
  io.req.valid := dut.io.req.valid
  io.req.bits := dut.io.req.bits
  dut.io.req.ready := io.req.ready

  dut.io.resp.valid := io.resp.valid
  dut.io.resp.bits := io.resp.bits
  io.resp.ready := dut.io.resp.ready
}

object IFUMMUHarnessElaborate extends App {
  val targetDir = args
    .sliding(2)
    .collectFirst { case Array("--target-dir", value) => value }
    .getOrElse("build/ifu-mmu-rtl")
  emitVerilog(new IFUMMUHarness, Array("--target-dir", targetDir))
}
