package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
//通用2的幂基数不恢复余数除法器。
class ysyx_26030103_DIVNonRestoringCore(
    config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig() //配置
) extends ysyx_26030103_DIVCore(config.XLEN) {
  private val params = config.MDUProfile.DIV.For(config.DIVImpl)
  private val coreWidth = config.XLEN
  private val radix = params.Radix //除法基数
  private val digitBits = ysyx_26030103_MULBoothConfig.BoothBits(radix) //单个基数数字位数
  private val iterationsPerCycle = params.IterBits //每拍处理的基数数字数
  private val cycleDigitBits = digitBits * iterationsPerCycle //每拍展开的二进制轮数
  private val cycleCount = (coreWidth + cycleDigitBits - 1) / cycleDigitBits //外层迭代轮数
  private val paddedBits = cycleCount * cycleDigitBits //补齐后的被除数宽度
  private val remainderWidth = coreWidth + 2 //有符号部分余数宽度
  private val counterWidth = log2Ceil(cycleCount + 1).max(1) //计数器宽度
  private val busy = RegInit(false.B) //正在迭代
  private val resultValid = RegInit(false.B) //结果有效
  private val divZero = RegInit(false.B) //除数为零
  private val remainderReg = RegInit(0.U(remainderWidth.W)) //有符号部分余数补码
  private val dividendReg = RegInit(0.U(paddedBits.W)) //待处理被除数
  private val divisorReg = RegInit(0.U(coreWidth.W)) //除数幅值
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
  val divisorExtended = Cat(0.U(2.W), divisorReg) //扩展为正数补码
  var selectedRemainder: UInt = remainderReg //当前有符号部分余数
  var quotientChunk: UInt = 0.U(cycleDigitBits.W) //本轮商位
  for (step <- 0 until cycleDigitBits) {
    val inputBit = inputBits(cycleDigitBits - step - 1) //按高位到低位取被除数位
    val shiftedRemainder = Cat(selectedRemainder(remainderWidth - 2, 0), inputBit) //部分余数左移并带入一位
    val subtracted = (shiftedRemainder - divisorExtended)(remainderWidth - 1, 0) //非负时试减
    val added = (shiftedRemainder +& divisorExtended)(remainderWidth - 1, 0) //负数时加回
    val nextRemainder = Mux(selectedRemainder(remainderWidth - 1), added, subtracted) //根据旧符号选择运算
    val quotientBit = !nextRemainder(remainderWidth - 1) //新余数非负则商位为一
    val shiftedQuotientChunk = (quotientChunk << 1)(cycleDigitBits - 1, 0) //商位左移
    quotientChunk = shiftedQuotientChunk | quotientBit.asUInt //追加当前商位
    selectedRemainder = nextRemainder //传递部分余数
  }
  val correctedRemainder = Mux(
    selectedRemainder(remainderWidth - 1),
    (selectedRemainder +& divisorExtended)(remainderWidth - 1, 0),
    selectedRemainder
  ) //最终负余数修正
  val quotientChunkWide = if (cycleDigitBits >= coreWidth) quotientChunk(coreWidth - 1, 0) else Cat(0.U((coreWidth - cycleDigitBits).W), quotientChunk) //扩展商位
  val nextQuotient = ((quotientReg << cycleDigitBits) | quotientChunkWide)(coreWidth - 1, 0) //追加本轮商位
  val nextDividend = (dividendReg << cycleDigitBits)(paddedBits - 1, 0) //被除数右移推进
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
    divisorReg := IO.Request.bits.DivisorMagnitude
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
        remainderResult := correctedRemainder(coreWidth - 1, 0)
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
