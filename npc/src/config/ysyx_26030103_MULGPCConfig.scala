package ysyx_26030103.config

import _root_.ysyx_26030103.common.{ysyx_26030103_KconfigDefaults => KconfigDefaults}

/** 由 Kconfig 选择的乘法器压缩器（GPC）配置。
  *
  * 输入和到输出编码的映射在 elaboration 期由动态规划求解，保证每个可能的
  * 输入和都有精确表示，并在默认关闭冗余表示时拒绝多义编码；结果
  * `EncodingTable` 供 `ysyx_26030103_MULGPC` 查表。
  */
case class ysyx_26030103_MULGPCConfig(
    Inputs: Int = KconfigDefaults.MDUGpcInputs,
    Outputs: Int = KconfigDefaults.MDUGpcOutputs,
    OutputOffsets: IndexedSeq[Int] = KconfigDefaults.MDUGpcOutputOffsets,
    HasCin: Boolean = false,
    HasCout: Boolean = false,
    CoutOffset: Int = KconfigDefaults.MDUGpcCoutOffset,
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
  private val outputWeights: IndexedSeq[BigInt] =
    AllOutputOffsets.map(offset => BigInt(1) << offset)
  private def betterEncoding(left: BigInt, right: BigInt): BigInt = {
    val leftBits = left.bitCount
    val rightBits = right.bitCount
    if (leftBits < rightBits || (leftBits == rightBits && left < right)) left else right
  }
  private def buildEncodings(maxInputSum: Int): IndexedSeq[BigInt] = {
    val counts = Array.fill(maxInputSum + 1)(0)
    val best = Array.fill[Option[BigInt]](maxInputSum + 1)(None)
    counts(0) = 1
    best(0) = Some(BigInt(0))
    for ((weight, bit) <- outputWeights.zipWithIndex if weight <= maxInputSum) {
      val weightValue = weight.toInt
      for (sum <- maxInputSum to weightValue by -1) {
        if (counts(sum - weightValue) > 0) {
          counts(sum) = math.min(2, counts(sum) + counts(sum - weightValue))
          val candidate = best(sum - weightValue).get | (BigInt(1) << bit)
          best(sum) = best(sum).map(betterEncoding(_, candidate)).orElse(Some(candidate))
        }
      }
    }
    (0 to maxInputSum).map { sum =>
      require(best(sum).nonEmpty, s"MUL GPC配置无法精确表示输入和$sum")
      require(
        AllowRedundant || counts(sum) == 1,
        s"MUL GPC配置在输入和$sum 上存在多种输出编码；若这是有意的，请打开冗余表示"
      )
      best(sum).get
    }.toIndexedSeq
  }
  final val EncodingTable: IndexedSeq[BigInt] = buildEncodings(InputCountWithCin)
  def ActiveOutputBits(maxDataInputs: Int, cinPossible: Boolean = false): IndexedSeq[Int] = {
    val maxSum = maxDataInputs + (if (HasCin && cinPossible) 1 else 0)
    val usedMask = EncodingTable.take(maxSum + 1).foldLeft(BigInt(0))(_ | _)
    (0 until OutputCountWithCout).filter(bit => ((usedMask >> bit) & 1) == 1).toIndexedSeq
  }
  final val FullActiveOutputBits: IndexedSeq[Int] = ActiveOutputBits(Inputs, HasCin)
  require(
    FullActiveOutputBits.length < InputCountWithCin,
    "MUL GPC完整输入时必须减少dot数量"
  )
  def Describe: String = {
    val cinDesc = if (HasCin) "+cin" else ""
    val coutDesc = if (HasCout) s"+cout@$CoutOffset" else ""
    val redundantDesc = if (AllowRedundant) ",redundant" else ""
    s"$Inputs:$Outputs$cinDesc$coutDesc@${OutputOffsets.mkString("[", ",", "]")}$redundantDesc"
  }
}
