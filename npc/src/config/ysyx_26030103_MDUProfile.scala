package ysyx_26030103.config

case class ysyx_26030103_MULShiftAddParams(
    IterBits: Int = 1,
    Split: Int = 1,
    EarlyOut: Boolean = false
)

case class ysyx_26030103_MULCompressionParams(
    Pipeline: Int = 0,
    Compressor: ysyx_26030103_MULGPCConfig = ysyx_26030103_MULGPCConfig()
)

case class ysyx_26030103_MULProfile(
    Width: Int = 32,
    Encoding: ysyx_26030103_MULEncoding = ysyx_26030103_MULEncoding.Plain,
    Radix: Int = 4,
    ShiftAdd: ysyx_26030103_MULShiftAddParams =
      ysyx_26030103_MULShiftAddParams(),
    Compression: ysyx_26030103_MULCompressionParams =
      ysyx_26030103_MULCompressionParams()
)

case class ysyx_26030103_DIVParams(
    Radix: Int = 2,
    IterBits: Int = 1,
    EarlyOut: Boolean = false
)

case class ysyx_26030103_DIVProfile(
    Restoring: ysyx_26030103_DIVParams = ysyx_26030103_DIVParams(),
    NonRestoring: ysyx_26030103_DIVParams = ysyx_26030103_DIVParams(),
    SRT: ysyx_26030103_DIVParams = ysyx_26030103_DIVParams()
) {
  def For(Impl: ysyx_26030103_DIVImpl): ysyx_26030103_DIVParams = Impl match {
    case ysyx_26030103_DIVImpl.Restoring    => Restoring
    case ysyx_26030103_DIVImpl.NonRestoring => NonRestoring
    case ysyx_26030103_DIVImpl.SRT          => SRT
  }
}

case class ysyx_26030103_MDUProfile(
    MUL: ysyx_26030103_MULProfile = ysyx_26030103_MULProfile(),
    DIV: ysyx_26030103_DIVProfile = ysyx_26030103_DIVProfile()
)
