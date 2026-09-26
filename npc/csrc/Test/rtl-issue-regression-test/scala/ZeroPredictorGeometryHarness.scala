package ysyx_26030103.test

import chisel3._
import _root_.ysyx_26030103.ifu.{ysyx_26030103_BTB, ysyx_26030103_RAS}

/** Exercises the legitimate one-set/one-entry predictor configurations. */
class ZeroPredictorGeometryHarness extends Module {
  val io = IO(new Bundle {
    val btbLookupPC = Input(UInt(30.W))
    val btbHit = Output(Bool())
    val btbTarget = Output(UInt(32.W))
    val btbUpdateValid = Input(Bool())
    val btbUpdatePC = Input(UInt(30.W))
    val btbUpdateTarget = Input(UInt(32.W))
    val btbFlush = Input(Bool())

    val jalBtbLookupPC = Input(UInt(30.W))
    val jalBtbHit = Output(Bool())
    val jalBtbTarget = Output(UInt(32.W))
    val jalBtbKind = Output(UInt(2.W))
    val jalBtbUpdateValid = Input(Bool())
    val jalBtbUpdatePC = Input(UInt(30.W))
    val jalBtbUpdateTarget = Input(UInt(32.W))
    val jalBtbUpdateKind = Input(UInt(2.W))
    val jalBtbFlush = Input(Bool())

    val rasTop = Output(UInt(32.W))
    val rasNonempty = Output(Bool())
    val rasPushValid = Input(Bool())
    val rasPushAddr = Input(UInt(32.W))
    val rasPopValid = Input(Bool())
    val rasFlush = Input(Bool())
  })

  val btb = Module(new ysyx_26030103_BTB(BTBBits = 0, BTBWays = 1))
  btb.io.lookup_pc := io.btbLookupPC
  io.btbHit := btb.io.hit
  io.btbTarget := btb.io.target
  btb.io.update_valid := io.btbUpdateValid
  btb.io.update_pc := io.btbUpdatePC
  btb.io.update_target := io.btbUpdateTarget
  btb.io.flush := io.btbFlush

  // The production JAL BTB is the same table with a two-bit kind payload.
  // Instantiate it separately so JalBTBBits=0 is tested as behavior rather
  // than merely accepted by the config guard.
  val jalBtb = Module(
    new ysyx_26030103_BTB(BTBBits = 0, BTBWays = 1, KindBits = 2)
  )
  jalBtb.io.lookup_pc := io.jalBtbLookupPC
  io.jalBtbHit := jalBtb.io.hit
  io.jalBtbTarget := jalBtb.io.target
  io.jalBtbKind := jalBtb.io.hit_kind.get
  jalBtb.io.update_valid := io.jalBtbUpdateValid
  jalBtb.io.update_pc := io.jalBtbUpdatePC
  jalBtb.io.update_target := io.jalBtbUpdateTarget
  jalBtb.io.update_kind.get := io.jalBtbUpdateKind
  jalBtb.io.flush := io.jalBtbFlush

  val ras = Module(new ysyx_26030103_RAS(RASBits = 0))
  io.rasTop := ras.io.top
  io.rasNonempty := ras.io.nonempty
  ras.io.push_valid := io.rasPushValid
  ras.io.push_addr := io.rasPushAddr
  ras.io.pop_valid := io.rasPopValid
  ras.io.flush := io.rasFlush
}

object ZeroPredictorGeometryHarnessElaborate extends App {
  val targetDir = args
    .sliding(2)
    .collectFirst { case Array("--target-dir", value) => value }
    .getOrElse("build/zero-predictor-rtl")
  emitVerilog(
    new ZeroPredictorGeometryHarness,
    Array("--target-dir", targetDir)
  )
}
