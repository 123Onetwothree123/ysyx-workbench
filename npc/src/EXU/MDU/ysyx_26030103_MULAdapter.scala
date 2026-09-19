package ysyx_26030103.exu
import chisel3._
import _root_.ysyx_26030103.common._
//MUL ISA适配层边界。
abstract class ysyx_26030103_MULAdapter extends Module {
  final val IO = _root_.chisel3.IO(new ysyx_26030103_MULAdapterInterface) //乘法适配接口
}
