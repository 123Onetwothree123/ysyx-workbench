package ysyx_26030103.exu

import chisel3._
import _root_.ysyx_26030103.common._

/**
  * MUL 后端算法的公共 Module 边界。
  *
  * 普通移位累加、Booth 基 2/基 4、Wallace 和 Dadda 都使用同一套
  * 无符号幅值请求/响应协议；延迟、编码方式和流水级数由后端自行决定。
  */
abstract class ysyx_26030103_MULCore extends Module {
  final val IO = _root_.chisel3.IO(new ysyx_26030103_MULCoreInterface)
}
