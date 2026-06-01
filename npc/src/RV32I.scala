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
  val rom = Module(new ROM)
  val memory = Module(new Memory)
//开始连线
  rom.io.Address := ifu.io.InstructionAddress
  // ifu.io.InstructionReadDATA := rom.io.ReadDATA  // 原来从内部ROM取指
  ifu.io.InstructionReadDATA := io.InstructionReadDATA // 改为从顶层IO取指，临时C++的pmem桥接上来，反正能临时跑起来就行
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

  memory.io.valid := true.B
  memory.io.wen := exu.io.MemWE
  memory.io.raddr := exu.io.MemAddr
  memory.io.waddr := exu.io.MemAddr
  memory.io.wdata := exu.io.MemWriteDATA
  memory.io.wmask := exu.io.MemWriteMask

  // exu.io.MemoryReadDATA := memory.io.rdata  // 原来从内部Memory读
  exu.io.MemoryReadDATA := io.MemoryReadDATA // 改为从顶层IO读（C++侧pmem桥接）
  io.MemWE := exu.io.MemWE
  io.MemAddr := exu.io.MemAddr
  io.MemWriteDATA := exu.io.MemWriteDATA
  io.MemWriteMask := exu.io.MemWriteMask

  ifu.io.Redirect := exu.io.Redirect
  ifu.io.RedirectTarget := exu.io.RedirectTarget
  ifu.io.ExceptionTaken := exu.io.ExceptionTaken
  ifu.io.ExceptionTarget := exu.io.ExceptionTarget
}
