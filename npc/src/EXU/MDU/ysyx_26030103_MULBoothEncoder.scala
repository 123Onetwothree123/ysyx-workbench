package ysyx_26030103.exu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
class ysyx_26030103_MULBoothEncoder(val Radix: Int) extends Module {
  private val BoothBits = ysyx_26030103_MULBoothConfig.BoothBits(Radix) //每组处理位数
  final val IO = _root_.chisel3.IO(new ysyx_26030103_MULBoothEncoderInterface(Radix))
  private val WindowWidth = ysyx_26030103_MULBoothConfig.WindowWidth(Radix) //窗口宽度等于BoothBits+1
  private val DigitWidth = BoothBits + 1 //有符号数字的内部宽度
  private val ProductWidth = ysyx_26030103_MULBoothConfig.PartialProductWidth(Radix) //部分积宽度
  private val MultiplicandWidth = ysyx_26030103_MULBoothConfig.MultiplicandWidth(Radix) //被乘数扩展宽度
  val WindowExtended = Cat(0.U(1.W), IO.Window) //在窗口最高位补0，防止到时候搞个加1溢出
  val RoundedWindow = WindowExtended + 1.U((WindowWidth + 1).W) //计算window+1
  val RawMagnitude = (RoundedWindow >> 1)(DigitWidth - 1, 0) //计算floor((window+1)/2)
  val RadixValue = (BigInt(1) << BoothBits).U(DigitWidth.W) //计算2^BoothBits
  val DigitMagnitude = Mux( //得到Booth数字的绝对值
    IO.Window(BoothBits), //窗口最高位为1时表示负数字
    RadixValue - RawMagnitude, //负数字的绝对值等于2^B-raw
    RawMagnitude //窗口最高位为0时直接使用raw
  )
  val MultiplicandWide =
    if (ProductWidth == MultiplicandWidth) IO.Multiplicand
    else Cat(0.U((ProductWidth - MultiplicandWidth).W), IO.Multiplicand) //零扩展被乘数
  var PositiveProduct: UInt = 0.U(ProductWidth.W) //正幅值部分积累加器
  for (Index <- 0 until DigitWidth) {
    val Shifted = (MultiplicandWide << Index)(ProductWidth - 1, 0) //生成2^Index乘数
    val Addend = Mux(DigitMagnitude(Index), Shifted, 0.U(ProductWidth.W)) //按幅值位选择加数
    PositiveProduct = (PositiveProduct +& Addend)(ProductWidth - 1, 0) //移位加法累加并截断
  }
  val NegativeProduct = (~PositiveProduct + 1.U(ProductWidth.W))(ProductWidth - 1, 0) //生成负部分积的补码
  IO.PartialProduct := Mux(IO.Window(BoothBits), NegativeProduct, PositiveProduct) //输出带符号部分积
}
