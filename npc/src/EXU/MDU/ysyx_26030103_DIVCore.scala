package ysyx_26030103.exu
import chisel3._
import _root_.ysyx_26030103.common._
//DIV算法后端边界。
abstract class ysyx_26030103_DIVCore extends Module {
  final val IO = _root_.chisel3.IO(new ysyx_26030103_DIVCoreInterface) //除法核心接口
}
