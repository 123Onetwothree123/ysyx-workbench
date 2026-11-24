package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
//移位累加乘法器。
class ysyx_26030103_MULShiftAddCore(
    config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig() //配置
) extends ysyx_26030103_MULCore(config.MULEffectiveWidth) {
  private val operandWidth = config.MULEffectiveWidth
  private val productWidth = 2 * operandWidth
  private val useBooth = config.MDUProfile.MUL.Encoding == ysyx_26030103_MULEncoding.Booth //是否使用Booth
  private val boothRadix = if (useBooth) config.MDUProfile.MUL.Radix else 2 //单个Booth基数
  private val boothBits = if (useBooth) ysyx_26030103_MULBoothConfig.BoothBits(boothRadix) else 1 //单个Booth组宽度
  private val baseGroupsPerCycle = if (useBooth) (config.MDUProfile.MUL.ShiftAdd.IterBits + boothBits - 1) / boothBits else 1 //未分块时每拍处理的组数
  private val groupsPerCycle = if (useBooth) ((baseGroupsPerCycle + config.MDUProfile.MUL.ShiftAdd.Split - 1) / config.MDUProfile.MUL.ShiftAdd.Split).max(1) else 1 //分块后每拍Booth组数
  private val cycleBits = if (useBooth) groupsPerCycle * boothBits else ((config.MDUProfile.MUL.ShiftAdd.IterBits + config.MDUProfile.MUL.ShiftAdd.Split - 1) / config.MDUProfile.MUL.ShiftAdd.Split).max(1) //每拍实际处理位数
  private val totalGroups = if (useBooth) (operandWidth + 1 + boothBits - 1) / boothBits else operandWidth //总组数
  private val cycleCount = if (useBooth) (totalGroups + groupsPerCycle - 1) / groupsPerCycle else (operandWidth + cycleBits - 1) / cycleBits //总轮数
  private val paddedGroups = if (useBooth) cycleCount * groupsPerCycle else 0 //补齐后的Booth组数
  private val internalWidth = if (useBooth) 2 * (operandWidth + boothBits) else productWidth //内部累加宽度
  private val encoderWidth = operandWidth + boothBits //编码器被乘数宽度
  private val encoderProductWidth = ysyx_26030103_MULBoothConfig.PartialProductWidth(boothRadix, operandWidth) //编码器部分积宽度
  private val multiplierWidth = if (useBooth) paddedGroups * boothBits + 1 else cycleCount * cycleBits //乘数寄存器宽度
  private val counterWidth = log2Ceil(cycleCount + 1).max(1) //轮计数器宽度
  private val shiftWidth = log2Ceil(internalWidth + 1).max(1) //部分积移位量宽度
  private val busy = RegInit(false.B) //正在迭代
  private val resultValid = RegInit(false.B) //结果有效
  private val accumulator = RegInit(0.U(internalWidth.W)) //累加器
  private val multiplicand = RegInit(0.U(internalWidth.W)) //被乘数寄存器
  private val multiplier = RegInit(0.U(multiplierWidth.W)) //乘数寄存器
  private val iteration = RegInit(0.U(counterWidth.W)) //轮计数
  private val boothShift = RegInit(0.U(shiftWidth.W)) //Booth部分积移位量
  private val result = RegInit(0.U(productWidth.W)) //最终结果
  IO.Request.ready := !IO.Flush && !busy && (!resultValid || IO.Response.ready) //可接收请求
  IO.Response.valid := resultValid && !IO.Flush //结果有效
  IO.Response.bits.Product := result //输出低2N位乘积
  val requestFire = IO.Request.valid && IO.Request.ready //请求握手
  val responseFire = IO.Response.valid && IO.Response.ready //响应握手
  val currentAddend = Wire(UInt(internalWidth.W)) //本轮待累加部分积
  currentAddend := 0.U //默认不累加
  if (useBooth) {
    var boothSum: UInt = 0.U(internalWidth.W) //本轮Booth部分积和
    for (group <- 0 until groupsPerCycle) {
      val encoder = Module(new ysyx_26030103_MULBoothEncoder(boothRadix, operandWidth)) //Booth编码器
      val windowLow = group * boothBits //窗口起始位
      encoder.IO.Multiplicand := multiplicand(encoderWidth - 1, 0) //输入未移位被乘数
      encoder.IO.Window := multiplier(windowLow + boothBits, windowLow) //取当前窗口
      val shiftAmount = boothShift + (group * boothBits).U(shiftWidth.W) //当前部分积位置
      val signedPartialProduct = Cat(
        Fill(internalWidth - encoderProductWidth, encoder.IO.PartialProduct(encoderProductWidth - 1)),
        encoder.IO.PartialProduct
      )
      val shiftedPartialProduct = signedPartialProduct << shiftAmount //部分积左移
      val partialProduct = shiftedPartialProduct(internalWidth - 1, 0) //截取内部宽度
      boothSum = (boothSum +& partialProduct)(internalWidth - 1, 0) //累加本拍部分积
    }
    currentAddend := boothSum
  } else {
    var plainSum: UInt = 0.U(internalWidth.W) //本轮普通部分积和
    for (bit <- 0 until cycleBits) {
      val shifted = (multiplicand << bit)(internalWidth - 1, 0) //生成移位部分积
      val addend = Mux(multiplier(bit), shifted, 0.U(internalWidth.W)) //按乘数位选择
      plainSum = (plainSum +& addend)(internalWidth - 1, 0) //累加本拍部分积
    }
    currentAddend := plainSum
  }
  val nextAccumulator = (accumulator +& currentAddend)(internalWidth - 1, 0) //计算下一累加值
  val nextMultiplier = multiplier >> cycleBits //计算下一乘数
  val lastIteration = iteration === (cycleCount - 1).U(counterWidth.W) //判断最后一轮
  val earlyFinish = if (config.MDUProfile.MUL.ShiftAdd.EarlyOut) nextMultiplier === 0.U else false.B //提前结束
  when (IO.Flush) {
    busy := false.B
    resultValid := false.B
    accumulator := 0.U
    multiplicand := 0.U
    multiplier := 0.U
    iteration := 0.U
    boothShift := 0.U
    result := 0.U
  }.elsewhen (requestFire) {
    busy := true.B
    resultValid := false.B
    accumulator := 0.U
    multiplicand := Cat(0.U((internalWidth - operandWidth).W), IO.Request.bits.LHSMagnitude) //锁存被乘数
    if (useBooth) {
      // 无符号乘数需要一位符号 0 参与最后一组 Booth 窗口，padding 恒 >= 1。
      val paddingWidth = paddedGroups * boothBits - operandWidth
      multiplier := Cat(0.U(paddingWidth.W), IO.Request.bits.RHSMagnitude, 0.U(1.W))
    } else {
      val paddingWidth = cycleCount * cycleBits - operandWidth
      val multiplierValue = if (paddingWidth == 0) {
        IO.Request.bits.RHSMagnitude
      } else {
        Cat(0.U(paddingWidth.W), IO.Request.bits.RHSMagnitude)
      }
      multiplier := multiplierValue //普通乘数高位补零
    }
    iteration := 0.U
    boothShift := 0.U
  }.elsewhen (busy) {
    if (!useBooth) {
      multiplicand := (multiplicand << cycleBits)(internalWidth - 1, 0) //被乘数左移本拍位数
    } else {
      boothShift := boothShift + cycleBits.U(shiftWidth.W) //部分积位置前进
    }
    multiplier := nextMultiplier //乘数右移本拍位数
    when (lastIteration || earlyFinish) {
      busy := false.B
      resultValid := true.B
      result := nextAccumulator(productWidth - 1, 0) //保存2N位结果
      accumulator := nextAccumulator
    }.otherwise {
      accumulator := nextAccumulator
      iteration := iteration + 1.U
    }
  }.elsewhen (responseFire) {
    resultValid := false.B
  }
}
