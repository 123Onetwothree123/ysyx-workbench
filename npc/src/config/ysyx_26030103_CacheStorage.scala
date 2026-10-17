package ysyx_26030103.config

//由Kconfig选择的缓存存储介质。
sealed trait ysyx_26030103_CacheStorage {
  def Name: String
}
object ysyx_26030103_CacheStorage {
  case object Reg extends ysyx_26030103_CacheStorage {
    val Name = "reg"
  }
  case object SRAM extends ysyx_26030103_CacheStorage {
    val Name = "sram"
  }
  // 同步读SRAM但"不旁路"：命中响应老实打一拍(晚一拍)，用于量化偷懒的代价
  case object SRAMNoBypass extends ysyx_26030103_CacheStorage {
    val Name = "sram_nobypass"
  }
  def FromString(s: String): ysyx_26030103_CacheStorage = s match {
    case "reg" => Reg
    case "sram" => SRAM
    case "sram_nobypass" => SRAMNoBypass
    case other => throw new IllegalArgumentException(s"这个缓存存储没有实现: $other")
  }
}
