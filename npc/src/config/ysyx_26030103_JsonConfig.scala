package ysyx_26030103.config

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable

/** NPC 构建期配置读取器。
  *
  * `npc_config_gen --typed-json` 生成嵌套的类型化 JSON（布尔/数字/字符串/
  * 数组/对象）；Kconfig 原始的扁平 JSON 和 effective flat JSON 仍然可以
  * 直接读取。typed getter 使用点分路径，例如：
  *
  *   cfg.int("isa.xlen", 32)
  *   cfg.bool("debug.sdb", false)
  *   cfg.string("fpu.impl", "none")
  *
  * `flat` 优先使用生成器内嵌的完整扁平兼容视图；若输入是纯嵌套 typed
  * JSON，则从 typed 树反向投影出 Kconfig 风格键值。它供
  * `ysyx_26030103_MMUProfile.fromEnv` 等仍按扁平键读取的组件使用。加载
  * 旧 flat JSON 时 `flat` 就是原始键值，typed getter 会通过同一套别名
  * 映射访问对应字段，因此旧测试/直接调用不会失效。
  */
object ysyx_26030103_JsonConfig {
  val EnvironmentKey = "NPC_CONFIG_JSON"
  val FallbackEnvironmentKey = "NPC_CONFIG_EFFECTIVE_JSON"
  val DefaultPaths: Seq[String] =
    Seq("build/generated/npc_config.typed.json",
        "npc/build/generated/npc_config.typed.json",
        "build/generated/npc_config.effective.json",
        "npc/build/generated/npc_config.effective.json")

  sealed trait JsonValue
  object JsonValue {
    final case class Obj(fields: Map[String, JsonValue]) extends JsonValue
    final case class Arr(items: Vector[JsonValue]) extends JsonValue
    final case class Str(value: String) extends JsonValue
    final case class Num(text: String) extends JsonValue
    final case class Bool(value: Boolean) extends JsonValue
    case object Null extends JsonValue
  }

  /** 解析配置来源。优先环境变量，其次仓库内默认生成位置。
    *
    * 显式环境变量指向的文件不存在时立即报错，不会静默退回另一份配置；
    * 只有没有设置环境变量时才尝试默认路径，避免测试脚本误用上一次构建
    * 的陈旧配置。
    */
  def resolvePath(env: String => Option[String] = sys.env.get): Path = {
    val explicit = Seq(EnvironmentKey, FallbackEnvironmentKey)
      .flatMap(key => env(key).map(_.trim).filter(_.nonEmpty))
      .map(Paths.get(_))
    explicit.foreach { candidate =>
      if (!Files.isRegularFile(candidate))
        throw new IllegalArgumentException(
          s"NPC 硬件配置 JSON 不存在：$candidate；" +
            s"请先运行 make -C npc npc-config，或修正 $EnvironmentKey。"
        )
    }
    explicit.headOption
      .orElse(DefaultPaths.iterator.map(Paths.get(_)).find(Files.isRegularFile(_)))
      .getOrElse {
        val listed = (explicit ++ DefaultPaths.map(Paths.get(_)))
          .map(_.toString)
          .mkString(", ")
        throw new IllegalArgumentException(
          s"找不到 NPC 硬件配置 JSON；请先运行 make -C npc npc-config，" +
            s"或设置 $EnvironmentKey。已尝试：$listed"
        )
      }
  }

  def load(path: Path): Config =
    parseDocument(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
      .withSource(path)

  def load(path: String): Config = load(Paths.get(path))

  def parse(text: String): Config = parseDocument(text)

  def parseDocument(text: String): Config =
    new Config(new Parser(text).parse(), Paths.get("<inline>"))

  def parseFlat(text: String): Map[String, String] = parse(text).flat

  /** Kconfig 风格 I/O 兼容视图。 */
  final class Config private[config] (
      val root: JsonValue,
      val source: Path
  ) extends scala.collection.immutable.Map[String, String] {
    lazy val flat: Map[String, String] = root match {
      case JsonValue.Obj(fields) =>
        fields.get("flat") match {
          case Some(JsonValue.Obj(flatFields)) =>
            flatFields.collect { case (key, JsonValue.Str(value)) =>
              key -> value
            }
          case _ if fields.nonEmpty &&
              fields.values.forall(_.isInstanceOf[JsonValue.Str]) =>
            fields.collect { case (key, JsonValue.Str(value)) => key -> value }
          case _ =>
            flattenTyped(root)
        }
      case _ => Map.empty
    }

    override def get(key: String): Option[String] = {
      val direct = flat.get(key)
      if (direct.nonEmpty || !key.contains("."))
        direct
      else
        lookup(key).flatMap(valueText)
    }

    override def iterator: Iterator[(String, String)] = flat.iterator

    override def removed(key: String): Map[String, String] = flat.removed(key)

    override def updated[V1 >: String](key: String, value: V1):
        Map[String, V1] = flat.updated(key, value)

    private[config] def withSource(path: Path): Config =
      new Config(root, path)

    def option(path: String): Option[JsonValue] = lookup(path)

    def int(path: String, default: Int): Int =
      lookup(path) match {
        case None | Some(JsonValue.Null) => default
        case Some(value) => valueToLong(path, value).toInt
      }

    def long(path: String, default: Long): Long =
      lookup(path) match {
        case None | Some(JsonValue.Null) => default
        case Some(value) => valueToLong(path, value)
      }

    def bool(path: String, default: Boolean): Boolean =
      lookup(path) match {
        case None | Some(JsonValue.Null) => default
        case Some(value) => valueToBool(path, value)
      }

    def string(path: String, default: String): String =
      lookup(path) match {
        case None | Some(JsonValue.Null) => default
        case Some(value) => valueToText(path, value)
      }

    def ints(path: String, default: Seq[Int]): Seq[Int] =
      lookup(path) match {
        case None | Some(JsonValue.Null) => default
        case Some(JsonValue.Arr(items)) =>
          items.map(value => valueToLong(path, value).toInt)
        case Some(value) =>
          valueToText(path, value)
            .split(',')
            .iterator
            .map(_.trim)
            .filter(_.nonEmpty)
            .map(parseLong(path, _).toInt)
            .toSeq
      }

    private def lookup(path: String): Option[JsonValue] = {
      val segments =
        path.split('.').iterator.map(_.trim).filter(_.nonEmpty).toVector
      lookupTyped(segments).orElse {
        val legacy =
          if (segments.size == 1) Some(path)
          else FlatKeys.legacyKey(segments)
        legacy.flatMap(flat.get).map(JsonValue.Str)
      }
    }

    private def lookupTyped(segments: Vector[String]): Option[JsonValue] = {
      def descend(value: JsonValue, remaining: List[String]): Option[JsonValue] =
        remaining match {
          case Nil => Some(value)
          case head :: tail =>
            value match {
              case JsonValue.Obj(fields) =>
                fields.get(head).flatMap(descend(_, tail))
              case _ => None
            }
        }
      if (segments.isEmpty) None
      else descend(root, segments.toList)
    }

    private def flattenTyped(value: JsonValue): Map[String, String] = {
      val result = mutable.LinkedHashMap.empty[String, String]

      def add(path: Vector[String], text: String): Unit =
        FlatKeys.legacyKey(path).foreach(key => result.update(key, text))

      def visit(path: Vector[String], current: JsonValue): Unit =
        current match {
          case JsonValue.Obj(fields) =>
            fields.foreach { case (key, child) =>
              if (path.isEmpty &&
                  (key == "schema" || key == "config_hash" || key == "flat"))
                ()
              else
                visit(path :+ key, child)
            }
          case JsonValue.Arr(items) =>
            add(path, items.flatMap(valueText).mkString(","))
          case JsonValue.Null => ()
          case other => valueText(other).foreach(text => add(path, text))
        }

      visit(Vector.empty, value)
      result.toMap
    }
  }

  private def valueText(value: JsonValue): Option[String] = value match {
    case JsonValue.Str(text)  => Some(text)
    case JsonValue.Num(text)  => Some(text)
    case JsonValue.Bool(true) => Some("y")
    case JsonValue.Bool(false) => Some("n")
    case _                    => None
  }

  private def valueToText(path: String, value: JsonValue): String =
    valueText(value).getOrElse {
      throw new IllegalArgumentException(
        s"配置项 $path 不是标量，实际为 ${value.getClass.getSimpleName}"
      )
    }

  private def valueToLong(path: String, value: JsonValue): Long = value match {
    case JsonValue.Num(text)  => parseLong(path, text)
    case JsonValue.Str(text)  => parseLong(path, text)
    case JsonValue.Bool(true) => 1L
    case JsonValue.Bool(false) => 0L
    case other =>
      throw new IllegalArgumentException(
        s"配置项 $path 不是整数，实际为 ${other.getClass.getSimpleName}"
      )
  }

  private def valueToBool(path: String, value: JsonValue): Boolean = value match {
    case JsonValue.Bool(flag) => flag
    case JsonValue.Num(text)  => parseLong(path, text) != 0L
    case JsonValue.Str(text) =>
      text.trim.toLowerCase match {
        case "y" | "1" | "true" | "yes" | "on"  => true
        case "n" | "0" | "false" | "no" | "off" | "" => false
        case other =>
          throw new IllegalArgumentException(
            s"配置项 $path 不是布尔值: '$other'"
          )
      }
    case other =>
      throw new IllegalArgumentException(
        s"配置项 $path 不是布尔值，实际为 ${other.getClass.getSimpleName}"
      )
  }

  private def parseLong(path: String, text: String): Long = {
    val trimmed = text.trim
    try {
      if (trimmed.startsWith("0x") || trimmed.startsWith("0X"))
        java.lang.Long.parseLong(trimmed.substring(2), 16)
      else
        java.lang.Long.parseLong(trimmed)
    } catch {
      case _: NumberFormatException =>
        throw new IllegalArgumentException(
          s"配置项 $path 不是整数: '$text'"
        )
    }
  }

  /** typed 路径 -> Kconfig 扁平键。生成器和 Scala 共享这份约定。 */
  private object FlatKeys {
    private def upper(text: String): String = text.toUpperCase
    private def joined(parts: Seq[String]): String =
      parts.map(upper).mkString("_")

    def legacyKey(path: Seq[String]): Option[String] = {
      val parts = path.map(_.toLowerCase)
      if (parts.isEmpty)
        return None
      val tail = parts.tail
      val rest = tail.mkString("_")
      parts.head match {
        case "isa" =>
          tail.mkString(".") match {
            case "xlen"                => Some("XLEN")
            case "soc_xlen"            => Some("SOC_XLEN")
            case "soc_xlen_follow_cpu" => Some("SOC_XLEN_FOLLOW_CPU")
            case "m"                   => Some("RISCV_M")
            case "a"                   => Some("RISCV_A")
            case "c"                   => Some("RISCV_C")
            case "f"                   => Some("RISCV_F")
            case "d"                   => Some("RISCV_D")
            case "s"                   => Some("HAS_S")
            case "u"                   => Some("HAS_U")
            case _                     => Some("ISA_" + joined(tail))
          }
        case "fpu"       => Some("FPU_" + joined(tail))
        case "mdu"       => Some(joined(tail))
        case "frontend"  => Some(joined(tail))
        case "csr"       => Some("CSR_" + joined(tail))
        case "interrupt" =>
          tail.mkString(".") match {
            case "timer_path"      => Some("TIMER_PATH")
            case "s_timer_irq"     => Some("HAS_S_TIMER_IRQ")
            case "s_external_irq"  => Some("HAS_S_EXTERNAL_IRQ")
            case value if value.startsWith("plic.") =>
              Some("PLIC_" + joined(value.stripPrefix("plic.").split('.').toSeq))
            case _ => Some("INTERRUPT_" + joined(tail))
          }
        case "debug" =>
          tail.mkString(".") match {
            case "sdb"                 => Some("SDB")
            case "difftest"            => Some("DIFFTEST")
            case "mtrace"              => Some("MTRACE")
            case "itrace"              => Some("ITRACE")
            case "itrace_write_file"   => Some("ITRACE_WRITE_FILE")
            case "ftrace"              => Some("FTRACE")
            case "iringbuf"            => Some("IRINGBUF")
            case "perf_stats"          => Some("PERF_STATS")
            case "perf_save"           => Some("PERF_SAVE")
            case "watchpoint"          => Some("WATCHPOINT")
            case "watchpoint_num"      => Some("WATCHPOINT_NUM")
            case value if value.startsWith("perf_groups.") =>
              Some("PERF_COUNTER_" +
                upper(value.stripPrefix("perf_groups.")))
            case value if value.startsWith("perf_counters.") =>
              Some("PERF_COUNTER_" +
                upper(value.stripPrefix("perf_counters.")))
            case _ => Some("DEBUG_" + joined(tail))
          }
        case "platform" =>
          tail.mkString(".") match {
            case "ram_size"      => Some("NPC_RAM_SIZE")
            case "cache_padding" => Some("CACHE_PADDING")
            case "chip_link"     => Some("CHIPLINK")
            case "address_width" => Some("ADDRESS_WIDTH")
            case "soc_xlen"      => Some("SOC_XLEN")
            case _               => Some(joined(tail))
          }
        case "boot" => Some(joined(tail))
        case "build" =>
          tail.mkString(".") match {
            case "pdk" => Some("PDK")
            case value if value.startsWith("trace.") =>
              Some("TRACE_" +
                upper(value.stripPrefix("trace.").replace('.', '_')))
            case value if value.startsWith("log.") =>
              Some("LOG_" +
                upper(value.stripPrefix("log.").replace('.', '_')))
            case _ => Some("BUILD_" + joined(tail))
          }
        case "cache" =>
          tail.mkString(".") match {
            case "block_size_log2" => Some("CACHE_BLOCK_SIZE_LOG2")
            case "index_bits"      => Some("CACHE_INDEX_BITS")
            case "wbuf_depth"      => Some("WBUF_DEPTH")
            case "dma_coherence"   => Some("DMA_COHERENCE")
            case value if value == "icache.enabled" => Some("ICACHE")
            case value if value.startsWith("icache.") =>
              Some("ICACHE_" +
                upper(value.stripPrefix("icache.").replace('.', '_')))
            case value if value == "dcache.enabled" => Some("DCACHE")
            case value if value.startsWith("dcache.") =>
              Some("DCACHE_" +
                upper(value.stripPrefix("dcache.").replace('.', '_')))
            case _ => Some("CACHE_" + joined(tail))
          }
        case "mmu" =>
          tail.mkString(".") match {
            case "utlb_lookup_ports" => Some("UTLB_LOOKUP_PORTS")
            case "pmp_enabled"       => Some("HAS_PMP")
            case "pmp_entries"       => Some("PMP_ENTRIES")
            case value if value.startsWith("kconfig.") =>
              Some(joined(value.stripPrefix("kconfig.").split('.').toSeq))
            case _ => Some("MMU_" + joined(tail))
          }
        case _ => None
      }
    }
  }

  private final class Parser(text: String) {
    private var position = 0

    def parse(): JsonValue = {
      skipWhitespace()
      val value = parseValue()
      skipWhitespace()
      if (position != text.length)
        fail("JSON 文档后存在多余内容")
      value match {
        case JsonValue.Obj(_) => value
        case _                => fail("顶层 JSON 必须是对象")
      }
    }

    private def parseValue(): JsonValue = {
      skipWhitespace()
      peek() match {
        case '{' => parseObject()
        case '[' => parseArray()
        case '"' => JsonValue.Str(parseString())
        case 't' => parseLiteral("true", JsonValue.Bool(true))
        case 'f' => parseLiteral("false", JsonValue.Bool(false))
        case 'n' => parseLiteral("null", JsonValue.Null)
        case character if character == '-' || character.isDigit =>
          parseNumber()
        case '\u0000' => fail("JSON 值不完整")
        case other    => fail(s"无法识别的 JSON 值，实际 '$other'")
      }
    }

    private def parseObject(): JsonValue = {
      expect('{')
      skipWhitespace()
      val fields = mutable.LinkedHashMap.empty[String, JsonValue]
      if (peek() == '}') {
        position += 1
      } else {
        var done = false
        while (!done) {
          if (peek() != '"')
            fail("JSON 对象键必须是字符串")
          val key = parseString()
          skipWhitespace()
          expect(':')
          val value = parseValue()
          fields.update(key, value)
          skipWhitespace()
          peek() match {
            case ',' =>
              position += 1
              skipWhitespace()
            case '}' =>
              position += 1
              done = true
            case other =>
              fail(s"期望 ',' 或 '}'，实际 '$other'")
          }
        }
      }
      JsonValue.Obj(fields.toMap)
    }

    private def parseArray(): JsonValue = {
      expect('[')
      skipWhitespace()
      val items = Vector.newBuilder[JsonValue]
      if (peek() == ']') {
        position += 1
      } else {
        var done = false
        while (!done) {
          items += parseValue()
          skipWhitespace()
          peek() match {
            case ',' =>
              position += 1
              skipWhitespace()
            case ']' =>
              position += 1
              done = true
            case other =>
              fail(s"期望 ',' 或 ']'，实际 '$other'")
          }
        }
      }
      JsonValue.Arr(items.result())
    }

    private def parseLiteral(literal: String, value: JsonValue): JsonValue = {
      if (position + literal.length > text.length ||
          text.substring(position, position + literal.length) != literal)
        fail(s"非法 JSON 字面量，期望 '$literal'")
      position += literal.length
      value
    }

    private def parseNumber(): JsonValue = {
      val start = position
      if (peek() == '-')
        position += 1
      if (peek() == '0') {
        position += 1
      } else if (peek().isDigit) {
        while (peek().isDigit)
          position += 1
      } else {
        fail("JSON 数字缺少整数部分")
      }
      if (peek() == '.') {
        position += 1
        if (!peek().isDigit)
          fail("JSON 数字小数部分为空")
        while (peek().isDigit)
          position += 1
      }
      if (peek() == 'e' || peek() == 'E') {
        position += 1
        if (peek() == '+' || peek() == '-')
          position += 1
        if (!peek().isDigit)
          fail("JSON 数字指数部分为空")
        while (peek().isDigit)
          position += 1
      }
      JsonValue.Num(text.substring(start, position))
    }

    private def peek(): Char =
      if (position < text.length) text.charAt(position) else '\u0000'

    private def expect(expected: Char): Unit = {
      if (peek() != expected)
        fail(s"期望 '$expected'")
      position += 1
    }

    private def skipWhitespace(): Unit =
      while (position < text.length &&
             Character.isWhitespace(text.charAt(position))) {
        position += 1
      }

    private def parseString(): String = {
      expect('"')
      val result = new StringBuilder
      while (position < text.length) {
        val character = text.charAt(position)
        position += 1
        if (character == '"') {
          return result.toString
        } else if (character != '\\') {
          result.append(character)
        } else {
          if (position >= text.length)
            fail("转义不完整")
          val escaped = text.charAt(position)
          position += 1
          escaped match {
            case '"'  => result.append('"')
            case '\\' => result.append('\\')
            case '/'  => result.append('/')
            case 'b'  => result.append('\b')
            case 'f'  => result.append('\f')
            case 'n'  => result.append('\n')
            case 'r'  => result.append('\r')
            case 't'  => result.append('\t')
            case 'u'  => appendUnicode(result)
            case other => fail(s"未知转义 '\\$other'")
          }
        }
      }
      fail("JSON 字符串未闭合")
    }

    private def appendUnicode(result: StringBuilder): Unit = {
      val first = readHex4()
      if (Character.isHighSurrogate(first)) {
        if (position + 1 >= text.length ||
            text.charAt(position) != '\\' ||
            text.charAt(position + 1) != 'u') {
          fail("高代理项后缺少 \\u 低代理项")
        }
        position += 2
        val second = readHex4()
        if (!Character.isLowSurrogate(second))
          fail("代理项对不合法")
        result.append(
          new String(Character.toChars(Character.toCodePoint(first, second)))
        )
      } else if (Character.isLowSurrogate(first)) {
        fail("孤立的低代理项")
      } else {
        result.append(first)
      }
    }

    private def readHex4(): Char = {
      if (position + 4 > text.length)
        fail("Unicode 转义不完整")
      var value = 0
      var index = 0
      while (index < 4) {
        val digit = Character.digit(text.charAt(position + index), 16)
        if (digit < 0)
          fail("Unicode 转义包含非法十六进制字符")
        value = (value << 4) | digit
        index += 1
      }
      position += 4
      value.toChar
    }

    private def fail(message: String): Nothing =
      throw new IllegalArgumentException(
        s"JSON 解析失败（位置 $position）：$message"
      )
  }
}
