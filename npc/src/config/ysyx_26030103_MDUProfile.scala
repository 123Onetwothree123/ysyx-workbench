package ysyx_26030103.config

import _root_.ysyx_26030103.common.{ysyx_26030103_KconfigDefaults => KconfigDefaults}

case class ysyx_26030103_MULShiftAddParams(
    IterBits: Int = KconfigDefaults.MDUMulShiftIterBits,
    Split: Int = KconfigDefaults.MDUMulShiftSplit,
    EarlyOut: Boolean = false
)

case class ysyx_26030103_MULCompressionParams(
    Pipeline: Int = KconfigDefaults.MDUMulPipeline,
    Compressor: ysyx_26030103_MULGPCConfig = ysyx_26030103_MULGPCConfig()
)

case class ysyx_26030103_MULProfile(
    Width: Int = KconfigDefaults.MDUMulWidth,
    Encoding: ysyx_26030103_MULEncoding = ysyx_26030103_MULEncoding.Plain,
    Radix: Int = KconfigDefaults.MDUMulRadix,
    ShiftAdd: ysyx_26030103_MULShiftAddParams =
      ysyx_26030103_MULShiftAddParams(),
    Compression: ysyx_26030103_MULCompressionParams =
      ysyx_26030103_MULCompressionParams()
)

case class ysyx_26030103_DIVParams(
    Radix: Int = KconfigDefaults.MDUDivRestoringRadix,
    IterBits: Int = KconfigDefaults.MDUDivRestoringIterBits,
    EarlyOut: Boolean = false
)

case class ysyx_26030103_DIVProfile(
    Restoring: ysyx_26030103_DIVParams = ysyx_26030103_DIVParams(
      KconfigDefaults.MDUDivRestoringRadix,
      KconfigDefaults.MDUDivRestoringIterBits
    ),
    NonRestoring: ysyx_26030103_DIVParams = ysyx_26030103_DIVParams(
      KconfigDefaults.MDUDivNonRestoringRadix,
      KconfigDefaults.MDUDivNonRestoringIterBits
    ),
    SRT: ysyx_26030103_DIVParams = ysyx_26030103_DIVParams(
      KconfigDefaults.MDUDivSRTRadix,
      KconfigDefaults.MDUDivSRTIterBits
    )
) {
  def For(impl: ysyx_26030103_DIVImpl): ysyx_26030103_DIVParams = impl match {
    case ysyx_26030103_DIVImpl.Restoring    => Restoring
    case ysyx_26030103_DIVImpl.NonRestoring => NonRestoring
    case ysyx_26030103_DIVImpl.SRT          => SRT
  }
}

case class ysyx_26030103_RocketMDUParams(
    MULUnroll: Int = KconfigDefaults.MDURocketMulUnroll,
    DIVUnroll: Int = KconfigDefaults.MDURocketDivUnroll,
    MULEarlyOut: Boolean = false,
    DIVEarlyOut: Boolean = false,
    DIVEarlyOutGranularity: Int =
      KconfigDefaults.MDURocketDivEarlyOutGranularity
)

case class ysyx_26030103_MDUProfile(
    MUL: ysyx_26030103_MULProfile = ysyx_26030103_MULProfile(),
    DIV: ysyx_26030103_DIVProfile = ysyx_26030103_DIVProfile(),
    Rocket: ysyx_26030103_RocketMDUParams = ysyx_26030103_RocketMDUParams()
)
