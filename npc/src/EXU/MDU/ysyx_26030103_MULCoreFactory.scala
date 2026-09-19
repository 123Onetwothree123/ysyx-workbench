package ysyx_26030103.exu
import chisel3._
import _root_.ysyx_26030103.common._
//按MULImpl选择乘法后端。
object ysyx_26030103_MULCoreFactory {
  def Create(config: ysyx_26030103_NPCConfig): ysyx_26030103_MULCore = {
    config.MULImpl match {
      case ysyx_26030103_MULImpl.ShiftAdd =>
        Module(new ysyx_26030103_MULShiftAddCore(config))
      case ysyx_26030103_MULImpl.Wallace =>
        Module(new ysyx_26030103_MULWallaceCore(config))
      case ysyx_26030103_MULImpl.Dadda =>
        Module(new ysyx_26030103_MULDaddaCore(config))
    }
  }
}
