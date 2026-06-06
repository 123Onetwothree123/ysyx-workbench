package RV32I
import chisel3._
import chisel3.util._
//他妈的，我们伟大的scala插件和编译器设计专家应该要以死谢罪，是哪个天才想到的，如果直接写RV32I，因为我这个顶层模块类和包同名了
//能被解读成RV32I的RV32I的AXI模块，还得手动指定从最顶层的根目录去找
import _root_.RV32I.AXI5Lite._
import _root_.RV32I.GPR._

class RV32I(AddressWidth: Int = 32) extends Module {
  val io = IO(new RV32IIO)
  val ifu = Module(new IFU)
  val idu = Module(new IDU)
  val exu = Module(new EXU)
  val wbu = Module(new WBU)
  val lsu = Module(new LSU)
  val gpr = Module(new GPR)
  StageConnect(ifu.io.out, idu.io.in)
  StageConnect(idu.io.out, exu.io.in)
  StageConnect(exu.io.out, wbu.io.in)
  ifu.io.InstructionBus.AR <> io.InstructionsBus.AR
  ifu.io.InstructionBus.R <> io.InstructionsBus.R
  lsu.io.DataBus.AW <> io.DataBus.AW
  lsu.io.DataBus.AR <> io.DataBus.AR
  lsu.io.DataBus.W <> io.DataBus.W
  lsu.io.DataBus.R <> io.DataBus.R
  lsu.io.DataBus.B <> io.DataBus.B
  io.DataBus.ACLK := clock
  io.DataBus.ARESETn := ~reset.asBool
  io.InstructionsBus.ACLK := clock
  io.InstructionsBus.ARESETn := ~reset.asBool
  // 手动连线了
  idu.io.ReadDATA1 := gpr.io.ReadDATA1
  idu.io.ReadDATA2 := gpr.io.ReadDATA2
  gpr.io.Read1SELECT := idu.io.Read1SELECT
  gpr.io.Read2SELECT := idu.io.Read2SELECT
  exu.io.LSU_Complete := lsu.io.Complete
  exu.io.LSULoadDATA := lsu.io.LoadDATA
  lsu.io.MemoryValid := exu.io.MemoryValid
  lsu.io.MemoryWrite := exu.io.MemoryWrite
  lsu.io.WidthSelect := exu.io.WidthSelect
  lsu.io.ALUResult := exu.io.ALUResult_ToLSU
  lsu.io.StoreDATA := exu.io.StoreDATA
  lsu.io.LoadSigned := exu.io.LoadSigned
  ifu.io.Redirect := exu.io.Redirect
  ifu.io.RedirectTarget := exu.io.RedirectTarget
  ifu.io.ExceptionTaken := exu.io.ExceptionTaken
  ifu.io.ExceptionTarget := exu.io.ExceptionTarget
  gpr.io.WriteSELECT := wbu.io.WriteSELECT
  gpr.io.WriteEN := wbu.io.WriteEN
  gpr.io.wdata := wbu.io.wdata
  // 临时新加的处理中断的
  exu.io.Interrupt := io.Interrupt
}
