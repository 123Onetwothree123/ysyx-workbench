package ysyx_26030103.config

/** 由Kconfig选择的乘法器压缩器（GPC）配置。
  *
  * 输入和到输出编码的映射在elaboration期由动态规划求解：保证每个可能的
  * 输入和都有精确表示，并在默认关闭冗余表示时拒绝多义编码。求解结果
  * `EncodingTable` 供 `ysyx_26030103_MULGPC` 查表使用。
  */
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
