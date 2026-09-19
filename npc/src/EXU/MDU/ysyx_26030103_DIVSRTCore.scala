package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.common.ysyx_26030103_NPCConfig
//参数化SRT除法器，使用精确比较选择冗余商数字。
class ysyx_26030103_DIVSRTCore(
    config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig() //配置
) extends ysyx_26030103_DIVCore {
  private val Radix = config.DIVRadix //除法基数
  private val DigitBits = ysyx_26030103_MULBoothConfig.BoothBits(Radix) //单个基数数字位数
  private val IterationsPerCycle = config.DIVIterBits //每拍处理的基数数字数
  private val CycleDigitBits = DigitBits * IterationsPerCycle //每拍处理的总位数
  private val CycleCount = (32 + CycleDigitBits - 1) / CycleDigitBits //迭代轮数
  private val PaddedBits = CycleCount * CycleDigitBits //补齐后的被除数宽度
  private val RemainderWidth = DigitBits + 34 //部分余数宽度
  private val QuotientWidth = 34 //冗余商累加器宽度
  private val QuotientDigitWidth = DigitBits + 1 //有符号商数字宽度
  private val CounterWidth = log2Ceil(CycleCount + 1).max(1) //计数器宽度
  private val Busy = RegInit(false.B) //正在迭代
  private val ResultValid = RegInit(false.B) //结果有效
  private val DivZero = RegInit(false.B) //除数为零
  private val RemainderReg = RegInit(0.S(RemainderWidth.W)) //有符号部分余数
  private val DividendReg = RegInit(0.U(PaddedBits.W)) //待处理被除数
  private val DivisorReg = RegInit(0.U(32.W)) //除数幅值
  private val QuotientReg = RegInit(0.S(QuotientWidth.W)) //冗余商累加器
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
  val InputBits = DividendReg(PaddedBits - 1, PaddedBits - CycleDigitBits) //本轮被除数数字
  val DivisorWide = Cat(0.U((RemainderWidth - 32).W), DivisorReg) //扩展除数
  var SelectedRemainder: SInt = RemainderReg //当前部分余数
  var SelectedQuotient: SInt = QuotientReg //当前冗余商
  for (Iteration <- 0 until IterationsPerCycle) {
    val ChunkHigh = CycleDigitBits - Iteration * DigitBits - 1
    val ChunkLow = CycleDigitBits - (Iteration + 1) * DigitBits
    val InputChunk = InputBits(ChunkHigh, ChunkLow) //当前被除数数字
    val ShiftedRemainder = (SelectedRemainder << DigitBits)(RemainderWidth - 1, 0).asSInt //部分余数乘基数
    val InputWide = Cat(0.U((RemainderWidth - DigitBits).W), InputChunk).asSInt //扩展被除数数字
    val TrialRemainder = (ShiftedRemainder +& InputWide)(RemainderWidth - 1, 0).asSInt //本轮试探余数
    val TrialNegative = TrialRemainder < 0.S //试探余数符号
    val TrialBits = TrialRemainder.asUInt
    val AbsoluteTrial = Mux(
      TrialNegative,
      (~TrialBits + 1.U(RemainderWidth.W))(RemainderWidth - 1, 0),
      TrialBits
    ) //试探余数绝对值
    var RemainingMagnitude: UInt = AbsoluteTrial //商数字选择后的剩余幅值
    var QuotientMagnitude: UInt = 0.U(DigitBits.W) //向下取整商数字
    var ScaledDivisor: UInt = 0.U(RemainderWidth.W) //向下取整商数字对应的除数倍数
    for (Bit <- (DigitBits - 1) to 0 by -1) {
      val ShiftedDivisor = (DivisorWide << Bit)(RemainderWidth - 1, 0) //候选除数倍数
      val CanTake = RemainingMagnitude >= ShiftedDivisor //当前商位是否可取
      val NextRemaining = (RemainingMagnitude - ShiftedDivisor)(RemainderWidth - 1, 0) //试减结果
      val BitMask = (1.U(DigitBits.W) << Bit)(DigitBits - 1, 0) //当前商位掩码
      val NextQuotientMagnitude = Mux(CanTake, QuotientMagnitude | BitMask, QuotientMagnitude) //更新商数字
      val NextScaledDivisor = Mux(
        CanTake,
        (ScaledDivisor +& ShiftedDivisor)(RemainderWidth - 1, 0),
        ScaledDivisor
      ) //更新除数倍数
      RemainingMagnitude = Mux(CanTake, NextRemaining, RemainingMagnitude) //保留剩余幅值
      QuotientMagnitude = NextQuotientMagnitude //保留商数字
      ScaledDivisor = NextScaledDivisor //保留除数倍数
    }
    val MaxDigit = ~0.U(DigitBits.W) //最大冗余商数字幅值
    val DoubleRemainder = (RemainingMagnitude << 1)(RemainderWidth - 1, 0) //两倍试探余数
    val RoundUp = QuotientMagnitude =/= MaxDigit && DoubleRemainder >= DivisorWide //是否向最近商数字取整
    val RoundedQuotientMagnitude = Mux(
      RoundUp,
      (QuotientMagnitude +& 1.U)(DigitBits - 1, 0),
      QuotientMagnitude
    ) //最近商数字幅值
    val RoundedScaledDivisor = Mux(
      RoundUp,
      (ScaledDivisor +& DivisorWide)(RemainderWidth - 1, 0),
      ScaledDivisor
    ) //最近商数字对应的除数倍数
    val QuotientMagnitudeExtended = Cat(0.U(1.W), RoundedQuotientMagnitude) //扩展商数字幅值
    val NegativeQuotientMagnitude = (~QuotientMagnitudeExtended + 1.U(QuotientDigitWidth.W))(QuotientDigitWidth - 1, 0) //负商数字补码
    val QuotientDigitBits = Mux(
      TrialNegative,
      NegativeQuotientMagnitude,
      QuotientMagnitudeExtended
    ) //有符号冗余商数字
    val PositiveScaledDivisor = RoundedScaledDivisor.asSInt //有符号除数倍数
    val PositiveNextRemainder = (TrialRemainder - PositiveScaledDivisor)(RemainderWidth - 1, 0).asSInt //正试探余数减法
    val NegativeNextRemainder = (TrialRemainder + PositiveScaledDivisor)(RemainderWidth - 1, 0).asSInt //负试探余数加法
    val NextRemainderDigit = Mux(TrialNegative, NegativeNextRemainder, PositiveNextRemainder) //下一轮部分余数
    val QuotientDigitForAccumulatorBits = if (QuotientDigitWidth >= QuotientWidth) {
      QuotientDigitBits(QuotientWidth - 1, 0)
    } else {
      Cat(Fill(QuotientWidth - QuotientDigitWidth, QuotientDigitBits(QuotientDigitWidth - 1)), QuotientDigitBits)
    } //累加器宽度适配
    val QuotientDigitForAccumulator = QuotientDigitForAccumulatorBits.asSInt //扩展商数字
    val ShiftedQuotient = (SelectedQuotient << DigitBits)(QuotientWidth - 1, 0).asSInt //商累加器乘基数
    val NextQuotientDigit = (ShiftedQuotient +& QuotientDigitForAccumulator)(QuotientWidth - 1, 0).asSInt //更新冗余商
    SelectedRemainder = NextRemainderDigit //传递部分余数
    SelectedQuotient = NextQuotientDigit //传递冗余商
  }
  val NextRemainder = SelectedRemainder //本周期结束余数
  val NextQuotient = SelectedQuotient //本周期结束商
  val NextDividend = (DividendReg << CycleDigitBits)(PaddedBits - 1, 0) //被除数推进
  val EarlyFinish = if (config.DIVEarlyOut) NextDividend === 0.U && NextRemainder === 0.S else false.B //后续全为零且余数不再变化
  val RemainingCycles = (CycleCount - 1).U(CounterWidth.W) - Count //提前结束时尚未展开的周期数
  val RemainingShift = RemainingCycles * CycleDigitBits.U //提前结束时尚未展开的位数
  val AlignedEarlyQuotient = (NextQuotient << RemainingShift)(QuotientWidth - 1, 0).asSInt //补齐被省略的低位零
  val FinalQuotientBeforeCorrection = Mux(EarlyFinish, AlignedEarlyQuotient, NextQuotient) //提前结束时对齐商
  val FinalNegative = NextRemainder < 0.S //最终余数符号
  val CorrectedRemainder = Mux(
    FinalNegative,
    (NextRemainder + DivisorWide.asSInt)(RemainderWidth - 1, 0).asSInt,
    NextRemainder
  ) //最终余数修正
  val CorrectedQuotient = Mux(
    FinalNegative,
    (FinalQuotientBeforeCorrection - 1.S(QuotientWidth.W))(QuotientWidth - 1, 0).asSInt,
    FinalQuotientBeforeCorrection
  ) //最终商修正
  val CorrectedQuotientUInt = CorrectedQuotient.asUInt //商无符号视图
  val CorrectedRemainderUInt = CorrectedRemainder.asUInt //余数无符号视图
  val LastIteration = Count === (CycleCount - 1).U(CounterWidth.W) //判断最后一轮
  when (IO.Flush) {
    Busy := false.B
    ResultValid := false.B
    DivZero := false.B
    RemainderReg := 0.S
    DividendReg := 0.U
    DivisorReg := 0.U
    QuotientReg := 0.S
    OriginalDividend := 0.U
    Count := 0.U
    QuotientResult := 0.U
    RemainderResult := 0.U
  }.elsewhen (ReqFire) {
    Busy := true.B
    ResultValid := false.B
    DivZero := IO.Req.bits.DivisorMagnitude === 0.U
    RemainderReg := 0.S
    if (PaddedBits == 32) {
      DividendReg := IO.Req.bits.DividendMagnitude
    } else {
      DividendReg := Cat(0.U((PaddedBits - 32).W), IO.Req.bits.DividendMagnitude)
    }
    DivisorReg := IO.Req.bits.DivisorMagnitude
    QuotientReg := 0.S
    OriginalDividend := IO.Req.bits.DividendMagnitude
    Count := 0.U
  }.elsewhen (Busy) {
    when (DivZero) {
      Busy := false.B
      ResultValid := true.B
      QuotientResult := "hffffffff".U
      RemainderResult := OriginalDividend
    }.otherwise {
      RemainderReg := NextRemainder
      DividendReg := NextDividend
      QuotientReg := NextQuotient
      when (LastIteration || EarlyFinish) {
        Busy := false.B
        ResultValid := true.B
        QuotientResult := CorrectedQuotientUInt(31, 0)
        RemainderResult := CorrectedRemainderUInt(31, 0)
      }.otherwise {
        Count := Count + 1.U
      }
    }
  }.elsewhen (RespFire) {
    ResultValid := false.B
  }
}
