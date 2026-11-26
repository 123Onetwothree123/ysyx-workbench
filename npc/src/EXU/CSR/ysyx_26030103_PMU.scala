package ysyx_26030103.exu
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
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
    val XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    val EnableSscofpmf: Boolean = true
) extends Module {
  ysyx_26030103_XLENRequire(XLEN, "PMU")
  private val counterCount = ysyx_26030103_PMUEventID.CounterCount
  private val eventBusWidth = ysyx_26030103_PMUEventID.BusWidth
  private val ofBit = XLEN - 1
  private val minhBit = XLEN - 2
  private val sinhBit = XLEN - 3
  private val uinhBit = XLEN - 4
  private val ofMask = (BigInt(1) << ofBit).U(XLEN.W)

  val io = IO(new Bundle {
    val events = Input(UInt(eventBusWidth.W))
    val privilege = Input(UInt(2.W))
    val CSRAddress = Input(UInt(12.W))
    val CSRWriteEnable = Input(Bool())
    val CSRWriteData = Input(UInt(XLEN.W))
    val mcountinhibit = Input(UInt(32.W))
    // S 模式可见的 scountovf 位必须受 mcounteren 门控；M 模式保持完整视图。
    val counteren = Input(UInt(32.W))
    // 软件向 mip.LCOFI 写 0 时清除所有 mhpmevent 的 OF 汇总位。
    val lcofiClear = Input(Bool())
    val readData = Output(UInt(XLEN.W))
    val scountovf = Output(UInt(32.W))
    val lcofiPending = Output(Bool())
  })

  val counters = RegInit(VecInit(Seq.fill(counterCount)(0.U(64.W))))
  val events = RegInit(VecInit(Seq.fill(counterCount)(0.U(XLEN.W))))

  val readData = WireDefault(0.U(XLEN.W))
  val overflowBits = WireDefault(VecInit(Seq.fill(counterCount)(false.B)))
  for (index <- 0 until counterCount) {
    val lowAddress = (ysyx_26030103_PMUCSR.CounterBase + index).U(12.W)
    val highAddress = (ysyx_26030103_PMUCSR.CounterHighBase + index).U(12.W)
    val userLowAddress = (ysyx_26030103_PMUCSR.UserCounterBase + index).U(12.W)
    val userHighAddress =
      (ysyx_26030103_PMUCSR.UserCounterHighBase + index).U(12.W)
    val eventAddress = (ysyx_26030103_PMUCSR.EventBase + index).U(12.W)
    val eventWrite = io.CSRWriteEnable && io.CSRAddress === eventAddress
    val lowWrite = io.CSRWriteEnable && io.CSRAddress === lowAddress
    val highWrite =
      if (XLEN == 32) io.CSRWriteEnable && io.CSRAddress === highAddress else false.B

    val EventID = events(index)(7, 0)
    val eventHighZero =
      events(index)(7, 5) === 0.U && events(index)(15, 8) === 0.U
    val eventIndex = events(index)(4, 0)
    val eventFire = EventID =/= 0.U && eventHighZero && io.events(eventIndex)
    val modeInhibit =
      if (EnableSscofpmf)
        Mux(io.privilege === 3.U, events(index)(minhBit),
          Mux(io.privilege === 1.U, events(index)(sinhBit),
            Mux(io.privilege === 0.U, events(index)(uinhBit), false.B)))
      else false.B
    val increment = !lowWrite && !highWrite &&
      !io.mcountinhibit(3 + index) && !modeInhibit && eventFire
    val overflow = EnableSscofpmf.B && increment &&
      counters(index) === "hffffffffffffffff".U(64.W)

    when(eventWrite) {
      events(index) := io.CSRWriteData
    }.elsewhen(overflow) {
      events(index) := events(index) | ofMask
    }.elsewhen(io.lcofiClear) {
      // mip.LCOFI 写 0：清除该计数器的 overflow 汇总位。
      events(index) := events(index) & ~ofMask
    }

    when(lowWrite) {
      if (XLEN == 64) {
        counters(index) := io.CSRWriteData
      } else {
        counters(index) := Cat(counters(index)(63, 32), io.CSRWriteData(31, 0))
      }
    }.elsewhen(highWrite) {
      counters(index) := Cat(io.CSRWriteData(31, 0), counters(index)(31, 0))
    }.elsewhen(increment) {
      counters(index) := counters(index) + 1.U
    }

    when(io.CSRAddress === lowAddress || io.CSRAddress === userLowAddress) {
      readData := (if (XLEN == 64) counters(index)
                   else counters(index)(31, 0))
    }
    if (XLEN == 32) {
      when(io.CSRAddress === highAddress || io.CSRAddress === userHighAddress) {
        readData := counters(index)(63, 32)
      }
    }
    when(io.CSRAddress === eventAddress) {
      readData := events(index)
    }
    overflowBits(index) := events(index)(ofBit)
  }

  val scountovf = Cat(overflowBits.asUInt, 0.U(3.W))
  // S/U 模式读 scountovf 时，只有 mcounteren 允许的对应计数器位可见；
  // M 模式保留全部 overflow 位。低 3 位本来就没有对应计数器。
  val visibleScountovf =
    (if (EnableSscofpmf) scountovf else 0.U(32.W)) &
      Mux(io.privilege === 3.U, "hffffffff".U(32.W), io.counteren)
  val scountovfWide =
    if (XLEN == 32) visibleScountovf
    else Cat(0.U((XLEN - 32).W), visibleScountovf)
  when(io.CSRAddress === ysyx_26030103_PMUCSR.CountOvf.U(12.W)) {
    readData := scountovfWide
  }
  io.readData := readData
  io.scountovf := visibleScountovf
  io.lcofiPending :=
    (if (EnableSscofpmf) overflowBits.reduce(_ || _) else false.B)
}
