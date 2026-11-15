package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
//通用2的幂基数恢复余数除法器。
class ysyx_26030103_DIVRestoringCore(
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
  private val remainderWidth = coreWidth + digitBits //部分余数宽度
  private val counterWidth = log2Ceil(cycleCount + 1).max(1) //计数器宽度
  private val busy = RegInit(false.B) //正在迭代
  private val resultValid = RegInit(false.B) //结果有效
  private val divZero = RegInit(false.B) //除数为零
  private val remainderReg = RegInit(0.U(remainderWidth.W)) //部分余数
  private val dividendReg = RegInit(0.U(paddedBits.W)) //待处理被除数
  private val divisorReg = RegInit(0.U(remainderWidth.W)) //扩展除数
  private val quotientReg = RegInit(0.U(coreWidth.W)) //商
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
  val inputBits = dividendReg(paddedBits - 1, paddedBits - cycleDigitBits) //本轮输入位
  var selectedRemainder: UInt = remainderReg //当前试减余数
  var quotientChunk: UInt = 0.U(cycleDigitBits.W) //本轮商数字
  for (iteration <- 0 until iterationsPerCycle) {
    val chunkHigh = cycleDigitBits - iteration * digitBits - 1
    val chunkLow = cycleDigitBits - (iteration + 1) * digitBits
    val inputChunk = inputBits(chunkHigh, chunkLow) //当前基数数字
    val shiftedRemainder = selectedRemainder << digitBits //部分余数左移
    val trialRemainder = Cat(shiftedRemainder(remainderWidth - 1, digitBits), inputChunk) //带入当前被除数数字
    var selectedDigitRemainder: UInt = trialRemainder //当前数字试减余数
    var quotientDigit: UInt = 0.U(digitBits.W) //当前商数字
    for (_ <- 0 until radix - 1) {
      val canSubtract = selectedDigitRemainder >= divisorReg //当前候选是否够减
      val subtracted = selectedDigitRemainder - divisorReg //试减结果
      val incremented = (quotientDigit +& 1.U)(digitBits - 1, 0) //商数字加一
      selectedDigitRemainder = Mux(canSubtract, subtracted, selectedDigitRemainder) //保留可行余数
      quotientDigit = Mux(canSubtract, incremented, quotientDigit) //选择最大商数字
    }
    val quotientDigitWide = if (cycleDigitBits == digitBits) quotientDigit else Cat(0.U((cycleDigitBits - digitBits).W), quotientDigit) //扩展当前商数字
    quotientChunk = ((quotientChunk << digitBits)(cycleDigitBits - 1, 0) | quotientDigitWide) //追加当前商数字
    selectedRemainder = selectedDigitRemainder //传递当前余数
  }
  val quotientChunkWide = if (cycleDigitBits >= coreWidth) quotientChunk(coreWidth - 1, 0) else Cat(0.U((coreWidth - cycleDigitBits).W), quotientChunk) //扩展本轮商数字
  val nextQuotient = ((quotientReg << cycleDigitBits) | quotientChunkWide)(coreWidth - 1, 0) //追加本轮商数字
  val nextDividend = (dividendReg << cycleDigitBits)(paddedBits - 1, 0) //被除数右侧推进
  val earlyFinish = if (params.EarlyOut) nextDividend === 0.U && selectedRemainder === 0.U else false.B //后续全为零且余数不再变化
  val remainingCycles = (cycleCount - 1).U(counterWidth.W) - count //提前结束时尚未展开的周期数
  val remainingShift = remainingCycles * cycleDigitBits.U //提前结束时尚未展开的位数
  val alignedEarlyQuotient = (nextQuotient << remainingShift)(coreWidth - 1, 0) //补齐被省略的低位零
  val finalQuotient = Mux(earlyFinish, alignedEarlyQuotient, nextQuotient) //最终商
  val lastIteration = count === (cycleCount - 1).U(counterWidth.W) //判断最后一轮
  when (IO.Flush) {
    busy := false.B
    resultValid := false.B
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
  }.elsewhen (requestFire) {
    busy := true.B
    resultValid := false.B
    divZero := IO.Request.bits.DivisorMagnitude === 0.U
    remainderReg := 0.U
    if (paddedBits == coreWidth) {
      dividendReg := IO.Request.bits.DividendMagnitude
    } else {
      dividendReg := Cat(0.U((paddedBits - coreWidth).W), IO.Request.bits.DividendMagnitude)
    }
    divisorReg := Cat(0.U(digitBits.W), IO.Request.bits.DivisorMagnitude)
    quotientReg := 0.U
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
      remainderReg := selectedRemainder
      dividendReg := nextDividend
      quotientReg := nextQuotient
      when (lastIteration || earlyFinish) {
        busy := false.B
        resultValid := true.B
        quotientResult := finalQuotient
        remainderResult := selectedRemainder(coreWidth - 1, 0)
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
