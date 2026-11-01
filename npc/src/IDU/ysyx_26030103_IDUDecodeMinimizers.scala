package ysyx_26030103.idu

import chisel3.util.BitPat
import chisel3.util.experimental.decode._
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.jdk.CollectionConverters._

/** 不做逻辑最小化；只把行输出转换成 decoder/pla 期望的默认值编码。
  *
  * 对默认值为 1 的输出位，pla 会用 default.value 作为 invert mask，
  * 因此行里必须存放 0-set（对实际输出取反），否则语义会翻转。
  */
object ysyx_26030103_IdentityMinimizer extends Minimizer {
  def minimize(table: TruthTable): TruthTable = {
    val rows = table.table.map { case (input, output) =>
      val width = output.getWidth
      val encoded = output.rawString.zipWithIndex.map { case (bit, pos) =>
        val index = width - 1 - pos
        val defaultOne =
          table.default.mask.testBit(index) &&
            table.default.value.testBit(index)
        if (defaultOne) {
          bit match {
            case '0' => '1'
            case '1' => '0'
            case c   => c
          }
        } else {
          bit
        }
      }.mkString
      input -> BitPat("b" + encoded)
    }
    TruthTable(rows, table.default)
  }
}

/** 用外部 ABC 的 bdd/dc2 引擎做两级覆盖提取。
  *
  * 该 Minimizer 只负责 elaboration 阶段的真值表转换，不改变指令语义。
  * 选择该后端时要求 PATH 中存在 `abc`。
  */
sealed abstract class ysyx_26030103_AbcMinimizer(
    private val command: String
) extends Minimizer {
  def minimize(table: TruthTable): TruthTable = {
    val parts = ysyx_26030103_TruthTableUtil.split(table)
    ysyx_26030103_TruthTableUtil.merge(parts.map { case (part, indexes) =>
      (minimizeUniform(part), indexes)
    })
  }

  private def minimizeUniform(table: TruthTable): TruthTable = {
    val dir = Files.createTempDirectory("npc-idu-abc-")
    val in = dir.resolve("in.pla")
    val out = dir.resolve("out.pla")
    try {
      Files.writeString(in, toPla(table), StandardCharsets.UTF_8)
      val script =
        s"read_pla ${in.toAbsolutePath}; $command; collapse; " +
          s"write_pla ${out.toAbsolutePath}; quit"
      val process = new ProcessBuilder(Seq("abc", "-c", script).asJava)
        .redirectErrorStream(true)
        .start()
      val log = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val rc = process.waitFor()
      if (rc != 0 || !Files.exists(out)) {
        throw new RuntimeException(
          s"IDU_DECODE_MINIMIZER=$command 失败（abc rc=$rc）：$log"
        )
      }
      fromPla(Files.readString(out, StandardCharsets.UTF_8), table.default)
    } finally {
      Files.deleteIfExists(in)
      Files.deleteIfExists(out)
      Files.deleteIfExists(dir)
    }
  }

  private def toPla(table: TruthTable): String = {
    val defaultType = table.default.rawString.toCharArray.distinct
    require(defaultType.length == 1,
      "ABC minimizer 只接受单一默认输出类型")
    val dt = defaultType.head
    val tableType = if (dt == '?') "fr" else "fd"
    val rows = table.table.map { case (input, output) =>
      val in = input.rawString.replace('?', '-')
      val rawOut = output.rawString.replace('?', '-')
      val out =
        if (dt == '1') rawOut.map {
          case '0' => '1'
          case '1' => '0'
          case c   => c
        }
        else rawOut
      s"$in $out"
    }
    (Seq(
      s".i ${table.inputWidth}",
      s".o ${table.outputWidth}",
      s".type $tableType"
    ) ++ rows ++ Seq(".e")).mkString("\n")
  }

  private def fromPla(text: String, default: BitPat): TruthTable = {
    val rows = text.linesIterator
      .map(_.trim)
      .filter(line =>
        line.nonEmpty && !line.startsWith(".") && !line.startsWith("#"))
      .flatMap { line =>
        val fields = line.split("\\s+")
        if (fields.length >= 2) {
          Some(
            BitPat("b" + fields(0).replace('-', '?')) ->
              BitPat("b" + fields(1).replace('-', '?'))
          )
        } else {
          None
        }
      }
      .toSeq
    require(rows.nonEmpty, "ABC minimizer 返回了空 PLA")
    TruthTable.fromEspressoOutput(rows, default)
  }
}

object ysyx_26030103_AbcBddMinimizer extends ysyx_26030103_AbcMinimizer("bdd")

object ysyx_26030103_AbcSatMinimizer extends ysyx_26030103_AbcMinimizer(
  "strash; dc2"
)

private object ysyx_26030103_TruthTableUtil {
  def split(table: TruthTable): Seq[(TruthTable, Seq[Int])] = {
    def bpFilter(bitPat: BitPat, indexes: Seq[Int]): BitPat =
      BitPat(
        "b" + bitPat.rawString.zipWithIndex
          .filter(b => indexes.contains(b._2))
          .map(_._1)
          .mkString
      )

    def tableFilter(indexes: Seq[Int]): Option[(TruthTable, Seq[Int])] =
      if (indexes.nonEmpty) {
        Some(
          (
            TruthTable(
              table.table.map { case (in, out) => in -> bpFilter(out, indexes) },
              bpFilter(table.default, indexes)
            ),
            indexes
          )
        )
      } else {
        None
      }

    def index(bitPat: BitPat, bpType: Char): Seq[Int] =
      bitPat.rawString.zipWithIndex
        .filter(_._1 == bpType)
        .map(_._2)

    val allDontCares = table.default.rawString.forall(_ == '?')
    val allZeros = table.default.rawString.forall(_ == '0')
    val allOnes = table.default.rawString.forall(_ == '1')
    if (!(allDontCares || allZeros || allOnes)) {
      Seq('1', '0', '?').flatMap(t => tableFilter(index(table.default, t)))
    } else {
      Seq(table -> (0 until table.default.width))
    }
  }

  def merge(tables: Seq[(TruthTable, Seq[Int])]): TruthTable = {
    def reIndex(
        bitPat: BitPat,
        table: TruthTable,
        indexes: Seq[Int]
    ): Seq[(Char, Int)] = {
      table.table
        .map(a => a._1.toString -> a._2)
        .collectFirst { case (k, v) if k == bitPat.toString => v }
        .getOrElse(BitPat("b" + ("?" * indexes.size)))
        .rawString
        .zip(indexes)
    }

    def bitPat(indexedChar: Seq[(Char, Int)]): BitPat =
      BitPat(
        "b" + indexedChar.sortBy(_._2).map(_._1).mkString
      )

    if (tables.size > 1) {
      TruthTable(
        tables
          .flatMap(_._1.table.map(_._1))
          .map { key =>
            key -> bitPat(tables.flatMap { case (table, indexes) =>
              reIndex(key, table, indexes)
            })
          },
        bitPat(tables.flatMap { case (table, indexes) =>
          table.default.rawString.zip(indexes)
        })
      )
    } else {
      tables.head._1
    }
  }
}
