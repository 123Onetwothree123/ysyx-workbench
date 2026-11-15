package ysyx_26030103.mem

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.infra._

private[mem] class ysyx_26030103_LSUIO(
    val XLEN: Int,
    val AddressWidth: Int,
    val BusDataWidth: Int
) extends Bundle {
  val in = Flipped(Decoupled(new ysyx_26030103_EXUMessage(XLEN)))
  val out = Decoupled(new ysyx_26030103_EXUMessage(XLEN))
  // 给EXU: 本级非空(有访存或完成中的指令),EXU的副作用指令要等着
  val Busy = Output(Bool())
  // 只表示仍可能失败或尚未完成的访存/写缓冲。普通 ALU 指令在本级
  // 单拍直通时不会拉高它，供控制转移避免无谓等待。
  val MemoryBusy = Output(Bool())
  // 访存故障提交(CSR后门)
  val MemTrapCommit = Output(Bool())
  val MemTrapCause = Output(UInt(32.W))
  val MemTrapPC = Output(UInt(XLEN.W))
  val MemTrapTval = Output(UInt(XLEN.W))
  // 访存故障提交时冲刷IDU→EXU和EXU→LSU流水寄存器(里面全是年轻指令)
  val FlushIDEX = Output(Bool())
  val FlushEXMEM = Output(Bool())
  // 直接连总线
  val DataBus = new ysyx_26030103_AXI5IO(AddressWidth, BusDataWidth, 4)
  // 为未来 DMA/其他非 CPU 写入者保留的数据缓存一致性失效口。
  // Fence.I 只同步指令取值，当前写直达 DCache 不因 Fence.I 清空。
  val DCacheFlush = Input(Bool())
  val AtomicBusy = Output(Bool())
  val BusQuiet = Input(Bool())
  // 异常/上下文切换（CSR 提交）时保守地失效 reservation。
  val ReservationClear = Input(Bool())
  val AtomicCommit = Output(Bool())
  // DMA_COHERENCE=hardware_snoop 的地址级失效握手。Ready 为低时 DMA
  // 的 B 保持等待，直到所有匹配行失效且旧 refill/响应不再可能被使用。
  val DCacheDMASnoopValid = Input(Bool())
  val DCacheDMASnoopStart = Input(UInt(AddressWidth.W))
  val DCacheDMASnoopEnd = Input(UInt(AddressWidth.W))
  val DCacheDMASnoopReady = Output(Bool())
  val StoreCommit = Output(Bool())
  val StoreCommitAddress = Output(UInt(AddressWidth.W))
  val StoreCommitLastAddress = Output(UInt(AddressWidth.W))
  // VIPT 并行翻译结果：PAddr 可能晚于 DCache 请求到达；PAddrVaddr 是
  // 该物理地址对应的虚拟地址标签。非并行模式忽略这些输入。
  val VIPTPAddress = Input(UInt(AddressWidth.W))
  val VIPTPAddressValid = Input(Bool())
  val VIPTPAddressVirtualAddress = Input(UInt(AddressWidth.W))
  // MMU 翻译故障：取消本 LSU 已受理但尚未取到 PA 的 load。
  val VIPTKill = Input(Bool())
  val PMPAddress = Output(UInt(AddressWidth.W))
  // 本次访问最后一个字节的地址。PMP 需要同时检查起始与末字节所在的
  // 4 字节 granule，防止 8 字节访问跨出 NA4/TOR 区域边界。
  val PMPLastAddress = Output(UInt(AddressWidth.W))
  val PMPRead = Output(Bool())
  val PMPWrite = Output(Bool())
  val PMPFault = Input(Bool())
  // DCache性能计数器
  val DCachePerfHit = Output(Bool())
  val DCachePerfMiss = Output(Bool())
  val DCachePerfRefillRequest = Output(Bool())
  val DCachePerfRefillResponse = Output(Bool())
  val DCachePerfLineFill = Output(Bool())
  val DCachePerfWriteback = Output(Bool())
  val PerfWBufCount = Output(UInt(5.W))
  // 给IDU做数据冒险检测和转发用
  val HazardValid = Output(Bool())
  val HazardRd = Output(UInt(5.W))
  val HazardRegWrite = Output(Bool())
  val HazardMemOp = Output(Bool())
  val ForwardData = Output(UInt(XLEN.W))
  val ForwardReady = Output(Bool())
  // 浮点寄存器写口/转发。FLW/FLD 的数据在本级完成后才有效。
  val HazardFPRegWrite = Output(Bool())
  val HazardFPRd = Output(UInt(5.W))
  val ForwardFPData = Output(UInt(64.W))
  val ForwardFPReady = Output(Bool())
  // 等待槽冒险检测: 本级被年长访存占用时,EX/MEM流水寄存器里等待的指令
  // 对IDU也必须可见,否则依赖它的消费者会拿着旧寄存器值溜过去
  val Hazard2Valid = Output(Bool())
  val Hazard2Rd = Output(UInt(5.W))
  val Hazard2RegWrite = Output(Bool())
  val Hazard2MemOp = Output(Bool())
  val Hazard2ForwardData = Output(UInt(XLEN.W))
  val Hazard2ForwardReady = Output(Bool())
  val Hazard2FPRegWrite = Output(Bool())
  val Hazard2FPRd = Output(UInt(5.W))
  val Hazard2ForwardFPData = Output(UInt(64.W))
  val Hazard2ForwardFPReady = Output(Bool())
  // 调试/性能
  val Complete = Output(Bool())
  val AccessFault = Output(Bool())
  val AccessFaultResponse = Output(UInt(2.W))
  val Active = Output(Bool())
  val IsStore = Output(Bool())
  val StallReadAR = Output(Bool())
  val StallReadR = Output(Bool())
  val StallWriteRequest = Output(Bool())
  val StallWriteB = Output(Bool())
  val DebugMemoryWrite = Output(Bool())
  val DebugLoadDATA = Output(UInt(32.W))
}
