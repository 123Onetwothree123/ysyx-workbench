package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
case class ysyx_26030103_DIVStepResult(Remainder: UInt, Quotient: UInt)
abstract class ysyx_26030103_DIVCoreShell(
    config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_DIVCore(config.XLEN)
    with ysyx_26030103_IterativeCore[ysyx_26030103_DIVCoreRequest, ysyx_26030103_DIVCoreResponse] {
  protected val params = config.MDUProfile.DIV.For(config.DIVImpl)
  protected val coreWidth = config.XLEN
  protected val radix = params.Radix
  protected val digitBits = ysyx_26030103_MULBoothConfig.BoothBits(radix)
  protected val iterationsPerCycle = params.IterBits
  protected val cycleDigitBits = digitBits * iterationsPerCycle
  protected val cycleCount = (coreWidth + cycleDigitBits - 1) / cycleDigitBits
  protected val paddedBits = cycleCount * cycleDigitBits
  protected def remainderWidth: Int
  protected def quotientWidth: Int = coreWidth
  protected def divisorWidth: Int = coreWidth
  protected val counterWidth = log2Ceil(cycleCount + 1).max(1)
  protected val divZero = RegInit(false.B)
  protected val remainderReg = RegInit(0.U(remainderWidth.W))
  protected val dividendReg = RegInit(0.U(paddedBits.W))
  protected val divisorReg = RegInit(0.U(divisorWidth.W))
  protected val quotientReg = RegInit(0.U(quotientWidth.W))
  protected val originalDividend = RegInit(0.U(coreWidth.W))
  protected val count = RegInit(0.U(counterWidth.W))
  protected val quotientResult = RegInit(0.U(coreWidth.W))
  protected val remainderResult = RegInit(0.U(coreWidth.W))
  protected val iterationResult = RegInit(0.U(16.W))
  protected val earlyOutResult = RegInit(false.B)
  protected def step(): ysyx_26030103_DIVStepResult
  protected def CorrectedQuotient(alignedQuotient: UInt, nextRemainder: UInt): UInt = alignedQuotient
  protected def CorrectedRemainder(nextRemainder: UInt): UInt = nextRemainder
  protected def EarlyDone(nextRemainder: UInt, nextDividend: UInt): Bool
  protected def InitialDivisor: UInt
  override protected def IterativeRequest: DecoupledIO[ysyx_26030103_DIVCoreRequest] = IO.Request
  override protected def IterativeResponse: DecoupledIO[ysyx_26030103_DIVCoreResponse] = IO.Response
  override protected def IterativeFlush: Bool = IO.Flush
  IO.IterationCount := iterationResult
  IO.EarlyOut := earlyOutResult
  IterativeHandshake()
  IO.Response.bits.Quotient := quotientResult
  IO.Response.bits.Remainder := remainderResult
  private val stepResult = step()
  private val nextRemainder = stepResult.Remainder
  private val nextQuotient = stepResult.Quotient
  private val nextDividend = (dividendReg << cycleDigitBits)(paddedBits - 1, 0)
  private val earlyFinish = if (params.EarlyOut) EarlyDone(nextRemainder, nextDividend) else false.B
  private val remainingCycles = (cycleCount - 1).U(counterWidth.W) - count
  private val remainingShift = remainingCycles * cycleDigitBits.U
  private val alignedEarlyQuotient = (nextQuotient << remainingShift)(quotientWidth - 1, 0)
  private val finalQuotient = Mux(earlyFinish, alignedEarlyQuotient, nextQuotient)
  private val lastIteration = count === (cycleCount - 1).U(counterWidth.W)
  RunIterative(
    () => {
      divZero := false.B
      remainderReg := 0.U
      dividendReg := 0.U
      divisorReg := 0.U
      quotientReg := 0.U
      originalDividend := 0.U
      count := 0.U
      quotientResult := 0.U
      remainderResult := 0.U
      iterationResult := 0.U
      earlyOutResult := false.B
    },
    () => {
      divZero := IO.Request.bits.DivisorMagnitude === 0.U
      remainderReg := 0.U
      if (paddedBits == coreWidth) {
        dividendReg := IO.Request.bits.DividendMagnitude
      } else {
        dividendReg := Cat(0.U((paddedBits - coreWidth).W), IO.Request.bits.DividendMagnitude)
      }
      divisorReg := InitialDivisor
      quotientReg := 0.U
      originalDividend := IO.Request.bits.DividendMagnitude
      count := 0.U
      iterationResult := 0.U
      earlyOutResult := false.B
    },
    () => {
      when(divZero) {
        busy := false.B
        resultValid := true.B
        quotientResult := Fill(coreWidth, 1.U)
        remainderResult := originalDividend
        iterationResult := 0.U
        earlyOutResult := false.B
      }.otherwise {
        remainderReg := nextRemainder
        dividendReg := nextDividend
        quotientReg := nextQuotient
        when(lastIteration || earlyFinish) {
          busy := false.B
          resultValid := true.B
          quotientResult := CorrectedQuotient(finalQuotient, nextRemainder)(coreWidth - 1, 0)
          remainderResult := CorrectedRemainder(nextRemainder)(coreWidth - 1, 0)
          iterationResult :=
            ((count + 1.U) * cycleDigitBits.U).pad(16)
          earlyOutResult := earlyFinish
        }.otherwise {
          count := count + 1.U
        }
      }
    }
  )
}
