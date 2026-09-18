package ysyx_26030103.exu

import chisel3._
import _root_.ysyx_26030103.common._

/**
  * ISA MUL 适配层的公共 Module 边界。
  *
  * 适配层负责符号归一化、符号恢复和高低 32 位结果选择，
  * 内部可以连接任意 MULCore 后端。
  */
abstract class ysyx_26030103_MULAdapter extends Module {
  final val IO = _root_.chisel3.IO(new ysyx_26030103_MULAdapterInterface)
}
