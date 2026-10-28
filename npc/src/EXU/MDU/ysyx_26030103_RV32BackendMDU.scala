package ysyx_26030103.exu

import chisel3._
import _root_.ysyx_26030103.config._

abstract class ysyx_26030103_RV32BackendMDU(
    override val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_MDU(config) {
  require(config.XLEN == 32, "RV32 专用 MDU 后端不能在 XLEN=64 下生成")
  protected def backendConfig: ysyx_26030103_NPCConfig
  private val core = Module(new ysyx_26030103_StandardMDU(backendConfig))
  core.io.Req <> io.Req
  core.io.Resp <> io.Resp
  core.io.Flush := io.Flush
}

class ysyx_26030103_IbexMDU(
    override val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_RV32BackendMDU(config) {
  override protected def backendConfig: ysyx_26030103_NPCConfig =
    config.copy(
      MDUImpl = ysyx_26030103_MDUImpl.Standard,
      MULImpl = ysyx_26030103_MULImpl.ShiftAdd,
      DIVImpl = ysyx_26030103_DIVImpl.Restoring,
      MDUProfile = ysyx_26030103_MDUProfile()
    )
}

class ysyx_26030103_CVE2MDU(
    override val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_RV32BackendMDU(config) {
  override protected def backendConfig: ysyx_26030103_NPCConfig =
    config.copy(
      MDUImpl = ysyx_26030103_MDUImpl.Standard,
      MULImpl = ysyx_26030103_MULImpl.Wallace,
      DIVImpl = ysyx_26030103_DIVImpl.NonRestoring,
      MDUProfile = ysyx_26030103_MDUProfile()
    )
}

class ysyx_26030103_CV32E40PMDU(
    override val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_RV32BackendMDU(config) {
  override protected def backendConfig: ysyx_26030103_NPCConfig =
    config.copy(
      MDUImpl = ysyx_26030103_MDUImpl.Standard,
      MULImpl = ysyx_26030103_MULImpl.Dadda,
      DIVImpl = ysyx_26030103_DIVImpl.SRT,
      MDUProfile = ysyx_26030103_MDUProfile()
    )
}

class ysyx_26030103_PicoRV32MDU(
    override val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends ysyx_26030103_RV32BackendMDU(config) {
  override protected def backendConfig: ysyx_26030103_NPCConfig =
    config.copy(
      MDUImpl = ysyx_26030103_MDUImpl.Standard,
      MULImpl = ysyx_26030103_MULImpl.ShiftAdd,
      DIVImpl = ysyx_26030103_DIVImpl.Restoring,
      MDUProfile = ysyx_26030103_MDUProfile(
        MUL = ysyx_26030103_MULProfile(
          ShiftAdd = ysyx_26030103_MULShiftAddParams(
            IterBits = 2,
            Split = 2,
            EarlyOut = false
          )
        )
      )
    )
}
