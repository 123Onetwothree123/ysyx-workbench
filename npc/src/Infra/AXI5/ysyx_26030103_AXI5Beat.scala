package ysyx_26030103.infra

import chisel3._

private[infra] object ysyx_26030103_AXI5Beat {
  def IsLast(beat: UInt, length: UInt): Bool = beat === length
  def Next(beat: UInt): UInt = beat + 1.U
}
