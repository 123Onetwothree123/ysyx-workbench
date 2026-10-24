package ysyx_26030103.common
import chisel3._

/** 32 个 RISC-V 浮点寄存器，FLEN=32/64。
  *
  * 与整数 GPR 不同，f0 是普通可写寄存器，不做硬连线零。三个组合读口
  * 分别服务 rs1/rs2/rs3；写口在 WBU 提交时更新。复位值全 0，便于
  * DiffTest/裸机测试有确定的初始状态。
  */
class ysyx_26030103_FPR(val FLEN: Int = 64) extends Module {
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
  })
  val Regs = RegInit(VecInit(Seq.fill(32)(0.U(FLEN.W))))
  when(io.wen) {
    Regs(io.waddr) := io.wdata
  }
  io.rdata1 := Regs(io.raddr1)
  io.rdata2 := Regs(io.raddr2)
  io.rdata3 := Regs(io.raddr3)
}
