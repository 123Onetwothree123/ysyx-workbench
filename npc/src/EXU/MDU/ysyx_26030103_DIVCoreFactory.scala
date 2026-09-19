package ysyx_26030103.exu
import chisel3._
import _root_.ysyx_26030103.common.ysyx_26030103_NPCConfig
import _root_.ysyx_26030103.common.ysyx_26030103_DIVImpl
//选择除法后端。
object ysyx_26030103_DIVCoreFactory {
  def Create(config: ysyx_26030103_NPCConfig): ysyx_26030103_DIVCore = {
    config.DIVImpl match {
      case ysyx_26030103_DIVImpl.Restoring => Module(new ysyx_26030103_DIVRestoringCore(config))
      case ysyx_26030103_DIVImpl.NonRestoring => Module(new ysyx_26030103_DIVNonRestoringCore(config))
      case ysyx_26030103_DIVImpl.SRT => Module(new ysyx_26030103_DIVSRTCore(config))
    }
  }
}
