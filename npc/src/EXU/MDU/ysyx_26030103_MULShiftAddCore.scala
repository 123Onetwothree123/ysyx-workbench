package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
//移位累加乘法器。
class ysyx_26030103_MULShiftAddCore(
    config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig() //配置
) extends ysyx_26030103_MULCore {
  private val UseBooth = config.MULEncoding == ysyx_26030103_MULEncoding.Booth //是否使用Booth
  private val BoothRadix = if (UseBooth) config.MULRadix else 2 //单个Booth基数
  private val BoothBits = if (UseBooth) ysyx_26030103_MULBoothConfig.BoothBits(BoothRadix) else 1 //单个Booth组宽度
  private val BaseGroupsPerCycle = if (UseBooth) (config.MULIterBits + BoothBits - 1) / BoothBits else 1 //未分块时每拍处理的组数
  private val GroupsPerCycle = if (UseBooth) ((BaseGroupsPerCycle + config.MULSplit - 1) / config.MULSplit).max(1) else 1 //分块后每拍Booth组数
  private val CycleBits = if (UseBooth) GroupsPerCycle * BoothBits else ((config.MULIterBits + config.MULSplit - 1) / config.MULSplit).max(1) //每拍实际处理位数
  private val TotalGroups = if (UseBooth) (33 + BoothBits - 1) / BoothBits else 32 //总组数
  private val CycleCount = if (UseBooth) (TotalGroups + GroupsPerCycle - 1) / GroupsPerCycle else (32 + CycleBits - 1) / CycleBits //总轮数
  private val PaddedGroups = if (UseBooth) CycleCount * GroupsPerCycle else 0 //补齐后的Booth组数
  private val InternalWidth = if (UseBooth) 2 * (32 + BoothBits) else 64 //内部累加宽度
  private val EncoderWidth = 32 + BoothBits //编码器被乘数宽度
  private val EncoderProductWidth = ysyx_26030103_MULBoothConfig.PartialProductWidth(BoothRadix) //编码器部分积宽度
  private val MultiplierWidth = if (UseBooth) PaddedGroups * BoothBits + 1 else CycleCount * CycleBits //乘数寄存器宽度
  private val CounterWidth = log2Ceil(CycleCount + 1).max(1) //轮计数器宽度
  private val ShiftWidth = log2Ceil(InternalWidth + 1).max(1) //部分积移位量宽度
  private val Busy = RegInit(false.B) //正在迭代
  private val ResultValid = RegInit(false.B) //结果有效
  private val Accumulator = RegInit(0.U(InternalWidth.W)) //累加器
  private val Multiplicand = RegInit(0.U(InternalWidth.W)) //被乘数寄存器
  private val Multiplier = RegInit(0.U(MultiplierWidth.W)) //乘数寄存器
  private val Iteration = RegInit(0.U(CounterWidth.W)) //轮计数
  private val BoothShift = RegInit(0.U(ShiftWidth.W)) //Booth部分积移位量
  private val Result = RegInit(0.U(64.W)) //最终结果
  IO.Req.ready := !IO.Flush && !Busy && (!ResultValid || IO.Resp.ready) //可接收请求
  IO.Resp.valid := ResultValid && !IO.Flush //结果有效
  IO.Resp.bits.Product := Result //输出低64位乘积
  val ReqFire = IO.Req.valid && IO.Req.ready //请求握手
  val RespFire = IO.Resp.valid && IO.Resp.ready //响应握手
  val CurrentAddend = Wire(UInt(InternalWidth.W)) //本轮待累加部分积
  CurrentAddend := 0.U //默认不累加
  if (UseBooth) {
    var BoothSum: UInt = 0.U(InternalWidth.W) //本轮Booth部分积和
    for (Group <- 0 until GroupsPerCycle) {
      val Encoder = Module(new ysyx_26030103_MULBoothEncoder(BoothRadix)) //Booth编码器
      val WindowLow = Group * BoothBits //窗口起始位
      Encoder.IO.Multiplicand := Multiplicand(EncoderWidth - 1, 0) //输入未移位被乘数
      Encoder.IO.Window := Multiplier(WindowLow + BoothBits, WindowLow) //取当前窗口
      val ShiftAmount = BoothShift + (Group * BoothBits).U(ShiftWidth.W) //当前部分积位置
      val SignedPartialProduct = if (EncoderProductWidth >= InternalWidth) {
        Encoder.IO.PartialProduct(InternalWidth - 1, 0)
      } else {
        Cat(
          Fill(InternalWidth - EncoderProductWidth, Encoder.IO.PartialProduct(EncoderProductWidth - 1)),
          Encoder.IO.PartialProduct
        )
      }
      val ShiftedPartialProduct = SignedPartialProduct << ShiftAmount //部分积左移
      val PartialProduct = ShiftedPartialProduct(InternalWidth - 1, 0) //截取内部宽度
      BoothSum = (BoothSum +& PartialProduct)(InternalWidth - 1, 0) //累加本拍部分积
    }
    CurrentAddend := BoothSum
  } else {
    var PlainSum: UInt = 0.U(InternalWidth.W) //本轮普通部分积和
    for (Bit <- 0 until CycleBits) {
      val Shifted = (Multiplicand << Bit)(InternalWidth - 1, 0) //生成移位部分积
      val Addend = Mux(Multiplier(Bit), Shifted, 0.U(InternalWidth.W)) //按乘数位选择
      PlainSum = (PlainSum +& Addend)(InternalWidth - 1, 0) //累加本拍部分积
    }
    CurrentAddend := PlainSum
  }
  val NextAccumulator = (Accumulator +& CurrentAddend)(InternalWidth - 1, 0) //计算下一累加值
  val NextMultiplier = Multiplier >> CycleBits //计算下一乘数
  val LastIteration = Iteration === (CycleCount - 1).U(CounterWidth.W) //判断最后一轮
  val EarlyFinish = if (config.MULEarlyOut) NextMultiplier === 0.U else false.B //提前结束
  when (IO.Flush) {
    Busy := false.B
    ResultValid := false.B
    Accumulator := 0.U
    Multiplicand := 0.U
    Multiplier := 0.U
    Iteration := 0.U
    BoothShift := 0.U
    Result := 0.U
  }.elsewhen (ReqFire) {
    Busy := true.B
    ResultValid := false.B
    Accumulator := 0.U
    Multiplicand := Cat(0.U((InternalWidth - 32).W), IO.Req.bits.LHSMagnitude) //锁存被乘数
    if (UseBooth) {
      val PaddingWidth = PaddedGroups * BoothBits - 32
      Multiplier := Cat(0.U(PaddingWidth.W), IO.Req.bits.RHSMagnitude, 0.U(1.W)) //补高位和最低位
    } else {
      val PaddingWidth = CycleCount * CycleBits - 32
      Multiplier := Cat(0.U(PaddingWidth.W), IO.Req.bits.RHSMagnitude) //普通乘数高位补零
    }
    Iteration := 0.U
    BoothShift := 0.U
  }.elsewhen (Busy) {
    if (!UseBooth) {
      Multiplicand := (Multiplicand << CycleBits)(InternalWidth - 1, 0) //被乘数左移本拍位数
    } else {
      BoothShift := BoothShift + CycleBits.U(ShiftWidth.W) //部分积位置前进
    }
    Multiplier := NextMultiplier //乘数右移本拍位数
    when (LastIteration || EarlyFinish) {
      Busy := false.B
      ResultValid := true.B
      Result := NextAccumulator(63, 0) //保存64位结果
      Accumulator := NextAccumulator
    }.otherwise {
      Accumulator := NextAccumulator
      Iteration := Iteration + 1.U
    }
  }.elsewhen (RespFire) {
    ResultValid := false.B
  }
}
