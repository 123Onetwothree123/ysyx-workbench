package ysyx_26030103.ifu

import chisel3._
import chisel3.util.Cat
import _root_.ysyx_26030103.common.ysyx_26030103_BTBKind

/** 分支、JAL 与 RAS 预测器的优先级和目标选择逻辑。
  *
  * 仅当 RAS 非空时才能使用 Ret 表项。Ret BTB 表项保存的是静态 JALR
  * 立即数而非绝对目标，因此带非零偏移量的返回指令会按
  * `(rasTop + immediate) & ~1` 预测目标，与体系结构规定的 JALR
  * 目标计算方式一致。
  */
class ysyx_26030103_PredictorSelect(AddressWidth: Int = 32) extends Module {
  require(AddressWidth == 32 || AddressWidth == 64,
    s"PredictorSelect AddressWidth 非法: $AddressWidth")
  val io = IO(new Bundle {
    val fetchPC = Input(UInt(AddressWidth.W))
    val branchHit = Input(Bool())
    val branchTarget = Input(UInt(AddressWidth.W))
    val jalHit = Input(Bool())
    val jalTarget = Input(UInt(AddressWidth.W))
    val jalKind = Input(UInt(2.W))
    val rasNonempty = Input(Bool())
    val rasTop = Input(UInt(AddressWidth.W))
    val predHit = Output(Bool())
    val predTarget = Output(UInt(AddressWidth.W))
  })

  val jalRet = io.jalHit && io.jalKind === ysyx_26030103_BTBKind.Ret
  val jalRetUsable = jalRet && io.rasNonempty
  val jalStatic = io.jalHit && io.jalKind =/= ysyx_26030103_BTBKind.Ret
  val branchTaken = io.branchHit && io.branchTarget < io.fetchPC
  val retSum = io.rasTop + io.jalTarget
  val retTarget = Cat(retSum(AddressWidth - 1, 1), 0.U(1.W))

  io.predHit := jalRetUsable || jalStatic || branchTaken
  io.predTarget := Mux(
    jalRetUsable,
    retTarget,
    Mux(jalStatic, io.jalTarget, io.branchTarget)
  )
}
