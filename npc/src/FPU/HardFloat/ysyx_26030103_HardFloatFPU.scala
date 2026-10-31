package ysyx_26030103.fpu

import chisel3._
import _root_.ysyx_26030103.config._

// Berkeley HardFloat 适配层：对外 IEEE-754，内部 RecFN，FMA 可选 MulAddRecFN/ExactFMA。
class ysyx_26030103_HardFloatFPU(Config: ysyx_26030103_NPCConfig)
    extends ysyx_26030103_HardFloatFPUBase(Config)
    with ysyx_26030103_HardFloatFPUArith
    with ysyx_26030103_HardFloatFPUResult
    with ysyx_26030103_HardFloatFPUResponse {
  // 未使用的 compare 结果显式消费，避免 lint 误报。
  dontTouch(cmpS.io.gt)
  dontTouch(cmpD.io.gt)
}
