package ysyx_26030103.common

object ysyx_26030103_Validation {
  def IsPowerOfTwo(value: Int): Boolean =
    value > 0 && (value & (value - 1)) == 0

  def IsPowerOfTwo(value: Long): Boolean =
    value > 0L && (value & (value - 1L)) == 0L

  def RequirePowerOfTwo(value: Int, message: => String): Int = {
    require(IsPowerOfTwo(value), message)
    value
  }

  def RequirePowerOfTwo(value: Long, message: => String): Long = {
    require(IsPowerOfTwo(value), message)
    value
  }

  def RequireXLEN(value: Int, owner: String, message: => String): Int = {
    try {
      ysyx_26030103_XLENRequire(value, owner)
      value
    } catch {
      case _: IllegalArgumentException =>
        throw new IllegalArgumentException(message)
    }
  }

  def OnOff(flag: Boolean): String =
    if (flag) "on"
    else "off"
}
