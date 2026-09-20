package ysyx_26030103.common
//由Kconfig选择的乘法器结构。
sealed trait ysyx_26030103_MULImpl {
  def Name: String
}
object ysyx_26030103_MULImpl {
  case object ShiftAdd extends ysyx_26030103_MULImpl {
    val Name = "shift_add"
  }
  case object Wallace extends ysyx_26030103_MULImpl {
    val Name = "wallace"
  }
  case object Dadda extends ysyx_26030103_MULImpl {
    val Name = "dadda"
  }
  def FromString(s: String): ysyx_26030103_MULImpl = s match {
    case "shift_add" => ShiftAdd
    case "wallace" => Wallace
    case "dadda" => Dadda
    case other => throw new IllegalArgumentException(s"这个乘法器没有实现: $other")
  }
}
//部分积编码与乘法器结构正交。
sealed trait ysyx_26030103_MULEncoding {
  def Name: String
}
object ysyx_26030103_MULEncoding {
  case object Plain extends ysyx_26030103_MULEncoding {
    val Name = "plain"
  }
  case object Booth extends ysyx_26030103_MULEncoding {
    val Name = "booth"
  }
  def FromString(s: String): ysyx_26030103_MULEncoding = s match {
    case "plain" => Plain
    case "booth" => Booth
    case other => throw new IllegalArgumentException(s"这个乘法编码没有实现: $other")
  }
}
//由Kconfig选择的除法器结构。
sealed trait ysyx_26030103_DIVImpl {
  def Name: String
}
object ysyx_26030103_DIVImpl {
  case object Restoring extends ysyx_26030103_DIVImpl {
    val Name = "restoring"
  }
  case object NonRestoring extends ysyx_26030103_DIVImpl {
    val Name = "nonrestoring"
  }
  case object SRT extends ysyx_26030103_DIVImpl {
    val Name = "srt"
  }
  def FromString(Value: String): ysyx_26030103_DIVImpl = Value match {
    case "restoring" => Restoring
    case "nonrestoring" => NonRestoring
    case "srt" => SRT
    case other => throw new IllegalArgumentException(s"这个除法器没有实现: $other")
  }
}
case class ysyx_26030103_NPCConfig(
    UseM: Boolean = false,
    UseA: Boolean = false,
    UseC: Boolean = false,
    MULImpl: ysyx_26030103_MULImpl = ysyx_26030103_MULImpl.ShiftAdd,
    MULEncoding: ysyx_26030103_MULEncoding = ysyx_26030103_MULEncoding.Plain,
    DIVImpl: ysyx_26030103_DIVImpl = ysyx_26030103_DIVImpl.Restoring,
    MULRadix: Int = 4,
    DIVRadix: Int = 2,
    DIVIterBits: Int = 1,
    DIVEarlyOut: Boolean = false,
    MULIterBits: Int = 1,
    MULPipeline: Int = 0,
    MULSplit: Int = 1,
    MULEarlyOut: Boolean = false,
    // 目标平台
    ResetAddr: Long = 0x30000000L,
    AddressWidth: Int = 32,
    // caches
    ICacheEnable: Boolean = true,
    DCacheEnable: Boolean = false,
    BlockSizeLog2: Int = 4,
    IndexBits: Int = 5,
    // LSU 写缓冲项数(必须是2的幂, 环形队列按位回绕)
    WBufDepth: Int = 4,
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
  require(
    Integer.bitCount(WBufDepth) == 1,
    "WBufDepth必须是2的幂(1/2/4/8/16)"
  )
  require(
    ysyx_26030103_MULBoothConfig.IsValidRadix(MULRadix),
    "MULRadix必须是大于等于2的2的幂"
  )
  require(ysyx_26030103_MULBoothConfig.IsValidRadix(DIVRadix), "DIVRadix必须是大于等于2的2的幂")
  require(DIVIterBits >= 1 && DIVIterBits <= 4, "DIVIterBits必须在1到4之间")
  require(MULIterBits >= 1 && MULIterBits <= 8, "MULIterBits必须在1到8之间")
  require(MULPipeline >= 0 && MULPipeline <= 4, "MULPipeline必须在0到4之间")
  require(MULSplit >= 1 && MULSplit <= 4, "MULSplit必须在1到4之间")
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
    val ISA = ISAString
    val MOff = OnOff(UseM)
    val AOff = OnOff(UseA)
    val COff = OnOff(UseC)
    val ICacheDesc =
      if (ICacheEnable) { s"${1 << BlockSizeLog2}B/${1 << IndexBits}sets" }
      else { "off" }
    val DCacheDesc =
      if (DCacheEnable) { s"${1 << BlockSizeLog2}B/${1 << IndexBits}sets" }
      else { "off" }
    s"ISA=$ISA, M=$MOff, A=$AOff, C=$COff, " +
      s"mul=${MULImpl.Name}/${MULEncoding.Name}, " +
      s"mul_radix=$MULRadix, div=${DIVImpl.Name}, div_radix=$DIVRadix, div_iter=$DIVIterBits, " +
      s"iter=$MULIterBits, pipeline=$MULPipeline, split=$MULSplit, " +
      s"mul_early_out=${OnOff(MULEarlyOut)}, div_early_out=${OnOff(DIVEarlyOut)}, " +
      s"ICache=$ICacheDesc, DCache=$DCacheDesc, WBuf=$WBufDepth, " +
      s"BTB=${1 << BTBBits}x$BTBWays, JalBTB=${1 << JalBTBBits}x$JalBTBWays, " +
      s"RAS=${1 << RASBits}, reset=0x${ResetAddr.toHexString}"
  }
}
