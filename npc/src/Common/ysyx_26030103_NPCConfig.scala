package ysyx_26030103.common
//kconfig来选择
sealed trait ysyx_26030103_MulImpl {
  def Name: String
}
object ysyx_26030103_MulImpl {
  case object ReuseAdder extends ysyx_26030103_MulImpl {
    val Name = "reuse_adder"
  }
  case object Wallace extends ysyx_26030103_MulImpl {
    val Name = "wallace" // 华莱士
  }
  case object Dadda extends ysyx_26030103_MulImpl {
    val Name = "dadda"
  }
  def FromString(s: String): ysyx_26030103_MulImpl = s match {
    case "reuse_adder" => ReuseAdder
    case "wallace"     => Wallace
    case "dadda"       => Dadda
    case other => throw new IllegalArgumentException(s"这个乘法器没有实现: $other")
  }
}
case class ysyx_26030103_NPCConfig(
    UseM: Boolean = false,
    UseA: Boolean = false,
    UseC: Boolean = false,
    MulImpl: ysyx_26030103_MulImpl = ysyx_26030103_MulImpl.ReuseAdder,
    // 目标平台
    ResetAddr: Long = 0x30000000L,
    AddressWidth: Int = 32,
    // icache
    BlockSizeLog2: Int = 4,
    IndexBits: Int = 5,
    CacheableBase: Long = 0x80000000L,
    CacheableMask: Long = 0x80000000L,
    // 分支预测
    BTBBits: Int = 4,
    BTBWays: Int = 1,
    JalBTBBits: Int = 4,
    JalBTBWays: Int = 1,
    RASBits: Int = 4
) {
  require(!UseA, "RV32_A还没有做")
  require(!UseC, "RV32_C还没做")
  private def OnOff(Flag: Boolean): String = {
    if (Flag) { "on" }
    else { "off" }
  }
  def ISAString: String = {
    val MExt = if (UseM) { "M" }
    else { "" }
    val AExt = if (UseA) { "A" }
    else { "" }
    val CExt = if (UseC) { "C" }
    else { "" }
    "RV32I" + MExt + AExt + CExt
  }
  def Describe: String = {
    val Isa = ISAString
    val MOff = OnOff(UseM)
    val AOff = OnOff(UseA)
    val COff = OnOff(UseC)
    s"ISA=$Isa, M=$MOff, A=$AOff, C=$COff, mul=${MulImpl.Name}, " +
      s"ICache=${1 << BlockSizeLog2}B/${1 << IndexBits}sets, " +
      s"BTB=${1 << BTBBits}x$BTBWays, JalBTB=${1 << JalBTBBits}x$JalBTBWays, " +
      s"RAS=${1 << RASBits}, reset=0x${ResetAddr.toHexString}"
  }
}
