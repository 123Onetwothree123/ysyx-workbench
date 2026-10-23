package ysyx_26030103.common

import chisel3._
import chisel3.util._

/** 最小可交互的 16550 UART 模型。
  *
  * 只实现 xv6/AM 实际使用的寄存器子集：
  *   - 偏移 0：RBR（读，弹出接收字节）/ THR（写，输出到仿真 stdout）
  *   - 偏移 1：IER（RX 中断使能；TX 中断暂不产生）
  *   - 偏移 2：IIR（读）/ FCR（写，支持清空接收 FIFO）
  *   - 偏移 3：LCR（仅 DLAB 位影响偏移 0/1 的解释）
  *   - 偏移 5：LSR（DataReady / THRE / TEMT）
  *
  * 接收侧是一个深度可配置的小 FIFO。`rxValid/rxData` 由仿真顶层注入，
  * 只要 FIFO 未满就在时钟沿接收，不会反压或阻塞仿真；`rxReady` 供上层
  * 在注入前做流控。`irq` 是电平信号：IER.RX 使能且 FIFO 非空时保持为高，
  * 读走最后一个字节后自动撤销。
  */
class ysyx_26030103_UART16550(
    val Depth: Int = 64,
    val AddressWidth: Int = 5
) extends Module {
  require(Depth >= 1 && Depth <= 256, "UART RX FIFO 深度必须在 1..256")

  private val OffsetBits = 3

  val io = IO(new Bundle {
    // 仿真注入的串行接收字节。
    val rxValid = Input(Bool())
    val rxData = Input(UInt(8.W))
    val rxReady = Output(Bool())
    // 寄存器读写端口：地址使用 UART 窗口内的字节偏移。
    val rdAddr = Input(UInt(AddressWidth.W))
    val rdEn = Input(Bool())
    val rdData = Output(UInt(8.W))
    val wrAddr = Input(UInt(AddressWidth.W))
    val wrEn = Input(Bool())
    val wrData = Input(UInt(8.W))
    // 电平中断，接到 PLIC source 10。
    val irq = Output(Bool())
  })

  val rdOffset = io.rdAddr(OffsetBits - 1, 0)
  val wrOffset = io.wrAddr(OffsetBits - 1, 0)

  // 16550 的寄存器状态。复位值按常见实现：IER/FCR/LCR 等为 0，
  // LSR 的 THRE/TEMT 恒为 1（发送器始终可接受新字节）。
  val ier = RegInit(0.U(4.W))
  val fcr = RegInit(0.U(8.W))
  val lcr = RegInit(0.U(8.W))
  val mcr = RegInit(0.U(8.W))
  val scr = RegInit(0.U(8.W))
  val divisorLow = RegInit(0.U(8.W))
  val divisorHigh = RegInit(0.U(8.W))

  // 接收 FIFO 采用移位寄存器实现：深度小，读口组合输出队首，便于
  // AXIRAM 在 AR 握手当拍取得读数据。
  val rxQueue = RegInit(VecInit(Seq.fill(Depth)(0.U(8.W))))
  val rxCount = RegInit(0.U(log2Ceil(Depth + 1).W))
  val overrun = RegInit(false.B)

  val fifoNotEmpty = rxCount =/= 0.U
  val fifoNotFull = rxCount < Depth.U
  io.rxReady := fifoNotFull

  // rxCount 可以等于 Depth（FIFO 满），需要多一位表示；写指针只取
  // 低 log2Ceil(Depth) 位，避免 Chisel 的动态索引越界警告。
  private def queueIndex(value: UInt): UInt =
    if (Depth == 1) 0.U else value(log2Ceil(Depth) - 1, 0)

  val doPop =
    io.rdEn && rdOffset === 0.U && !lcr(7) && fifoNotEmpty
  val doPush = io.rxValid && fifoNotFull

  when(doPop) {
    // 队首被读走，其余元素前移。若同拍还有注入，新字节放到新的队尾。
    for (i <- 0 until Depth - 1) {
      rxQueue(i) := Mux((i + 1).U < rxCount, rxQueue(i + 1), 0.U)
    }
    when(doPush) {
      rxQueue(queueIndex(rxCount - 1.U)) := io.rxData
    }
    rxCount := rxCount - 1.U + doPush.asUInt
  }.elsewhen(doPush) {
    rxQueue(queueIndex(rxCount)) := io.rxData
    rxCount := rxCount + 1.U
  }

  // FIFO 溢出：保留旧数据，置 LSR.OE，LSR 读后清除。
  when(io.rxValid && !fifoNotFull) {
    overrun := true.B
  }

  // 写寄存器。偏移 0/1 在 DLAB=1 时是波特率除数锁存器，不能当 THR 输出。
  when(io.wrEn) {
    when(wrOffset === 0.U) {
      when(lcr(7)) { divisorLow := io.wrData }
        .otherwise {
          // THR：直接打印到仿真 stdout，保持原有 UART TX 行为。
          printf("%c", io.wrData)
        }
    }.elsewhen(wrOffset === 1.U) {
      when(lcr(7)) { divisorHigh := io.wrData }
        .otherwise { ier := io.wrData(3, 0) }
    }.elsewhen(wrOffset === 2.U) {
      fcr := io.wrData
      // FCR 的 bit1/bit2 同时置位时清空接收 FIFO。
      when(io.wrData(1) || io.wrData(2)) {
        rxCount := 0.U
        overrun := false.B
      }
    }.elsewhen(wrOffset === 3.U) {
      lcr := io.wrData
    }.elsewhen(wrOffset === 4.U) {
      mcr := io.wrData
    }.elsewhen(wrOffset === 7.U) {
      scr := io.wrData
    }
  }

  // 读寄存器。LSR 的 DataReady 反映 FIFO 是否非空；THRE/TEMT 恒为 1。
  val lsr = Cat(
    false.B, // bit7：错误（无）
    true.B, // bit6：TEMT，发送器完全空闲
    true.B, // bit5：THRE，THR 可写
    false.B, // bit4：break
    false.B, // bit3：framing error
    false.B, // bit2：parity error
    overrun, // bit1：overrun
    fifoNotEmpty // bit0：Data Ready
  )
  // IIR：bit0=0 表示有中断；bits[3:1]=010 表示接收数据可用。
  // 无中断时返回 0xC1（FIFO 使能位 + “无中断”编码），与常见 16550 一致。
  val iir = Mux(
    ier(0) && fifoNotEmpty,
    Cat(fcr(0), fcr(0), 0.U(2.W), "b010".U(3.W), 0.U(1.W)),
    Cat(fcr(0), fcr(0), "b000001".U(6.W))
  )

  io.rdData := MuxLookup(rdOffset, 0.U(8.W))(
    Seq(
      0.U -> Mux(lcr(7), divisorLow, rxQueue(0)),
      1.U -> Mux(lcr(7), divisorHigh, Cat(0.U(4.W), ier)),
      2.U -> iir,
      3.U -> lcr,
      4.U -> mcr,
      5.U -> lsr,
      6.U -> 0.U(8.W), // MSR：无调制解调器状态
      7.U -> scr
    )
  )

  // LSR 读清除 overrun。注意 rdData 在时钟沿前仍返回旧值。
  when(io.rdEn && rdOffset === 5.U) {
    overrun := false.B
  }

  // 只有 RX 数据可用中断；TX 侧在仿真中即时排空，不产生 THRE 中断。
  io.irq := ier(0) && fifoNotEmpty
}
