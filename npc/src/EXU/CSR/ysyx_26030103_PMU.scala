package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._

/** RISC-V HPM 计数器组（mhpmcounter3..31 / mhpmevent3..31）。
  *
  * 计数器与事件选择器均为软件可读写；事件编号由
  * `ysyx_26030103_PMUEventId` 定义。Sscofpmf 打开时，mhpmevent 的最高
  * 四位作为 OF/MINH/SINH/UINH，并在 64 位计数回绕时置 OF。
  */
class ysyx_26030103_PMU(
    val XLEN: Int = 64,
    val EnableSscofpmf: Boolean = true
) extends Module {
  require(XLEN == 32 || XLEN == 64, s"PMU XLEN 非法: $XLEN")
  private val CounterCount = ysyx_26030103_PMUEventId.CounterCount
  private val EventBusWidth = ysyx_26030103_PMUEventId.BusWidth
  private val CounterBase = 0xb03
  private val CounterHighBase = 0xb83
  private val EventBase = 0x323
  private val CountOvf = 0xda0
  private val OfBit = XLEN - 1
  private val MinhBit = XLEN - 2
  private val SinhBit = XLEN - 3
  private val UinhBit = XLEN - 4
  private val OfMask = (BigInt(1) << OfBit).U(XLEN.W)

  val io = IO(new Bundle {
    val events = Input(UInt(EventBusWidth.W))
    val privilege = Input(UInt(2.W))
    val csrAddr = Input(UInt(12.W))
    val csrWen = Input(Bool())
    val csrWdata = Input(UInt(XLEN.W))
    val mcountinhibit = Input(UInt(32.W))
    // S 模式可见的 scountovf 位必须受 mcounteren 门控；M 模式保持完整视图。
    val counteren = Input(UInt(32.W))
    // 软件向 mip.LCOFI 写 0 时清除所有 mhpmevent 的 OF 汇总位。
    val lcofiClear = Input(Bool())
    val readData = Output(UInt(XLEN.W))
    val scountovf = Output(UInt(32.W))
    val lcofiPending = Output(Bool())
  })

  val Counters = RegInit(VecInit(Seq.fill(CounterCount)(0.U(64.W))))
  val Events = RegInit(VecInit(Seq.fill(CounterCount)(0.U(XLEN.W))))

  val ReadData = WireDefault(0.U(XLEN.W))
  val OverflowBits = WireDefault(VecInit(Seq.fill(CounterCount)(false.B)))
  for (i <- 0 until CounterCount) {
    val LowAddr = (CounterBase + i).U(12.W)
    val HighAddr = (CounterHighBase + i).U(12.W)
    val UserLowAddr = (0xc03 + i).U(12.W)
    val UserHighAddr = (0xc83 + i).U(12.W)
    val EventAddr = (EventBase + i).U(12.W)
    val EventWrite = io.csrWen && io.csrAddr === EventAddr
    val LowWrite = io.csrWen && io.csrAddr === LowAddr
    val HighWrite =
      if (XLEN == 32) io.csrWen && io.csrAddr === HighAddr else false.B

    val EventId = Events(i)(7, 0)
    val EventHighZero =
      Events(i)(7, 5) === 0.U && Events(i)(15, 8) === 0.U
    val EventIndex = Events(i)(4, 0)
    val EventFire = EventId =/= 0.U && EventHighZero && io.events(EventIndex)
    val ModeInhibit =
      if (EnableSscofpmf)
        Mux(io.privilege === 3.U, Events(i)(MinhBit),
          Mux(io.privilege === 1.U, Events(i)(SinhBit),
            Mux(io.privilege === 0.U, Events(i)(UinhBit), false.B)))
      else false.B
    val Increment = !LowWrite && !HighWrite &&
      !io.mcountinhibit(3 + i) && !ModeInhibit && EventFire
    val Overflow = EnableSscofpmf.B && Increment &&
      Counters(i) === "hffffffffffffffff".U(64.W)

    when(EventWrite) {
      Events(i) := io.csrWdata
    }.elsewhen(Overflow) {
      Events(i) := Events(i) | OfMask
    }.elsewhen(io.lcofiClear) {
      // mip.LCOFI 写 0：清除该计数器的 overflow 汇总位。
      Events(i) := Events(i) & ~OfMask
    }

    when(LowWrite) {
      if (XLEN == 64) {
        Counters(i) := io.csrWdata
      } else {
        Counters(i) := Cat(Counters(i)(63, 32), io.csrWdata(31, 0))
      }
    }.elsewhen(HighWrite) {
      Counters(i) := Cat(io.csrWdata(31, 0), Counters(i)(31, 0))
    }.elsewhen(Increment) {
      Counters(i) := Counters(i) + 1.U
    }

    when(io.csrAddr === LowAddr || io.csrAddr === UserLowAddr) {
      ReadData := (if (XLEN == 64) Counters(i)
                   else Counters(i)(31, 0))
    }
    if (XLEN == 32) {
      when(io.csrAddr === HighAddr || io.csrAddr === UserHighAddr) {
        ReadData := Counters(i)(63, 32)
      }
    }
    when(io.csrAddr === EventAddr) {
      ReadData := Events(i)
    }
    OverflowBits(i) := Events(i)(OfBit)
  }

  val Scountovf = Cat(OverflowBits.asUInt, 0.U(3.W))
  // S/U 模式读 scountovf 时，只有 mcounteren 允许的对应计数器位可见；
  // M 模式保留全部 overflow 位。低 3 位本来就没有对应计数器。
  val VisibleScountovf =
    (if (EnableSscofpmf) Scountovf else 0.U(32.W)) &
      Mux(io.privilege === 3.U, "hffffffff".U(32.W), io.counteren)
  val ScountovfWide =
    if (XLEN == 32) VisibleScountovf
    else Cat(0.U((XLEN - 32).W), VisibleScountovf)
  when(io.csrAddr === CountOvf.U(12.W)) {
    ReadData := ScountovfWide
  }
  io.readData := ReadData
  io.scountovf := VisibleScountovf
  io.lcofiPending :=
    (if (EnableSscofpmf) OverflowBits.reduce(_ || _) else false.B)
}
