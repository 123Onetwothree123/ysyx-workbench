package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
class ysyx_26030103_DIVSRTCore(
    config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_DIVCoreShell(config) {
  override protected def remainderWidth: Int = digitBits + coreWidth + 2
  override protected def quotientWidth: Int = coreWidth + 2
  override protected def InitialDivisor: UInt = IO.Request.bits.DivisorMagnitude
  override protected def step(): ysyx_26030103_DIVStepResult = {
    val quotientDigitWidth = digitBits + 1
    val inputBits = dividendReg(paddedBits - 1, paddedBits - cycleDigitBits)
    val divisorWide = Cat(0.U((remainderWidth - coreWidth).W), divisorReg)
    var selectedRemainder: SInt = remainderReg.asSInt
    var selectedQuotient: SInt = quotientReg.asSInt
    for (iteration <- 0 until iterationsPerCycle) {
      val chunkHigh = cycleDigitBits - iteration * digitBits - 1
      val chunkLow = cycleDigitBits - (iteration + 1) * digitBits
      val inputChunk = inputBits(chunkHigh, chunkLow)
      val shiftedRemainder = (selectedRemainder << digitBits)(remainderWidth - 1, 0).asSInt
      val inputWide = Cat(0.U((remainderWidth - digitBits).W), inputChunk).asSInt
      val trialRemainder = (shiftedRemainder +& inputWide)(remainderWidth - 1, 0).asSInt
      val trialNegative = trialRemainder < 0.S
      val trialBits = trialRemainder.asUInt
      val absoluteTrial = Mux(
        trialNegative,
        (~trialBits + 1.U(remainderWidth.W))(remainderWidth - 1, 0),
        trialBits
      )
      var remainingMagnitude: UInt = absoluteTrial
      var quotientMagnitude: UInt = 0.U(digitBits.W)
      var scaledDivisor: UInt = 0.U(remainderWidth.W)
      for (bit <- (digitBits - 1) to 0 by -1) {
        val shiftedDivisor = (divisorWide << bit)(remainderWidth - 1, 0)
        val canTake = remainingMagnitude >= shiftedDivisor
        val nextRemaining = (remainingMagnitude - shiftedDivisor)(remainderWidth - 1, 0)
        val bitMask = (1.U(digitBits.W) << bit)(digitBits - 1, 0)
        val nextQuotientMagnitude = Mux(canTake, quotientMagnitude | bitMask, quotientMagnitude)
        val nextScaledDivisor = Mux(
          canTake,
          (scaledDivisor +& shiftedDivisor)(remainderWidth - 1, 0),
          scaledDivisor
        )
        remainingMagnitude = Mux(canTake, nextRemaining, remainingMagnitude)
        quotientMagnitude = nextQuotientMagnitude
        scaledDivisor = nextScaledDivisor
      }
      val maxDigit = ~0.U(digitBits.W)
      val doubleRemainder = (remainingMagnitude << 1)(remainderWidth - 1, 0)
      val roundUp = quotientMagnitude =/= maxDigit && doubleRemainder >= divisorWide
      val roundedQuotientMagnitude = Mux(
        roundUp,
        (quotientMagnitude +& 1.U)(digitBits - 1, 0),
        quotientMagnitude
      )
      val roundedScaledDivisor = Mux(
        roundUp,
        (scaledDivisor +& divisorWide)(remainderWidth - 1, 0),
        scaledDivisor
      )
      val quotientMagnitudeExtended = Cat(0.U(1.W), roundedQuotientMagnitude)
      val negativeQuotientMagnitude = (~quotientMagnitudeExtended + 1.U(quotientDigitWidth.W))(quotientDigitWidth - 1, 0)
      val quotientDigitBits = Mux(
        trialNegative,
        negativeQuotientMagnitude,
        quotientMagnitudeExtended
      )
      val positiveScaledDivisor = roundedScaledDivisor.asSInt
      val positiveNextRemainder = (trialRemainder - positiveScaledDivisor)(remainderWidth - 1, 0).asSInt
      val negativeNextRemainder = (trialRemainder + positiveScaledDivisor)(remainderWidth - 1, 0).asSInt
      val nextRemainderDigit = Mux(trialNegative, negativeNextRemainder, positiveNextRemainder)
      val quotientDigitForAccumulatorBits =
        Cat(Fill(quotientWidth - quotientDigitWidth, quotientDigitBits(quotientDigitWidth - 1)), quotientDigitBits)
      val quotientDigitForAccumulator = quotientDigitForAccumulatorBits.asSInt
      val shiftedQuotient = (selectedQuotient << digitBits)(quotientWidth - 1, 0).asSInt
      val nextQuotientDigit = (shiftedQuotient +& quotientDigitForAccumulator)(quotientWidth - 1, 0).asSInt
      selectedRemainder = nextRemainderDigit
      selectedQuotient = nextQuotientDigit
    }
    ysyx_26030103_DIVStepResult(selectedRemainder.asUInt, selectedQuotient.asUInt)
  }
  override protected def CorrectedQuotient(alignedQuotient: UInt, nextRemainder: UInt): UInt = {
    val finalNegative = nextRemainder.asSInt < 0.S
    val quotientSigned = alignedQuotient.asSInt
    Mux(
      finalNegative,
      (quotientSigned - 1.S(quotientWidth.W))(quotientWidth - 1, 0).asSInt,
      quotientSigned
    ).asUInt
  }
  override protected def CorrectedRemainder(nextRemainder: UInt): UInt = {
    val remainderSigned = nextRemainder.asSInt
    val divisorWide = Cat(0.U((remainderWidth - coreWidth).W), divisorReg)
    Mux(
      remainderSigned < 0.S,
      (remainderSigned + divisorWide.asSInt)(remainderWidth - 1, 0).asSInt,
      remainderSigned
    ).asUInt
  }
  override protected def EarlyDone(nextRemainder: UInt, nextDividend: UInt): Bool =
    nextDividend === 0.U && nextRemainder.asSInt === 0.S
}
