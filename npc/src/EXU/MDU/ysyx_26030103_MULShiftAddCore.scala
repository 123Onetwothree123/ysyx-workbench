package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
class ysyx_26030103_MULShiftAddCore(
    config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_MULCore(config.MULEffectiveWidth)
    with ysyx_26030103_IterativeCore[ysyx_26030103_MULCoreRequest, ysyx_26030103_MULCoreResponse] {
  private val operandWidth = config.MULEffectiveWidth
  private val productWidth = 2 * operandWidth
  private val useBooth = config.MDUProfile.MUL.Encoding == ysyx_26030103_MULEncoding.Booth
  private val boothRadix = if (useBooth) config.MDUProfile.MUL.Radix else 2
  private val boothBits = if (useBooth) ysyx_26030103_MULBoothConfig.BoothBits(boothRadix) else 1
  private val baseGroupsPerCycle = if (useBooth) (config.MDUProfile.MUL.ShiftAdd.IterBits + boothBits - 1) / boothBits else 1
  private val groupsPerCycle = if (useBooth) ((baseGroupsPerCycle + config.MDUProfile.MUL.ShiftAdd.Split - 1) / config.MDUProfile.MUL.ShiftAdd.Split).max(1) else 1
  private val cycleBits = if (useBooth) groupsPerCycle * boothBits else ((config.MDUProfile.MUL.ShiftAdd.IterBits + config.MDUProfile.MUL.ShiftAdd.Split - 1) / config.MDUProfile.MUL.ShiftAdd.Split).max(1)
  private val totalGroups = if (useBooth) (operandWidth + 1 + boothBits - 1) / boothBits else operandWidth
  private val cycleCount = if (useBooth) (totalGroups + groupsPerCycle - 1) / groupsPerCycle else (operandWidth + cycleBits - 1) / cycleBits
  private val paddedGroups = if (useBooth) cycleCount * groupsPerCycle else 0
  private val internalWidth = if (useBooth) 2 * (operandWidth + boothBits) else productWidth
  private val encoderWidth = operandWidth + boothBits
  private val encoderProductWidth = ysyx_26030103_MULBoothConfig.PartialProductWidth(boothRadix, operandWidth)
  private val multiplierWidth = if (useBooth) paddedGroups * boothBits + 1 else cycleCount * cycleBits
  private val counterWidth = log2Ceil(cycleCount + 1).max(1)
  private val shiftWidth = log2Ceil(internalWidth + 1).max(1)
  private val accumulator = RegInit(0.U(internalWidth.W))
  private val multiplicand = RegInit(0.U(internalWidth.W))
  private val multiplier = RegInit(0.U(multiplierWidth.W))
  private val iteration = RegInit(0.U(counterWidth.W))
  private val boothShift = RegInit(0.U(shiftWidth.W))
  private val result = RegInit(0.U(productWidth.W))
  override protected def IterativeRequest: DecoupledIO[ysyx_26030103_MULCoreRequest] = IO.Request
  override protected def IterativeResponse: DecoupledIO[ysyx_26030103_MULCoreResponse] = IO.Response
  override protected def IterativeFlush: Bool = IO.Flush
  IterativeHandshake()
  IO.Response.bits.Product := result
  val currentAddend = Wire(UInt(internalWidth.W))
  currentAddend := 0.U
  if (useBooth) {
    var boothSum: UInt = 0.U(internalWidth.W)
    for (group <- 0 until groupsPerCycle) {
      val encoder = Module(new ysyx_26030103_MULBoothEncoder(boothRadix, operandWidth))
      val windowLow = group * boothBits
      encoder.IO.Multiplicand := multiplicand(encoderWidth - 1, 0)
      encoder.IO.Window := multiplier(windowLow + boothBits, windowLow)
      val shiftAmount = boothShift + (group * boothBits).U(shiftWidth.W)
      val signedPartialProduct = Cat(
        Fill(internalWidth - encoderProductWidth, encoder.IO.PartialProduct(encoderProductWidth - 1)),
        encoder.IO.PartialProduct
      )
      val shiftedPartialProduct = signedPartialProduct << shiftAmount
      val partialProduct = shiftedPartialProduct(internalWidth - 1, 0)
      boothSum = (boothSum +& partialProduct)(internalWidth - 1, 0)
    }
    currentAddend := boothSum
  } else {
    var plainSum: UInt = 0.U(internalWidth.W)
    for (bit <- 0 until cycleBits) {
      val shifted = (multiplicand << bit)(internalWidth - 1, 0)
      val addend = Mux(multiplier(bit), shifted, 0.U(internalWidth.W))
      plainSum = (plainSum +& addend)(internalWidth - 1, 0)
    }
    currentAddend := plainSum
  }
  val nextAccumulator = (accumulator +& currentAddend)(internalWidth - 1, 0)
  val nextMultiplier = multiplier >> cycleBits
  val lastIteration = iteration === (cycleCount - 1).U(counterWidth.W)
  val earlyFinish = if (config.MDUProfile.MUL.ShiftAdd.EarlyOut) nextMultiplier === 0.U else false.B
  RunIterative(
    () => {
      accumulator := 0.U
      multiplicand := 0.U
      multiplier := 0.U
      iteration := 0.U
      boothShift := 0.U
      result := 0.U
    },
    () => {
      accumulator := 0.U
      multiplicand := Cat(0.U((internalWidth - operandWidth).W), IO.Request.bits.LHSMagnitude)
      if (useBooth) {
        val paddingWidth = paddedGroups * boothBits - operandWidth
        multiplier := Cat(0.U(paddingWidth.W), IO.Request.bits.RHSMagnitude, 0.U(1.W))
      } else {
        val paddingWidth = cycleCount * cycleBits - operandWidth
        val multiplierValue = if (paddingWidth == 0) {
          IO.Request.bits.RHSMagnitude
        } else {
          Cat(0.U(paddingWidth.W), IO.Request.bits.RHSMagnitude)
        }
        multiplier := multiplierValue
      }
      iteration := 0.U
      boothShift := 0.U
    },
    () => {
      if (!useBooth) {
        multiplicand := (multiplicand << cycleBits)(internalWidth - 1, 0)
      } else {
        boothShift := boothShift + cycleBits.U(shiftWidth.W)
      }
      multiplier := nextMultiplier
      when(lastIteration || earlyFinish) {
        busy := false.B
        resultValid := true.B
        result := nextAccumulator(productWidth - 1, 0)
        accumulator := nextAccumulator
      }.otherwise {
        accumulator := nextAccumulator
        iteration := iteration + 1.U
      }
    }
  )
}
