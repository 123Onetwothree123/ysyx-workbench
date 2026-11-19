package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.fpu._

// 提交时训练 BTB / jal BTB / RAS。
private[exu] trait EXUPredictorUpdate { this: ysyx_26030103_EXU =>
  def predictorUpdateBody(): Unit = {
    // 分支 BTB 只保存后向条件分支（前向表项即使命中也选顺序 PC，只会挤掉有用表项）；
    // instructionCommit 继续保证异常/IRQ/更老 MEM fault 不训练表项。
    io.BTBUpdateValid :=
      instructionCommit && inst.IsBranch && branchTarget < inst.PC
    io.BTBUpdatePC := inst.PC
    io.BTBUpdateTarget := branchTarget
    // RAS hint 同时用 x1(ra) 与 x5(alternate link register)：JAL 在 rd 为 link reg 时 push；
    // JALR 按 rd/rs1 hint 产生 push/pop，异名 link reg(x1<-x5 或 x5<-x1)是协程切换，同拍 pop 后 push。
    val rdIsLink = inst.Rd === 1.U || inst.Rd === 5.U
    val rs1IsLink = inst.Rs1 === 1.U || inst.Rs1 === 5.U
    val RASPush = (inst.IsJal && rdIsLink) || (inst.IsJalr && rdIsLink)
    val RASPop =
      inst.IsJalr && rs1IsLink && (!rdIsLink || inst.Rd =/= inst.Rs1)
    // jal BTB: jal 写回静态目标；普通间接 call(rd=x1/x5 且不 pop)写回解析出的实际 JALR 目标，
    // 稳定函数指针调用即可命中；ret 与协程切换存静态 immediate（预测器再与 RAS 栈顶相加）。
    val indirectCall = inst.IsJalr && rdIsLink && !RASPop
    io.JalBTBUpdateValid :=
      instructionCommit && (inst.IsJal || RASPop || indirectCall)
    io.JalBTBUpdatePC := inst.PC
    io.JalBTBUpdateTarget := Mux(
      inst.IsJal,
      jalTarget,
      Mux(RASPop, inst.Immediate, jalrTarget)
    )
    io.JalBTBUpdateKind := Mux(
      RASPop,
      ysyx_26030103_BTBKind.Ret,
      Mux(rdIsLink, ysyx_26030103_BTBKind.Call, ysyx_26030103_BTBKind.Jal)
    )
    // RAS更新：协程JALR会同时拉高push/pop，RAS内部按pop-then-push处理。
    io.RASPushValid := instructionCommit && RASPush
    io.RASPushAddress := inst.snpc
    io.RASPopValid := instructionCommit && RASPop
  }
}
