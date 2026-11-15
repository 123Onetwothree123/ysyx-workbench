package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
class ysyx_26030103_MULBoothEncoder(
    val Radix: Int,
    val OperandWidth: Int = ysyx_26030103_MULBoothConfig.OperandWidth
) extends Module {
  private val boothBits = ysyx_26030103_MULBoothConfig.BoothBits(Radix) //每组处理位数
  final val IO = _root_.chisel3.IO(new ysyx_26030103_MULBoothEncoderInterface(Radix, OperandWidth))
  private val windowWidth = ysyx_26030103_MULBoothConfig.WindowWidth(Radix) //窗口宽度等于BoothBits+1
  private val digitWidth = boothBits + 1 //有符号数字的内部宽度
  private val productWidth = ysyx_26030103_MULBoothConfig.PartialProductWidth(Radix, OperandWidth) //部分积宽度
  private val multiplicandWidth = ysyx_26030103_MULBoothConfig.MultiplicandWidth(Radix, OperandWidth) //被乘数扩展宽度
  val windowExtended = Cat(0.U(1.W), IO.Window) //在窗口最高位补0，防止到时候搞个加1溢出
  val roundedWindow = windowExtended + 1.U((windowWidth + 1).W) //计算window+1
  val rawMagnitude = (roundedWindow >> 1)(digitWidth - 1, 0) //计算floor((window+1)/2)
  val radixValue = (BigInt(1) << boothBits).U(digitWidth.W) //计算2^BoothBits
  val digitMagnitude = Mux( //得到Booth数字的绝对值
    IO.Window(boothBits), //窗口最高位为1时表示负数字
    radixValue - rawMagnitude, //负数字的绝对值等于2^B-raw
    rawMagnitude //窗口最高位为0时直接使用raw
  )
  val multiplicandWide =
    if (productWidth == multiplicandWidth) IO.Multiplicand
    else Cat(0.U((productWidth - multiplicandWidth).W), IO.Multiplicand) //零扩展被乘数
  var positiveProduct: UInt = 0.U(productWidth.W) //正幅值部分积累加器
  // 幅值恒小于 2^BoothBits（负分支最大为 RadixValue-RawMagnitude，
  // 正分支 RawMagnitude < 2^(BoothBits-1)），因此第 BoothBits 位恒为 0，
  // 不需要为它展开一级零加数。
  for (index <- 0 until boothBits) {
    val shifted = (multiplicandWide << index)(productWidth - 1, 0) //生成2^Index乘数
    val addend = Mux(digitMagnitude(index), shifted, 0.U(productWidth.W)) //按幅值位选择加数
    positiveProduct = (positiveProduct +& addend)(productWidth - 1, 0) //移位加法累加并截断
  }
  val negativeProduct = (~positiveProduct + 1.U(productWidth.W))(productWidth - 1, 0) //生成负部分积的补码
  IO.PartialProduct := Mux(IO.Window(boothBits), negativeProduct, positiveProduct) //输出带符号部分积
}
