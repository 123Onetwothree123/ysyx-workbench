package ysyx_26030103.cache

import chisel3._

/** 指令缓存实现的公共基类：IO在基类固定，保证所有实现端口一致。 */
abstract class ysyx_26030103_ICacheBase(AddressWidth: Int = 32) extends Module {
  final val io = IO(new ysyx_26030103_ICacheInterface(AddressWidth))
}
