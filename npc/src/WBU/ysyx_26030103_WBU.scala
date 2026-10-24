package ysyx_26030103.wbu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
class ysyx_26030103_WBU(val XLEN: Int = 32) extends Module {
  require(XLEN == 32 || XLEN == 64, s"WBU XLEN 非法: $XLEN")
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
  io.WriteEN := io.in.fire && io.in.bits.RegisterWrite
  io.wdata := 0.U(XLEN.W)
  io.FPWriteSELECT := io.in.bits.Rd
  io.FPWriteEN := io.in.fire && io.in.bits.FPRegisterWrite
  io.FPWriteData := io.in.bits.FPWriteData
  // 只有真正退休且修改了 f 寄存器的 FP 指令才把 FS 置 Dirty；FSW/FSD、
  // FCMP/FCLASS 等只读浮点状态，访问故障的 load/store 或被中断压掉的
  // 指令 Retire=0，都不改变架构状态。仅产生 fflags 的路径由 EXU 提交
  // 时另行置 Dirty。
  io.FPCommit := io.in.fire && io.in.bits.Retire &&
    io.in.bits.FPRegisterWrite
  switch(io.in.bits.WBSelect) {
    is("b00".U) {
      io.wdata := io.in.bits.ALUResult
    }
    is("b01".U) {
      io.wdata := io.in.bits.LoadData
    }
    is("b10".U) {
      io.wdata := io.in.bits.snpc
    }
    is("b11".U) {
      io.wdata := io.in.bits.CSRReadData
    }
  }
}
