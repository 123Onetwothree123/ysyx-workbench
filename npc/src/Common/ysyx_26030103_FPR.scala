package ysyx_26030103.common
import chisel3._
class ysyx_26030103_FPR(
    val FLEN: Int = ysyx_26030103_KconfigDefaults.NpcFPULen,
    val EnableDynamicDebug: Boolean = true
) extends Module {
  require(FLEN == 32 || FLEN == 64, s"FPR FLEN 非法: $FLEN")
  val io = IO(new Bundle {
    val wdata = Input(UInt(FLEN.W))
    val waddr = Input(UInt(5.W))
    val wen = Input(Bool())
    val raddr1 = Input(UInt(5.W))
    val raddr2 = Input(UInt(5.W))
    val raddr3 = Input(UInt(5.W))
    val rdata1 = Output(UInt(FLEN.W))
    val rdata2 = Output(UInt(FLEN.W))
    val rdata3 = Output(UInt(FLEN.W))
    //下面这段是AI之前debug的时候分析出来给的设计方案
    // DiffTest 专用只读口。不能复用上面三个执行级读口：C++ 在时钟沿后
    // 临时改地址回读会让 IDU 当拍的组合读数据跟着变化。独立读口只增加
    // 一个 32 选 1 mux，不引入任何调试专用寄存器。
    val DebugRaddr = Input(UInt(5.W))
    val DebugReadData = Output(UInt(FLEN.W))
  })
  val registerFile = Module(
    new ysyx_26030103_RegisterFile(
      ADDR_WIDTH = 5,
      DATA_WIDTH = FLEN,
      ReadPorts = 3,
      ZeroReg0 = false,
      DebugA0Fallback = false,
      InitZero = true,
      EnableDynamicDebug = EnableDynamicDebug
    )
  )
  registerFile.io.wdata := io.wdata
  registerFile.io.waddr := io.waddr
  registerFile.io.wen := io.wen
  registerFile.io.raddr1 := io.raddr1
  registerFile.io.raddr2 := io.raddr2
  registerFile.io.raddr3 := io.raddr3
  io.rdata1 := registerFile.io.rdata1
  io.rdata2 := registerFile.io.rdata2
  io.rdata3 := registerFile.io.rdata3
  registerFile.io.DebugRaddr := io.DebugRaddr
  io.DebugReadData := registerFile.io.DebugRdata
}
