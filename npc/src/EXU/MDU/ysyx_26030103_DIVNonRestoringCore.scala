package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
class ysyx_26030103_DIVNonRestoringCore(
    config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_DIVCoreShell(config) {
  override protected def remainderWidth: Int = coreWidth + 2
  override protected def InitialDivisor: UInt = IO.Request.bits.DivisorMagnitude
  override protected def step(): ysyx_26030103_DIVStepResult = {
    val inputBits = dividendReg(paddedBits - 1, paddedBits - cycleDigitBits)
    val divisorExtended = Cat(0.U(2.W), divisorReg)
    var selectedRemainder: UInt = remainderReg
    var quotientChunk: UInt = 0.U(cycleDigitBits.W)
    for (step <- 0 until cycleDigitBits) {
      val inputBit = inputBits(cycleDigitBits - step - 1)
      val shiftedRemainder = Cat(selectedRemainder(remainderWidth - 2, 0), inputBit)
      val subtracted = (shiftedRemainder - divisorExtended)(remainderWidth - 1, 0)
      val added = (shiftedRemainder +& divisorExtended)(remainderWidth - 1, 0)
      val nextRemainder = Mux(selectedRemainder(remainderWidth - 1), added, subtracted)
      val quotientBit = !nextRemainder(remainderWidth - 1)
      val shiftedQuotientChunk = (quotientChunk << 1)(cycleDigitBits - 1, 0)
      quotientChunk = shiftedQuotientChunk | quotientBit.asUInt
      selectedRemainder = nextRemainder
    }
    val quotientChunkWide = if (cycleDigitBits >= coreWidth) quotientChunk(coreWidth - 1, 0) else Cat(0.U((coreWidth - cycleDigitBits).W), quotientChunk)
    val nextQuotient = ((quotientReg << cycleDigitBits) | quotientChunkWide)(coreWidth - 1, 0)
    ysyx_26030103_DIVStepResult(selectedRemainder, nextQuotient)
  }
  override protected def CorrectedRemainder(nextRemainder: UInt): UInt = {
    val divisorExtended = Cat(0.U(2.W), divisorReg)
    Mux(
      nextRemainder(remainderWidth - 1),
      (nextRemainder +& divisorExtended)(remainderWidth - 1, 0),
      nextRemainder
    )
  }
  override protected def EarlyDone(nextRemainder: UInt, nextDividend: UInt): Bool =
    nextDividend === 0.U && nextRemainder === 0.U
}
