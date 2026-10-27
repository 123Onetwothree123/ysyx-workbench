package ysyx

import java.nio.file.{Files, Path, Paths}

import org.json4s._
import org.json4s.jackson.JsonMethods

/** ysyxSoC 的配置入口。
  *
  * npc/Makefile 生成的 typed JSON 是 SoC 与 NPC 共用的唯一有效配置源。
  * 这里只读取 `soc` 段；JSON 不存在时才回退到旧环境变量，方便单独构建
  * ysyxSoC 或兼容旧工作区。JSON 一旦存在但格式/路径错误则直接失败，
  * 不再静默读取第二套配置。
  */
object SoCConfig {
  private val configPath: Option[Path] = {
    val candidates = Seq("NPC_CONFIG_JSON", "NPC_CONFIG_EFFECTIVE_JSON")
    candidates.iterator
      .flatMap(name => sys.env.get(name).iterator)
      .map(_.trim)
      .find(_.nonEmpty)
      .map(Paths.get(_))
  }

  private lazy val document: Option[JValue] = configPath.map { path =>
    if (!Files.isRegularFile(path)) {
      throw new IllegalArgumentException(
        s"NPC_CONFIG_JSON 指向的 SoC 配置文件不存在：$path"
      )
    }
    try {
      JsonMethods.parse(Files.readString(path))
    } catch {
      case error: Exception =>
        throw new IllegalArgumentException(
          s"无法解析 SoC 配置 JSON：$path：${error.getMessage}",
          error
        )
    }
  }

  def source: String = configPath.map(_.toString).getOrElse("<legacy environment>")

  def hasJson: Boolean = document.isDefined

  private def lookup(path: String): Option[JValue] = {
    document.flatMap { root =>
      path
        .split('.')
        .foldLeft(Option(root)) {
          case (Some(JObject(fields)), segment) =>
            fields.collectFirst {
              case (key, value) if key == segment => value
            }
          case _ => None
        }
    }.filterNot(value => value == JNothing || value == JNull)
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
