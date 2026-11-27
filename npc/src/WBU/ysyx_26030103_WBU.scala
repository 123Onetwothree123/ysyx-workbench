package ysyx_26030103.wbu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
class ysyx_26030103_WBU(val XLEN: Int = 32) extends Module {
  ysyx_26030103_XLENRequire(XLEN, "WBU")
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_EXUMessage(XLEN)))
    // 给ysyx_26030103_GPR的
    val WriteSELECT = Output(UInt(5.W))
    val WriteEN = Output(Bool())
    val wdata = Output(UInt(XLEN.W))
    // 独立的 f 寄存器写口与 FP 退休事件（mstatus.FS=Dirty 用）。
    val FPWriteSELECT = Output(UInt(5.W))
    val FPWriteEN = Output(Bool())
    val FPWriteData = Output(UInt(64.W))
    val FPCommit = Output(Bool())
  })
  io.in.ready := true.B
  io.WriteSELECT := io.in.bits.Rd
  // 写使能必须同时要求 Retire：架构状态不能因为一条被冲刷/触发异常的
  // 指令而改变。上游目前已经门控，这里保持模块自身契约自洽（与下面的
  // FPCommit 一致）。
  io.WriteEN := io.in.fire && io.in.bits.Retire && io.in.bits.RegisterWrite
  io.FPWriteSELECT := io.in.bits.Rd
  val fpWriteFire = io.in.fire && io.in.bits.Retire &&
    io.in.bits.FPRegisterWrite
  io.FPWriteEN := fpWriteFire
  io.FPWriteData := io.in.bits.FPWriteData
  // 只有真正退休且修改了 f 寄存器的 FP 指令才把 FS 置 Dirty；FSW/FSD、
  // FCMP/FCLASS 等只读浮点状态，访问故障的 load/store 或被中断压掉的
  // 指令 Retire=0，都不改变架构状态。仅产生 fflags 的路径由 EXU 提交
  // 时另行置 Dirty。
  io.FPCommit := fpWriteFire
  io.wdata := ysyx_26030103_WritebackSource.Select(
    io.in.bits.WBSelect,
    io.in.bits.ALUResult,
    io.in.bits.LoadData,
    io.in.bits.snpc,
    io.in.bits.CSRReadData,
    0.U(XLEN.W)
  )
}
