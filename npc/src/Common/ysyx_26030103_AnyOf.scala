package ysyx_26030103.common

import chisel3._

object ysyx_26030103_AnyOf {
  def apply(choices: Seq[Bool]): Bool =
    choices.reduceOption(_ || _).getOrElse(false.B)
}
