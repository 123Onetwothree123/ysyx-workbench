package ysyx_26030103.fpu
import chisel3._
import _root_.ysyx_26030103.config._
class ysyx_26030103_FudianFPU(Config: ysyx_26030103_NPCConfig)
    extends ysyx_26030103_FudianFPUBase(Config)
    with ysyx_26030103_FudianFPUArith
    with ysyx_26030103_FudianFPUResult
    with ysyx_26030103_FudianFPUResponse {
}
