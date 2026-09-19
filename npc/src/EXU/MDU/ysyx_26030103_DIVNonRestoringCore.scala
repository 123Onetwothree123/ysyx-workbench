package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.common.ysyx_26030103_NPCConfig
//通用2的幂基数不恢复余数除法器。
class ysyx_26030103_DIVNonRestoringCore(
    config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig() //配置
) extends ysyx_26030103_DIVCore {
  private val Radix = config.DIVRadix //除法基数
  private val DigitBits = ysyx_26030103_MULBoothConfig.BoothBits(Radix) //单个基数数字位数
  private val IterationsPerCycle = config.DIVIterBits //每拍处理的基数数字数
  private val CycleDigitBits = DigitBits * IterationsPerCycle //每拍展开的二进制轮数
  private val CycleCount = (32 + CycleDigitBits - 1) / CycleDigitBits //外层迭代轮数
  private val PaddedBits = CycleCount * CycleDigitBits //补齐后的被除数宽度
  private val RemainderWidth = 34 //有符号部分余数宽度
  private val CounterWidth = log2Ceil(CycleCount + 1).max(1) //计数器宽度
  private val Busy = RegInit(false.B) //正在迭代
  private val ResultValid = RegInit(false.B) //结果有效
  private val DivZero = RegInit(false.B) //除数为零
  private val RemainderReg = RegInit(0.U(RemainderWidth.W)) //有符号部分余数补码
  private val DividendReg = RegInit(0.U(PaddedBits.W)) //待处理被除数
  private val DivisorReg = RegInit(0.U(32.W)) //除数幅值
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
  val DivisorExtended = Cat(0.U(2.W), DivisorReg) //扩展为正数补码
  var SelectedRemainder: UInt = RemainderReg //当前有符号部分余数
  var QuotientChunk: UInt = 0.U(CycleDigitBits.W) //本轮商位
  for (Step <- 0 until CycleDigitBits) {
    val InputBit = InputBits(CycleDigitBits - Step - 1) //按高位到低位取被除数位
    val ShiftedRemainder = Cat(SelectedRemainder(RemainderWidth - 2, 0), InputBit) //部分余数左移并带入一位
    val Subtracted = (ShiftedRemainder - DivisorExtended)(RemainderWidth - 1, 0) //非负时试减
    val Added = (ShiftedRemainder +& DivisorExtended)(RemainderWidth - 1, 0) //负数时加回
    val NextRemainder = Mux(SelectedRemainder(RemainderWidth - 1), Added, Subtracted) //根据旧符号选择运算
    val QuotientBit = !NextRemainder(RemainderWidth - 1) //新余数非负则商位为一
    val ShiftedQuotientChunk = (QuotientChunk << 1)(CycleDigitBits - 1, 0) //商位左移
    QuotientChunk = ShiftedQuotientChunk | QuotientBit.asUInt //追加当前商位
    SelectedRemainder = NextRemainder //传递部分余数
  }
  val CorrectedRemainder = Mux(
    SelectedRemainder(RemainderWidth - 1),
    (SelectedRemainder +& DivisorExtended)(RemainderWidth - 1, 0),
    SelectedRemainder
  ) //最终负余数修正
  val QuotientChunkWide = if (CycleDigitBits >= 32) QuotientChunk(31, 0) else Cat(0.U((32 - CycleDigitBits).W), QuotientChunk) //扩展商位
  val NextQuotient = ((QuotientReg << CycleDigitBits) | QuotientChunkWide)(31, 0) //追加本轮商位
  val NextDividend = (DividendReg << CycleDigitBits)(PaddedBits - 1, 0) //被除数右移推进
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
    DivisorReg := IO.Req.bits.DivisorMagnitude
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
        RemainderResult := CorrectedRemainder(31, 0)
      }.otherwise {
        Count := Count + 1.U
      }
    }
  }.elsewhen (RespFire) {
    ResultValid := false.B
  }
}
