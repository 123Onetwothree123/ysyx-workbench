package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
class ysyx_26030103_DIVRestoringCore(
    config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_DIVCoreShell(config) {
  override protected def remainderWidth: Int = coreWidth + digitBits
  override protected def divisorWidth: Int = remainderWidth
  override protected def InitialDivisor: UInt = Cat(0.U(digitBits.W), IO.Request.bits.DivisorMagnitude)
  override protected def step(): ysyx_26030103_DIVStepResult = {
    val inputBits = dividendReg(paddedBits - 1, paddedBits - cycleDigitBits)
    var selectedRemainder: UInt = remainderReg
    var quotientChunk: UInt = 0.U(cycleDigitBits.W)
    for (iteration <- 0 until iterationsPerCycle) {
      val chunkHigh = cycleDigitBits - iteration * digitBits - 1
      val chunkLow = cycleDigitBits - (iteration + 1) * digitBits
      val inputChunk = inputBits(chunkHigh, chunkLow)
      val shiftedRemainder = selectedRemainder << digitBits
      val trialRemainder = Cat(shiftedRemainder(remainderWidth - 1, digitBits), inputChunk)
      var selectedDigitRemainder: UInt = trialRemainder
      var quotientDigit: UInt = 0.U(digitBits.W)
      for (_ <- 0 until radix - 1) {
        val canSubtract = selectedDigitRemainder >= divisorReg
        val subtracted = selectedDigitRemainder - divisorReg
        val incremented = (quotientDigit +& 1.U)(digitBits - 1, 0)
        selectedDigitRemainder = Mux(canSubtract, subtracted, selectedDigitRemainder)
        quotientDigit = Mux(canSubtract, incremented, quotientDigit)
      }
      val quotientDigitWide = if (cycleDigitBits == digitBits) quotientDigit else Cat(0.U((cycleDigitBits - digitBits).W), quotientDigit)
      quotientChunk = ((quotientChunk << digitBits)(cycleDigitBits - 1, 0) | quotientDigitWide)
      selectedRemainder = selectedDigitRemainder
    }
    val quotientChunkWide = if (cycleDigitBits >= coreWidth) quotientChunk(coreWidth - 1, 0) else Cat(0.U((coreWidth - cycleDigitBits).W), quotientChunk)
    val nextQuotient = ((quotientReg << cycleDigitBits) | quotientChunkWide)(coreWidth - 1, 0)
    ysyx_26030103_DIVStepResult(selectedRemainder, nextQuotient)
  }
  override protected def EarlyDone(nextRemainder: UInt, nextDividend: UInt): Bool =
    nextDividend === 0.U && nextRemainder === 0.U
}
