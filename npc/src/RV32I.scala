package RV32I
import chisel3._
import chisel3.util._

class RV32I extends Module {
  val io = IO(new RV32IIO)
//先直接实例化模块了（反正获得对象自由了，不像Verilog那么死）
  val ifu = Module(new IFU)
  val idu = Module(new IDU)
  val gpr = Module(new GPR)
  val exu = Module(new EXU)
  val wbu = Module(new WBU)
//开始连线
  ifu.io.InstructionReadDATA := io.InstructionReadDATA
  io.InstructionAddress := ifu.io.InstructionAddress

  StageConnect(ifu.io.out, idu.io.in)
  StageConnect(idu.io.out, exu.io.in)
  StageConnect(exu.io.out, wbu.io.in)

  gpr.io.Read1SELECT := idu.io.Read1SELECT
  gpr.io.Read2SELECT := idu.io.Read2SELECT
  idu.io.ReadDATA1 := gpr.io.ReadDATA1
  idu.io.ReadDATA2 := gpr.io.ReadDATA2
  gpr.io.WriteSELECT := wbu.io.RegisterFileWriteSELECT
  gpr.io.WriteEN := wbu.io.RegisterFileWriteEN
  gpr.io.wdata := wbu.io.RegisterFileWriteDATA

  exu.io.MemoryReadDATA := io.MemoryReadDATA
  io.MemWE := exu.io.MemWE
  io.MemAddr := exu.io.MemAddr
  io.MemWriteDATA := exu.io.MemWriteDATA
  io.MemWriteMask := exu.io.MemWriteMask

  ifu.io.Redirect := exu.io.Redirect
  ifu.io.RedirectTarget := exu.io.RedirectTarget
  ifu.io.ExceptionTaken := exu.io.ExceptionTaken
  ifu.io.ExceptionTarget := exu.io.ExceptionTarget
}
