package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
//通用2的幂基数恢复余数除法器。
class ysyx_26030103_DIVRestoringCore(
    config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig() //配置
) extends ysyx_26030103_DIVCore {
  private val Radix = config.DIVRadix //除法基数
  private val DigitBits = ysyx_26030103_MULBoothConfig.BoothBits(Radix) //单个基数数字位数
  private val IterationsPerCycle = config.DIVIterBits //每拍处理的基数数字数
  private val CycleDigitBits = DigitBits * IterationsPerCycle //每拍处理的总位数
  private val CycleCount = (32 + CycleDigitBits - 1) / CycleDigitBits //迭代轮数
  private val PaddedBits = CycleCount * CycleDigitBits //补齐后的被除数宽度
  private val RemainderWidth = 32 + DigitBits //部分余数宽度
  private val CounterWidth = log2Ceil(CycleCount + 1).max(1) //计数器宽度
  private val Busy = RegInit(false.B) //正在迭代
  private val ResultValid = RegInit(false.B) //结果有效
  private val DivZero = RegInit(false.B) //除数为零
  private val RemainderReg = RegInit(0.U(RemainderWidth.W)) //部分余数
  private val DividendReg = RegInit(0.U(PaddedBits.W)) //待处理被除数
  private val DivisorReg = RegInit(0.U(RemainderWidth.W)) //扩展除数
  private val QuotientReg = RegInit(0.U(32.W)) //商
  private val OriginalDividend = RegInit(0.U(32.W)) //除零时返回原被除数
  private val Count = RegInit(0.U(CounterWidth.W)) //迭代计数
  private val QuotientResult = RegInit(0.U(32.W)) //输出商
  private val RemainderResult = RegInit(0.U(32.W)) //输出余数
  IO.Req.ready := !IO.Flush && !Busy && (!ResultValid || IO.Resp.ready) //可接收请求
  IO.Resp.valid := ResultValid && !IO.Flush //结果有效
  IO.Resp.bits.Quotient := QuotientResult //输出商
  IO.Resp.bits.Remainder := RemainderResult //输出余数
  val ReqFire = IO.Req.valid && IO.Req.ready //请求握手
  val RespFire = IO.Resp.valid && IO.Resp.ready //响应握手
  val InputBits = DividendReg(PaddedBits - 1, PaddedBits - CycleDigitBits) //本轮输入位
  var SelectedRemainder: UInt = RemainderReg //当前试减余数
  var QuotientChunk: UInt = 0.U(CycleDigitBits.W) //本轮商数字
  for (Iteration <- 0 until IterationsPerCycle) {
    val ChunkHigh = CycleDigitBits - Iteration * DigitBits - 1
    val ChunkLow = CycleDigitBits - (Iteration + 1) * DigitBits
    val InputChunk = InputBits(ChunkHigh, ChunkLow) //当前基数数字
    val ShiftedRemainder = SelectedRemainder << DigitBits //部分余数左移
    val TrialRemainder = Cat(ShiftedRemainder(RemainderWidth - 1, DigitBits), InputChunk) //带入当前被除数数字
    var SelectedDigitRemainder: UInt = TrialRemainder //当前数字试减余数
    var QuotientDigit: UInt = 0.U(DigitBits.W) //当前商数字
    for (Candidate <- 0 until Radix - 1) {
      val CanSubtract = SelectedDigitRemainder >= DivisorReg //当前候选是否够减
      val Subtracted = SelectedDigitRemainder - DivisorReg //试减结果
      val Incremented = (QuotientDigit +& 1.U)(DigitBits - 1, 0) //商数字加一
      SelectedDigitRemainder = Mux(CanSubtract, Subtracted, SelectedDigitRemainder) //保留可行余数
      QuotientDigit = Mux(CanSubtract, Incremented, QuotientDigit) //选择最大商数字
    }
    val QuotientDigitWide = if (CycleDigitBits == DigitBits) QuotientDigit else Cat(0.U((CycleDigitBits - DigitBits).W), QuotientDigit) //扩展当前商数字
    QuotientChunk = ((QuotientChunk << DigitBits)(CycleDigitBits - 1, 0) | QuotientDigitWide) //追加当前商数字
    SelectedRemainder = SelectedDigitRemainder //传递当前余数
  }
  val QuotientChunkWide = if (CycleDigitBits >= 32) QuotientChunk(31, 0) else Cat(0.U((32 - CycleDigitBits).W), QuotientChunk) //扩展本轮商数字
  val NextQuotient = ((QuotientReg << CycleDigitBits) | QuotientChunkWide)(31, 0) //追加本轮商数字
  val NextDividend = (DividendReg << CycleDigitBits)(PaddedBits - 1, 0) //被除数右侧推进
  val EarlyFinish = if (config.DIVEarlyOut) NextDividend === 0.U && SelectedRemainder === 0.U else false.B //后续全为零且余数不再变化
  val RemainingCycles = (CycleCount - 1).U(CounterWidth.W) - Count //提前结束时尚未展开的周期数
  val RemainingShift = RemainingCycles * CycleDigitBits.U //提前结束时尚未展开的位数
  val AlignedEarlyQuotient = (NextQuotient << RemainingShift)(31, 0) //补齐被省略的低位零
  val FinalQuotient = Mux(EarlyFinish, AlignedEarlyQuotient, NextQuotient) //最终商
  val LastIteration = Count === (CycleCount - 1).U(CounterWidth.W) //判断最后一轮
  when (IO.Flush) {
    Busy := false.B
    ResultValid := false.B
    DivZero := false.B
    RemainderReg := 0.U
    DividendReg := 0.U
    DivisorReg := 0.U
    QuotientReg := 0.U
    OriginalDividend := 0.U
    Count := 0.U
    QuotientResult := 0.U
    RemainderResult := 0.U
  }.elsewhen (ReqFire) {
    Busy := true.B
    ResultValid := false.B
    DivZero := IO.Req.bits.DivisorMagnitude === 0.U
    RemainderReg := 0.U
    if (PaddedBits == 32) {
      DividendReg := IO.Req.bits.DividendMagnitude
    } else {
      DividendReg := Cat(0.U((PaddedBits - 32).W), IO.Req.bits.DividendMagnitude)
    }
    DivisorReg := Cat(0.U(DigitBits.W), IO.Req.bits.DivisorMagnitude)
    QuotientReg := 0.U
    OriginalDividend := IO.Req.bits.DividendMagnitude
    Count := 0.U
  }.elsewhen (Busy) {
    when (DivZero) {
      Busy := false.B
      ResultValid := true.B
      QuotientResult := "hffffffff".U
      RemainderResult := OriginalDividend
    }.otherwise {
      RemainderReg := SelectedRemainder
      DividendReg := NextDividend
      QuotientReg := NextQuotient
      when (LastIteration || EarlyFinish) {
        Busy := false.B
        ResultValid := true.B
        QuotientResult := FinalQuotient
        RemainderResult := SelectedRemainder(31, 0)
      }.otherwise {
        Count := Count + 1.U
      }
    }
  }.elsewhen (RespFire) {
    ResultValid := false.B
  }
}
