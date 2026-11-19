package ysyx_26030103.test

import chisel3._
import _root_.ysyx_26030103.ifu.{ysyx_26030103_BTB, ysyx_26030103_RAS}

/** Exercises the legitimate one-set/one-entry predictor configurations. */
class ZeroPredictorGeometryHarness extends Module {
  val io = IO(new Bundle {
    val BTBLookupPC = Input(UInt(30.W))
    val BTBHit = Output(Bool())
    val BTBTarget = Output(UInt(32.W))
    val BTBUpdateValid = Input(Bool())
    val BTBUpdatePC = Input(UInt(30.W))
    val BTBUpdateTarget = Input(UInt(32.W))
    val BTBFlush = Input(Bool())

    val JalBTBLookupPC = Input(UInt(30.W))
    val JalBTBHit = Output(Bool())
    val JalBTBTarget = Output(UInt(32.W))
    val JalBTBKind = Output(UInt(2.W))
    val JalBTBUpdateValid = Input(Bool())
    val JalBTBUpdatePC = Input(UInt(30.W))
    val JalBTBUpdateTarget = Input(UInt(32.W))
    val JalBTBUpdateKind = Input(UInt(2.W))
    val JalBTBFlush = Input(Bool())

    val RASTop = Output(UInt(32.W))
    val RASNonempty = Output(Bool())
    val RASPushValid = Input(Bool())
    val RASPushAddress = Input(UInt(32.W))
    val RASPopValid = Input(Bool())
    val RASFlush = Input(Bool())
  })

  val BTB = Module(new ysyx_26030103_BTB(BTBBits = 0, BTBWays = 1))
  BTB.io.LookupPC := io.BTBLookupPC
  io.BTBHit := BTB.io.hit
  io.BTBTarget := BTB.io.target
  BTB.io.UpdateValid := io.BTBUpdateValid
  BTB.io.UpdatePC := io.BTBUpdatePC
  BTB.io.UpdateTarget := io.BTBUpdateTarget
  BTB.io.flush := io.BTBFlush

  // The production JAL BTB is the same table with a two-bit kind payload.
  // Instantiate it separately so JalBTBBits=0 is tested as behavior rather
  // than merely accepted by the config guard.
  val JalBTB = Module(
    new ysyx_26030103_BTB(BTBBits = 0, BTBWays = 1, KindBits = 2)
  )
  JalBTB.io.LookupPC := io.JalBTBLookupPC
  io.JalBTBHit := JalBTB.io.hit
  io.JalBTBTarget := JalBTB.io.target
  io.JalBTBKind := JalBTB.io.HitKind.get
  JalBTB.io.UpdateValid := io.JalBTBUpdateValid
  JalBTB.io.UpdatePC := io.JalBTBUpdatePC
  JalBTB.io.UpdateTarget := io.JalBTBUpdateTarget
  JalBTB.io.UpdateKind.get := io.JalBTBUpdateKind
  JalBTB.io.flush := io.JalBTBFlush

  val RAS = Module(new ysyx_26030103_RAS(RASBits = 0))
  io.RASTop := RAS.io.top
  io.RASNonempty := RAS.io.nonempty
  RAS.io.PushValid := io.RASPushValid
  RAS.io.PushAddress := io.RASPushAddress
  RAS.io.PopValid := io.RASPopValid
  RAS.io.flush := io.RASFlush
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
