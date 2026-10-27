package ysyx

import java.nio.file.{Files, Path, Paths}

import org.json4s._
import org.json4s.jackson.JsonMethods

/** ysyxSoC 的配置入口。
  *
  * npc/Makefile 生成的 typed JSON 是 SoC 与 NPC 共用的唯一有效配置源。
  * 这里只读取 `soc` 段；JSON 缺失或格式错误时直接失败。
  */
object SoCConfig {
  private val configPath: Path = {
    val candidates = Seq("NPC_CONFIG_JSON", "NPC_CONFIG_EFFECTIVE_JSON")
    val path = candidates.iterator
      .flatMap(name => sys.env.get(name).iterator)
      .map(_.trim)
      .find(_.nonEmpty)
      .map(Paths.get(_))
      .getOrElse {
        throw new IllegalArgumentException(
          "缺少 NPC_CONFIG_JSON；请先运行 make -C npc npc-config"
        )
      }
    if (!Files.isRegularFile(path)) {
      throw new IllegalArgumentException(
        s"NPC_CONFIG_JSON 指向的 SoC 配置文件不存在：$path"
      )
    }
    path
  }

  private lazy val document: JValue = {
    try {
      JsonMethods.parse(Files.readString(configPath))
    } catch {
      case error: Exception =>
        throw new IllegalArgumentException(
          s"无法解析 SoC 配置 JSON：$configPath：${error.getMessage}",
          error
        )
    }
  }

  def source: String = configPath.toString

  def hasJson: Boolean = true

  private def lookup(path: String): Option[JValue] = {
    path
      .split('.')
      .foldLeft(Option(document)) {
        case (Some(JObject(fields)), segment) =>
          fields.collectFirst {
            case (key, value) if key == segment => value
          }
        case _ => None
      }
      .filterNot(value => value == JNothing || value == JNull)
  }

  def int(path: String, fallback: => Int): Int = lookup(path) match {
    case Some(JInt(value))     => value.toInt
    case Some(JLong(value))    => value.toInt
    case Some(JDouble(value))  => value.toInt
    case Some(JDecimal(value)) => value.toInt
    case Some(JString(value))  => value.trim.toInt
    case Some(other) =>
      throw new IllegalArgumentException(
        s"SoC 配置项 $path 不是整数：$other"
      )
    case None => fallback
  }

  def boolean(path: String, fallback: => Boolean): Boolean = lookup(path) match {
    case Some(JBool(value)) => value
    case Some(JInt(value))  => value != 0
    case Some(JString(value)) =>
      value.trim.toLowerCase match {
        case "y" | "yes" | "true" | "1" => true
        case "n" | "no" | "false" | "0" | "" => false
        case other =>
          throw new IllegalArgumentException(
            s"SoC 配置项 $path 不是布尔值：'$other'"
          )
      }
    case Some(other) =>
      throw new IllegalArgumentException(
        s"SoC 配置项 $path 不是布尔值：$other"
      )
    case None => fallback
  }

  def string(path: String, fallback: => String): String = lookup(path) match {
    case Some(JString(value)) => value
    case Some(other) =>
      throw new IllegalArgumentException(
        s"SoC 配置项 $path 不是字符串：$other"
      )
    case None => fallback
  }
}
