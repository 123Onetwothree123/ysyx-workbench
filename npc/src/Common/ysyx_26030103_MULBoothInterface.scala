package ysyx_26030103.common
import chisel3._
//Booth编码器参数。
object ysyx_26030103_MULBoothConfig {
  final val OperandWidth: Int = 32
  def IsValidRadix(Radix: Int): Boolean = {
    Radix >= 2 && (Radix & (Radix - 1)) == 0
  }
  def BoothBits(Radix: Int): Int = {
    require(IsValidRadix(Radix), "Radix必须是大于等于2的2的幂")
    Integer.numberOfTrailingZeros(Radix)
  }
  def WindowWidth(Radix: Int): Int = BoothBits(Radix) + 1
  def MultiplicandWidth(Radix: Int): Int = OperandWidth + BoothBits(Radix)
  def PartialProductWidth(Radix: Int): Int = MultiplicandWidth(Radix) * 2
}
//Booth编码器组合接口；输出未移位的补码部分积。
class ysyx_26030103_MULBoothEncoderInterface(val Radix: Int) extends Bundle {
  val Multiplicand = Input(UInt(ysyx_26030103_MULBoothConfig.MultiplicandWidth(Radix).W)) //被乘数
  val Window = Input(UInt(ysyx_26030103_MULBoothConfig.WindowWidth(Radix).W)) //乘数窗口
  val PartialProduct = Output(UInt(ysyx_26030103_MULBoothConfig.PartialProductWidth(Radix).W)) //未移位部分积
}
