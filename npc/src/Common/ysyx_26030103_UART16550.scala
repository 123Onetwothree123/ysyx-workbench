package ysyx_26030103.common

import chisel3._
import chisel3.util._

// 最小可交互的 16550 UART 模型，只实现 xv6/AM 实际使用的寄存器子集。
// 接收侧是小 FIFO，`rxValid/rxData` 由仿真顶层注入，FIFO 未满就接收，
// 不反压也不阻塞仿真。发送侧没有真实波特率：写 THR 立即打印到 stdout，
// 另用两个短计数器模拟“THR 占用”和“移位器占用”，让 LSR.THRE/TEMT 和
// THRE 中断具备可观察的 16550 时序。`IRQ` 是电平信号，读走最后一个 RX
// 字节或读 IIR/写 THR 清除 pending 后自动撤销。
class ysyx_26030103_UART16550(
    val Depth: Int = ysyx_26030103_KconfigDefaults.UartFifoDepth,
    val AddressWidth: Int = ysyx_26030103_KconfigDefaults.UartAddressWidth,
    // THR 被占用、LSR.THRE=0 的仿真等待周期。没有真实波特率，该值只需
    // 让软件在写 THR 后能观察到 THRE 拉低，再看到 THRE/TEMT 重新置位。
    val TxHoldCycles: Int = ysyx_26030103_KconfigDefaults.UartTxHoldCycles,
    // 移位器“发送”一个字节的仿真周期，决定 LSR.TEMT 何时恢复。
    val TxShiftCycles: Int = ysyx_26030103_KconfigDefaults.UartTxShiftCycles
) extends Module {
  private val OffsetBits = 3

  require(Depth >= 1, "UART RX FIFO 深度必须 >= 1")
  require(AddressWidth >= OffsetBits,
    s"UART AddressWidth=$AddressWidth 必须 >= $OffsetBits（16550 寄存器偏移位宽）")
  require(TxHoldCycles >= 1, "UART TxHoldCycles 必须 >= 1")
  require(TxShiftCycles >= TxHoldCycles,
    "UART TxShiftCycles 必须不小于 TxHoldCycles")

  val io = IO(new Bundle {
    // 仿真注入的串行接收字节。
    val rxValid = Input(Bool())
    val rxData = Input(UInt(8.W))
    val rxReady = Output(Bool())
    // 寄存器读写端口：地址使用 UART 窗口内的字节偏移。
    val rdAddress = Input(UInt(AddressWidth.W))
    val ReadEnable = Input(Bool())
    val rdData = Output(UInt(8.W))
    val wrAddress = Input(UInt(AddressWidth.W))
    val WriteEnable = Input(Bool())
    val wrData = Input(UInt(8.W))
    // THR 写入的 host 可见副本。仅用于仿真器 marker/检查点触发，
    // 不改变 16550 的 TX 行为。
    val txValid = Output(Bool())
    val txData = Output(UInt(8.W))
    // 电平中断，接到 PLIC source 10。
    val IRQ = Output(Bool())
  })

  val rdOffset = io.rdAddress(OffsetBits - 1, 0)
  val wrOffset = io.wrAddress(OffsetBits - 1, 0)

  // 16550 的寄存器状态。复位值按常见实现：IER/FCR/LCR 等为 0；
  // 发送侧空闲，因此 LSR 的 THRE/TEMT 复位为 1。
  val ier = RegInit(0.U(4.W))
  val fcr = RegInit(0.U(8.W))
  val lcr = RegInit(0.U(8.W))
  io.txValid := io.WriteEnable && wrOffset === 0.U && !lcr(7)
  io.txData := io.wrData
  val mcr = RegInit(0.U(8.W))
  val scr = RegInit(0.U(8.W))
  val divisorLow = RegInit(0.U(8.W))
  val divisorHigh = RegInit(0.U(8.W))

  // 发送侧时序：thrHold 非零表示 THR 刚被写入、尚不能接收下一字节；
  // txShift 非零表示“移位器”仍在发送。两者都为 0 时 LSR 的
  // THRE/TEMT 同时为 1。计数器在晶振周期上递减，代表固定仿真延迟。
  private val txHoldBits = log2Ceil(TxHoldCycles + 1)
  private val txShiftBits = log2Ceil(TxShiftCycles + 1)
  val thrHold = RegInit(0.U(txHoldBits.W))
  val txShift = RegInit(0.U(txShiftBits.W))
  when(thrHold =/= 0.U) { thrHold := thrHold - 1.U }
  when(txShift =/= 0.U) { txShift := txShift - 1.U }

  val thrEmpty = thrHold === 0.U
  val temt = thrEmpty && txShift === 0.U

  // THRE 中断采用 16550 的边沿/挂起语义：THR 由忙变空（或 IER.THRE
  // 在空闲状态下被打开）时置 pending；写 THR 或把 THRE 作为 IIR 的
  // 当前中断源读取时清除。清掉后若 THR 仍为空，不会在同一空闲区间内
  // 无条件再次拉起，避免读 IIR 造成中断风暴。
  val threPending = RegInit(false.B)
  val threLevel = ier(1) && thrEmpty
  val ThreLevelPrevious = RegNext(threLevel, false.B)
  val threRise = threLevel && !ThreLevelPrevious

  // 接收 FIFO 采用移位寄存器实现：深度小，读口组合输出队首，便于
  // AXIRAM 在 AR 握手当拍取得读数据。
  val rxQueue = RegInit(VecInit(Seq.fill(Depth)(0.U(8.W))))
  val rxCount = RegInit(0.U(log2Ceil(Depth + 1).W))
  val overrun = RegInit(false.B)

  val FIFOHasData = rxCount =/= 0.U
  val FIFOHasSpace = rxCount < Depth.U
  io.rxReady := FIFOHasSpace

  // RX 数据可用中断保持原来的电平语义。
  val rdaPending = ier(0) && FIFOHasData

  // rxCount 可以等于 Depth（FIFO 满），需要多一位表示；写指针只取
  // 低 log2Ceil(Depth) 位，避免 Chisel 的动态索引越界警告。
  private def queueIndex(value: UInt): UInt =
    if (Depth == 1) 0.U else value(log2Ceil(Depth) - 1, 0)

  val doPop =
    io.ReadEnable && rdOffset === 0.U && !lcr(7) && FIFOHasData
  val doPush = io.rxValid && FIFOHasSpace

  when(doPop) {
    // 队首被读走，其余元素前移。若同拍还有注入，新字节放到新的队尾。
    for (index <- 0 until Depth - 1) {
      rxQueue(index) := Mux((index + 1).U < rxCount, rxQueue(index + 1), 0.U)
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
  when(io.rxValid && !FIFOHasSpace) {
    overrun := true.B
  }

  // 写寄存器。偏移 0/1 在 DLAB=1 时是波特率除数锁存器，不能当 THR 输出。
  when(io.WriteEnable) {
    when(wrOffset === 0.U) {
      when(lcr(7)) { divisorLow := io.wrData }
        .otherwise {
          // THR：直接打印到仿真 stdout，保持原有 UART TX 行为；同时进入
          // THRE=0、TEMT=0 的发送窗口，并清除尚未处理的 THRE pending。
          printf("%c", io.wrData)
          thrHold := TxHoldCycles.U
          txShift := TxShiftCycles.U
          threPending := false.B
        }
    }.elsewhen(wrOffset === 1.U) {
      when(lcr(7)) { divisorHigh := io.wrData }
        .otherwise { ier := io.wrData(3, 0) }
    }.elsewhen(wrOffset === 2.U) {
      fcr := io.wrData
      // FCR bit1 只复位接收 FIFO；bit2 只复位发送侧状态，二者不能
      // 互相误清。
      when(io.wrData(1)) {
        rxCount := 0.U
        overrun := false.B
      }
      when(io.wrData(2)) {
        thrHold := 0.U
        txShift := 0.U
        threPending := false.B
      }
    }.elsewhen(wrOffset === 3.U) {
      lcr := io.wrData
    }.elsewhen(wrOffset === 4.U) {
      mcr := io.wrData
    }.elsewhen(wrOffset === 7.U) {
      scr := io.wrData
    }
  }

  // 读寄存器。LSR 的 DataReady 反映 FIFO 是否非空；THRE/TEMT 由发送侧
  // 计数器驱动，写 THR 后先拉低 THRE，随后 THRE 先于 TEMT 恢复。
  val lsr = Cat(
    false.B, // bit7：错误（无）
    temt, // bit6：TEMT，THR 与移位器均空
    thrEmpty, // bit5：THRE，THR 可写
    false.B, // bit4：break
    false.B, // bit3：framing error
    false.B, // bit2：parity error
    overrun, // bit1：overrun
    FIFOHasData // bit0：Data Ready
  )
  // IIR：bit0=0 表示有中断；bits[3:1]=010 表示接收数据可用，
  // 001 表示 THRE。RX 数据可用优先级高于 THRE。无中断时 bit0=1，
  // bits[7:6] 跟随 FCR 的 FIFO 使能位（复位后为 0x01）。
  val iir = Mux(
    rdaPending,
    Cat(fcr(0), fcr(0), 0.U(2.W), "b010".U(3.W), 0.U(1.W)),
    Mux(
      ier(1) && threPending,
      Cat(fcr(0), fcr(0), 0.U(2.W), "b001".U(3.W), 0.U(1.W)),
      Cat(fcr(0), fcr(0), "b000001".U(6.W))
    )
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

  // THRE pending 更新。写 THR 当拍必须优先清除，不能被同一拍的
  // THRE 上升沿重新置位（否则 THR 已忙却报告 THRE 中断）。
  // RX 数据可用同时挂起时读 IIR 看到的是 010，THRE pending 必须保留。
  val thrWrite = io.WriteEnable && wrOffset === 0.U && !lcr(7)
  when(threRise && !thrWrite) {
    threPending := true.B
  }
  when(!ier(1)) {
    threPending := false.B
  }
  // DLAB 只改变偏移 0/1 的解释，偏移 2 始终是 IIR。
  when(
    io.ReadEnable && rdOffset === 2.U &&
      threPending && !rdaPending
  ) {
    threPending := false.B
  }

  // LSR 读清除 overrun。注意 rdData 在时钟沿前仍返回旧值。
  when(io.ReadEnable && rdOffset === 5.U) {
    overrun := false.B
  }

  // RX 数据可用是电平中断，THRE 是挂起式中断；两者都接到 PLIC source 10。
  io.IRQ := rdaPending || (ier(1) && threPending)
}
