package ysyx_26030103
import chisel3._
import _root_.ysyx_26030103.io.axi5.master.ysyx_26030103_AXI5MasterIO
import _root_.ysyx_26030103.io.axi5.slave.ysyx_26030103_AXI5SlaveIO
import _root_.ysyx_26030103.io.debug.ysyx_26030103_DebugIO
import _root_.ysyx_26030103.io.perf.ysyx_26030103_PerfIO
//把原本的AI代码删掉了，太难看了，还有一大堆莫名其妙的注释，只能古法重写IO接口，并且拆分成多个文件
class ysyx_26030103_IO(val SocXLEN: Int = 32) extends Bundle {
  require(
    SocXLEN == 32 || SocXLEN == 64,
    s"顶层 IO 的 SocXLEN 只允许 32 或 64，收到 $SocXLEN"
  )
  val interrupt = Input(Bool())
  val uart_irq = Input(Bool())
  val trap = new ysyx_26030103_TrapIO
  val master = new ysyx_26030103_AXI5MasterIO(SocXLEN)
  val slave = new ysyx_26030103_AXI5SlaveIO(SocXLEN)
  val debug = new ysyx_26030103_DebugIO
  val perf = new ysyx_26030103_PerfIO
}
