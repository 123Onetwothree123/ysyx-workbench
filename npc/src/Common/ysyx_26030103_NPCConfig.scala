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
case class ysyx_26030103_MULGPCConfig(
    Inputs: Int = 3,
    Outputs: Int = 2,
    OutputOffsets: IndexedSeq[Int] = IndexedSeq(0, 1),
    HasCin: Boolean = false,
    HasCout: Boolean = false,
    CoutOffset: Int = 1,
    AllowRedundant: Boolean = false
) {
  require(Inputs >= 3 && Inputs <= 64, "MUL GPC K必须在3到64之间")
  require(Outputs >= 1 && Outputs <= 64, "MUL GPC R必须在1到64之间")
  require(OutputOffsets.length == Outputs, "MUL GPC输出权重数量必须等于R")
  require(OutputOffsets.forall(_ >= 0), "MUL GPC输出权重偏移必须非负")
  require(CoutOffset >= 0, "MUL GPC Cout权重偏移必须非负")
  final val InputCountWithCin: Int = Inputs + (if (HasCin) 1 else 0)
  final val OutputCountWithCout: Int = Outputs + (if (HasCout) 1 else 0)
  final val AllOutputOffsets: IndexedSeq[Int] =
    OutputOffsets ++ (if (HasCout) IndexedSeq(CoutOffset) else IndexedSeq.empty)
  private val OutputWeights: IndexedSeq[BigInt] =
    AllOutputOffsets.map(Offset => BigInt(1) << Offset)
  private def BetterEncoding(Left: BigInt, Right: BigInt): BigInt = {
    val LeftBits = Left.bitCount
    val RightBits = Right.bitCount
    if (LeftBits < RightBits || (LeftBits == RightBits && Left < Right)) Left else Right
  }
  private def BuildEncodings(MaxInputSum: Int): IndexedSeq[BigInt] = {
    val Counts = Array.fill(MaxInputSum + 1)(0)
    val Best = Array.fill[Option[BigInt]](MaxInputSum + 1)(None)
    Counts(0) = 1
    Best(0) = Some(BigInt(0))
    for ((weight, bit) <- OutputWeights.zipWithIndex if weight <= MaxInputSum) {
      val W = weight.toInt
      for (sum <- MaxInputSum to W by -1) {
        if (Counts(sum - W) > 0) {
          Counts(sum) = math.min(2, Counts(sum) + Counts(sum - W))
          val Candidate = Best(sum - W).get | (BigInt(1) << bit)
          Best(sum) = Best(sum).map(BetterEncoding(_, Candidate)).orElse(Some(Candidate))
        }
      }
    }
    (0 to MaxInputSum).map { sum =>
      require(Best(sum).nonEmpty, s"MUL GPC配置无法精确表示输入和$sum")
      require(
        AllowRedundant || Counts(sum) == 1,
        s"MUL GPC配置在输入和$sum 上存在多种输出编码；若这是有意的，请打开冗余表示"
      )
      Best(sum).get
    }.toIndexedSeq
  }
  final val EncodingTable: IndexedSeq[BigInt] = BuildEncodings(InputCountWithCin)
  def ActiveOutputBits(MaxDataInputs: Int, CinPossible: Boolean = false): IndexedSeq[Int] = {
    val MaxSum = MaxDataInputs + (if (HasCin && CinPossible) 1 else 0)
    val UsedMask = EncodingTable.take(MaxSum + 1).foldLeft(BigInt(0))(_ | _)
    (0 until OutputCountWithCout).filter(Bit => ((UsedMask >> Bit) & 1) == 1).toIndexedSeq
  }
  final val FullActiveOutputBits: IndexedSeq[Int] = ActiveOutputBits(Inputs, HasCin)
  require(
    FullActiveOutputBits.length < InputCountWithCin,
    "MUL GPC完整输入时必须减少dot数量"
  )
  def Describe: String = {
    val CinDesc = if (HasCin) "+cin" else ""
    val CoutDesc = if (HasCout) s"+cout@$CoutOffset" else ""
    val RedundantDesc = if (AllowRedundant) ",redundant" else ""
    s"$Inputs:$Outputs$CinDesc$CoutDesc@${OutputOffsets.mkString("[", ",", "]")}$RedundantDesc"
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
    MULWidth: Int = 32,
    MULCompressorInputs: Int = 3,
    MULCompressorOutputs: Int = 2,
    MULCompressorOutputOffsets: IndexedSeq[Int] = IndexedSeq(0, 1),
    MULCompressorHasCin: Boolean = false,
    MULCompressorHasCout: Boolean = false,
    MULCompressorCoutOffset: Int = 1,
    MULCompressorAllowRedundant: Boolean = false,
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
    PMARegions: Seq[ysyx_26030103_PMARegion] =
      ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink = false),
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
    AddressWidth == 32,
    "当前RV32核的PC/PMA/流水线消息固定为32位；暂不支持非32位AddressWidth"
  )
  require((ResetAddr & 0x3L) == 0L, "未启用RV32_C时ResetAddr必须按4字节对齐")
  require(PMARegions.nonEmpty, "物理地址图不能为空")
  require(
    PMARegions.exists(r => ResetAddr >= r.Base && ResetAddr < r.EndExclusive && r.Executable),
    "ResetAddr必须位于可执行PMA区域"
  )
  require(
    Integer.bitCount(WBufDepth) == 1,
    "WBufDepth必须是2的幂(1/2/4/8/16)"
  )
  require(
    ysyx_26030103_MULBoothConfig.IsValidRadix(MULRadix),
    "MULRadix必须是大于等于2的2的幂"
  )
  require(
    MULWidth >= 32 && Integer.bitCount(MULWidth) == 1,
    "RV32 M扩展的MULWidth必须是不小于32的2的幂(32/64/128/... )"
  )
  require(
    MULCompressorInputs >= 3 && MULCompressorInputs <= 64,
    "MULCompressorInputs必须在3到64之间"
  )
  final val MULCompressorConfig = ysyx_26030103_MULGPCConfig(
    Inputs = MULCompressorInputs,
    Outputs = MULCompressorOutputs,
    OutputOffsets = MULCompressorOutputOffsets,
    HasCin = MULCompressorHasCin,
    HasCout = MULCompressorHasCout,
    CoutOffset = MULCompressorCoutOffset,
    AllowRedundant = MULCompressorAllowRedundant
  )
  require(
    ysyx_26030103_MULBoothConfig.IsValidRadix(DIVRadix) && DIVRadix <= 16,
    "DIVRadix必须是2/4/8/16之一，避免恢复余数除法器发生不可综合的静态展开"
  )
  require(DIVIterBits >= 1 && DIVIterBits <= 4, "DIVIterBits必须在1到4之间")
  require(MULIterBits >= 1 && MULIterBits <= 8, "MULIterBits必须在1到8之间")
  require(MULPipeline >= 0 && MULPipeline <= 4, "MULPipeline必须在0到4之间")
  require(MULSplit >= 1 && MULSplit <= 4, "MULSplit必须在1到4之间")
  // Compression-tree cores contain MULPipeline+1 elastic result slots.  The
  // iterative shift-add core remains single-outstanding regardless of that
  // otherwise inapplicable setting.  MDU adds one independent DIV slot.
  final val MULMaxInflight: Int = MULImpl match {
    case ysyx_26030103_MULImpl.ShiftAdd => 1
    case _                              => MULPipeline + 1
  }
  final val MDUMaxInflight: Int = MULMaxInflight + 1
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
      s"mul_width=$MULWidth, mul_gpc=${MULCompressorConfig.Describe}, mul_radix=$MULRadix, " +
      s"div=${DIVImpl.Name}, div_radix=$DIVRadix, div_iter=$DIVIterBits, " +
      s"iter=$MULIterBits, pipeline=$MULPipeline, split=$MULSplit, " +
      s"mul_early_out=${OnOff(MULEarlyOut)}, div_early_out=${OnOff(DIVEarlyOut)}, " +
      s"ICache=$ICacheDesc, DCache=$DCacheDesc, WBuf=$WBufDepth, " +
      s"BTB=${1 << BTBBits}x$BTBWays, JalBTB=${1 << JalBTBBits}x$JalBTBWays, " +
      s"RAS=${1 << RASBits}, reset=0x${ResetAddr.toHexString}"
  }
}
