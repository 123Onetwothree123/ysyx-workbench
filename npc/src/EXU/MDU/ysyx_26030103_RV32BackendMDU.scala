package ysyx_26030103.exu

import chisel3._
import _root_.ysyx_26030103.config._

/** RV32 专用 MDU 后端：把项目供应商 profile 映射到一个 StandardMDU。 */
class ysyx_26030103_RV32BackendMDU(
    override val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig(),
    MULImpl: ysyx_26030103_MULImpl = ysyx_26030103_MULImpl.ShiftAdd,
    DIVImpl: ysyx_26030103_DIVImpl = ysyx_26030103_DIVImpl.Restoring,
    MDUProfile: ysyx_26030103_MDUProfile = ysyx_26030103_MDUProfile()
) extends ysyx_26030103_MDU(config) {
  require(config.XLEN == 32, "RV32 专用 MDU 后端不能在 XLEN=64 下生成")
  private val backendConfig = config.copy(
    MDUImpl = ysyx_26030103_MDUImpl.Standard,
    MULImpl = MULImpl,
    DIVImpl = DIVImpl,
    MDUProfile = MDUProfile
  )
  private val core = Module(new ysyx_26030103_StandardMDU(backendConfig))
  core.io.Request <> io.Request
  core.io.Response <> io.Response
  core.io.Flush := io.Flush
  io.PerfDivIterations := core.io.PerfDivIterations
  io.PerfDivEarlyOut := core.io.PerfDivEarlyOut
}

object ysyx_26030103_RV32BackendMDU {
  // 每个 Kconfig 选项对应一套固定的 MUL/DIV/profile 组合。
  def create(config: ysyx_26030103_NPCConfig): ysyx_26030103_RV32BackendMDU = {
    val (mulImpl, divImpl, mduProfile) = config.MDUImpl match {
      case ysyx_26030103_MDUImpl.Ibex =>
        (ysyx_26030103_MULImpl.ShiftAdd, ysyx_26030103_DIVImpl.Restoring,
          ysyx_26030103_MDUProfile())
      case ysyx_26030103_MDUImpl.Cve2 =>
        (ysyx_26030103_MULImpl.Wallace, ysyx_26030103_DIVImpl.NonRestoring,
          ysyx_26030103_MDUProfile())
      case ysyx_26030103_MDUImpl.Cv32e40p =>
        (ysyx_26030103_MULImpl.Dadda, ysyx_26030103_DIVImpl.SRT,
          ysyx_26030103_MDUProfile())
      case ysyx_26030103_MDUImpl.PicoRV32 =>
        (ysyx_26030103_MULImpl.ShiftAdd, ysyx_26030103_DIVImpl.Restoring,
          ysyx_26030103_MDUProfile(
            MUL = ysyx_26030103_MULProfile(
              ShiftAdd = ysyx_26030103_MULShiftAddParams(
                IterBits = 2,
                Split = 2,
                EarlyOut = false
              )
            )
          ))
      case other =>
        throw new IllegalArgumentException(
          s"MDU_IMPL=${other.Name} 不是 RV32 backend")
    }
    new ysyx_26030103_RV32BackendMDU(
      config,
      mulImpl,
      divImpl,
      mduProfile
    )
  }
}
