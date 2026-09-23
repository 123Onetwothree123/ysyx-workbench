package ysyx_26030103.ifu

import chisel3._
import chisel3.util.Cat
import _root_.ysyx_26030103.common.ysyx_26030103_BTBKind

/** Priority and target selection for the branch/JAL/RAS predictors.
  *
  * A Ret entry is usable only with a non-empty RAS.  Ret BTB entries store the
  * static JALR immediate rather than an absolute target, so non-zero-offset
  * returns predict `(rasTop + immediate) & ~1` just like the architectural
  * JALR target calculation.
  */
class ysyx_26030103_PredictorSelect extends Module {
  val io = IO(new Bundle {
    val fetchPC = Input(UInt(32.W))
    val branchHit = Input(Bool())
    val branchTarget = Input(UInt(32.W))
    val jalHit = Input(Bool())
    val jalTarget = Input(UInt(32.W))
    val jalKind = Input(UInt(2.W))
    val rasNonempty = Input(Bool())
    val rasTop = Input(UInt(32.W))
    val predHit = Output(Bool())
    val predTarget = Output(UInt(32.W))
  })

  val JalRet = io.jalHit && io.jalKind === ysyx_26030103_BTBKind.Ret
  val JalRetUsable = JalRet && io.rasNonempty
  val JalStatic = io.jalHit && io.jalKind =/= ysyx_26030103_BTBKind.Ret
  val BranchTaken = io.branchHit && io.branchTarget < io.fetchPC
  val RetSum = io.rasTop + io.jalTarget
  val RetTarget = Cat(RetSum(31, 1), 0.U(1.W))

  io.predHit := JalRetUsable || JalStatic || BranchTaken
  io.predTarget := Mux(
    JalRetUsable,
    RetTarget,
    Mux(JalStatic, io.jalTarget, io.branchTarget)
  )
}
