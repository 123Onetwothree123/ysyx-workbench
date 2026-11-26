package ysyx_26030103.common

object ysyx_26030103_XLENRequire {
  def apply(value: Int, owner: String): Unit =
    require(value == 32 || value == 64, s"$owner XLEN 非法: $value")
}
