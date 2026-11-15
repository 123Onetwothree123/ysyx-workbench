package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
//参数化SRT除法器，使用精确比较选择冗余商数字。
class ysyx_26030103_DIVSRTCore(
    config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig() //配置
) extends ysyx_26030103_DIVCore(config.XLEN) {
  private val params = config.MDUProfile.DIV.For(config.DIVImpl)
  private val coreWidth = config.XLEN
  private val radix = params.Radix //除法基数
  private val digitBits = ysyx_26030103_MULBoothConfig.BoothBits(radix) //单个基数数字位数
  private val iterationsPerCycle = params.IterBits //每拍处理的基数数字数
  private val cycleDigitBits = digitBits * iterationsPerCycle //每拍处理的总位数
  private val cycleCount = (coreWidth + cycleDigitBits - 1) / cycleDigitBits //迭代轮数
  private val paddedBits = cycleCount * cycleDigitBits //补齐后的被除数宽度
  private val remainderWidth = digitBits + coreWidth + 2 //部分余数宽度
  private val quotientWidth = coreWidth + 2 //冗余商累加器宽度
  private val quotientDigitWidth = digitBits + 1 //有符号商数字宽度
  private val counterWidth = log2Ceil(cycleCount + 1).max(1) //计数器宽度
  private val busy = RegInit(false.B) //正在迭代
  private val resultValid = RegInit(false.B) //结果有效
  private val divZero = RegInit(false.B) //除数为零
  private val remainderReg = RegInit(0.S(remainderWidth.W)) //有符号部分余数
  private val dividendReg = RegInit(0.U(paddedBits.W)) //待处理被除数
  private val divisorReg = RegInit(0.U(coreWidth.W)) //除数幅值
  private val quotientReg = RegInit(0.S(quotientWidth.W)) //冗余商累加器
  private val originalDividend = RegInit(0.U(coreWidth.W)) //除零时返回原被除数
  private val count = RegInit(0.U(counterWidth.W)) //迭代计数
  private val quotientResult = RegInit(0.U(coreWidth.W)) //输出商
  private val remainderResult = RegInit(0.U(coreWidth.W)) //输出余数
  private val iterationResult = RegInit(0.U(16.W)) //本次除法实际迭代位数
  private val earlyOutResult = RegInit(false.B) //本次除法提前结束标志
  IO.IterationCount := iterationResult
  IO.EarlyOut := earlyOutResult
  IO.Request.ready := !IO.Flush && !busy && (!resultValid || IO.Response.ready) //可接收请求
  IO.Response.valid := resultValid && !IO.Flush //结果有效
  IO.Response.bits.Quotient := quotientResult //输出商
  IO.Response.bits.Remainder := remainderResult //输出余数
  val requestFire = IO.Request.valid && IO.Request.ready //请求握手
  val responseFire = IO.Response.valid && IO.Response.ready //响应握手
  val inputBits = dividendReg(paddedBits - 1, paddedBits - cycleDigitBits) //本轮被除数数字
  val divisorWide = Cat(0.U((remainderWidth - coreWidth).W), divisorReg) //扩展除数
  var selectedRemainder: SInt = remainderReg //当前部分余数
  var selectedQuotient: SInt = quotientReg //当前冗余商
  for (iteration <- 0 until iterationsPerCycle) {
    val chunkHigh = cycleDigitBits - iteration * digitBits - 1
    val chunkLow = cycleDigitBits - (iteration + 1) * digitBits
    val inputChunk = inputBits(chunkHigh, chunkLow) //当前被除数数字
    val shiftedRemainder = (selectedRemainder << digitBits)(remainderWidth - 1, 0).asSInt //部分余数乘基数
    val inputWide = Cat(0.U((remainderWidth - digitBits).W), inputChunk).asSInt //扩展被除数数字
    val trialRemainder = (shiftedRemainder +& inputWide)(remainderWidth - 1, 0).asSInt //本轮试探余数
    val trialNegative = trialRemainder < 0.S //试探余数符号
    val trialBits = trialRemainder.asUInt
    val absoluteTrial = Mux(
      trialNegative,
      (~trialBits + 1.U(remainderWidth.W))(remainderWidth - 1, 0),
      trialBits
    ) //试探余数绝对值
    var remainingMagnitude: UInt = absoluteTrial //商数字选择后的剩余幅值
    var quotientMagnitude: UInt = 0.U(digitBits.W) //向下取整商数字
    var scaledDivisor: UInt = 0.U(remainderWidth.W) //向下取整商数字对应的除数倍数
    for (bit <- (digitBits - 1) to 0 by -1) {
      val shiftedDivisor = (divisorWide << bit)(remainderWidth - 1, 0) //候选除数倍数
      val canTake = remainingMagnitude >= shiftedDivisor //当前商位是否可取
      val nextRemaining = (remainingMagnitude - shiftedDivisor)(remainderWidth - 1, 0) //试减结果
      val bitMask = (1.U(digitBits.W) << bit)(digitBits - 1, 0) //当前商位掩码
      val nextQuotientMagnitude = Mux(canTake, quotientMagnitude | bitMask, quotientMagnitude) //更新商数字
      val nextScaledDivisor = Mux(
        canTake,
        (scaledDivisor +& shiftedDivisor)(remainderWidth - 1, 0),
        scaledDivisor
      ) //更新除数倍数
      remainingMagnitude = Mux(canTake, nextRemaining, remainingMagnitude) //保留剩余幅值
      quotientMagnitude = nextQuotientMagnitude //保留商数字
      scaledDivisor = nextScaledDivisor //保留除数倍数
    }
    val maxDigit = ~0.U(digitBits.W) //最大冗余商数字幅值
    val doubleRemainder = (remainingMagnitude << 1)(remainderWidth - 1, 0) //两倍试探余数
    val roundUp = quotientMagnitude =/= maxDigit && doubleRemainder >= divisorWide //是否向最近商数字取整
    val roundedQuotientMagnitude = Mux(
      roundUp,
      (quotientMagnitude +& 1.U)(digitBits - 1, 0),
      quotientMagnitude
    ) //最近商数字幅值
    val roundedScaledDivisor = Mux(
      roundUp,
      (scaledDivisor +& divisorWide)(remainderWidth - 1, 0),
      scaledDivisor
    ) //最近商数字对应的除数倍数
    val quotientMagnitudeExtended = Cat(0.U(1.W), roundedQuotientMagnitude) //扩展商数字幅值
    val negativeQuotientMagnitude = (~quotientMagnitudeExtended + 1.U(quotientDigitWidth.W))(quotientDigitWidth - 1, 0) //负商数字补码
    val quotientDigitBits = Mux(
      trialNegative,
      negativeQuotientMagnitude,
      quotientMagnitudeExtended
    ) //有符号冗余商数字
    val positiveScaledDivisor = roundedScaledDivisor.asSInt //有符号除数倍数
    val positiveNextRemainder = (trialRemainder - positiveScaledDivisor)(remainderWidth - 1, 0).asSInt //正试探余数减法
    val negativeNextRemainder = (trialRemainder + positiveScaledDivisor)(remainderWidth - 1, 0).asSInt //负试探余数加法
    val nextRemainderDigit = Mux(trialNegative, negativeNextRemainder, positiveNextRemainder) //下一轮部分余数
    val quotientDigitForAccumulatorBits = if (quotientDigitWidth >= quotientWidth) {
      quotientDigitBits(quotientWidth - 1, 0)
    } else {
      Cat(Fill(quotientWidth - quotientDigitWidth, quotientDigitBits(quotientDigitWidth - 1)), quotientDigitBits)
    } //累加器宽度适配
    val quotientDigitForAccumulator = quotientDigitForAccumulatorBits.asSInt //扩展商数字
    val shiftedQuotient = (selectedQuotient << digitBits)(quotientWidth - 1, 0).asSInt //商累加器乘基数
    val nextQuotientDigit = (shiftedQuotient +& quotientDigitForAccumulator)(quotientWidth - 1, 0).asSInt //更新冗余商
    selectedRemainder = nextRemainderDigit //传递部分余数
    selectedQuotient = nextQuotientDigit //传递冗余商
  }
  val nextRemainder = selectedRemainder //本周期结束余数
  val nextQuotient = selectedQuotient //本周期结束商
  val nextDividend = (dividendReg << cycleDigitBits)(paddedBits - 1, 0) //被除数推进
  val earlyFinish = if (params.EarlyOut) nextDividend === 0.U && nextRemainder === 0.S else false.B //后续全为零且余数不再变化
  val remainingCycles = (cycleCount - 1).U(counterWidth.W) - count //提前结束时尚未展开的周期数
  val remainingShift = remainingCycles * cycleDigitBits.U //提前结束时尚未展开的位数
  val alignedEarlyQuotient = (nextQuotient << remainingShift)(quotientWidth - 1, 0).asSInt //补齐被省略的低位零
  val finalQuotientBeforeCorrection = Mux(earlyFinish, alignedEarlyQuotient, nextQuotient) //提前结束时对齐商
  val finalNegative = nextRemainder < 0.S //最终余数符号
  val correctedRemainder = Mux(
    finalNegative,
    (nextRemainder + divisorWide.asSInt)(remainderWidth - 1, 0).asSInt,
    nextRemainder
  ) //最终余数修正
  val correctedQuotient = Mux(
    finalNegative,
    (finalQuotientBeforeCorrection - 1.S(quotientWidth.W))(quotientWidth - 1, 0).asSInt,
    finalQuotientBeforeCorrection
  ) //最终商修正
  val correctedQuotientUInt = correctedQuotient.asUInt //商无符号视图
  val correctedRemainderUInt = correctedRemainder.asUInt //余数无符号视图
  val lastIteration = count === (cycleCount - 1).U(counterWidth.W) //判断最后一轮
  when (IO.Flush) {
    busy := false.B
    resultValid := false.B
    divZero := false.B
    remainderReg := 0.S
    dividendReg := 0.U
    divisorReg := 0.U
    quotientReg := 0.S
    originalDividend := 0.U
    count := 0.U
    quotientResult := 0.U
    remainderResult := 0.U
    iterationResult := 0.U
    earlyOutResult := false.B
  }.elsewhen (requestFire) {
    busy := true.B
    resultValid := false.B
    divZero := IO.Request.bits.DivisorMagnitude === 0.U
    remainderReg := 0.S
    if (paddedBits == coreWidth) {
      dividendReg := IO.Request.bits.DividendMagnitude
    } else {
      dividendReg := Cat(0.U((paddedBits - coreWidth).W), IO.Request.bits.DividendMagnitude)
    }
    divisorReg := IO.Request.bits.DivisorMagnitude
    quotientReg := 0.S
    originalDividend := IO.Request.bits.DividendMagnitude
    count := 0.U
    iterationResult := 0.U
    earlyOutResult := false.B
  }.elsewhen (busy) {
    when (divZero) {
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
      when (lastIteration || earlyFinish) {
        busy := false.B
        resultValid := true.B
        quotientResult := correctedQuotientUInt(coreWidth - 1, 0)
        remainderResult := correctedRemainderUInt(coreWidth - 1, 0)
        iterationResult :=
          ((count + 1.U) * cycleDigitBits.U).pad(16)
        earlyOutResult := earlyFinish
      }.otherwise {
        count := count + 1.U
      }
    }
  }.elsewhen (responseFire) {
    resultValid := false.B
  }
}
