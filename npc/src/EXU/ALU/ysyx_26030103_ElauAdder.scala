package ysyx_26030103.exu

import chisel3._
import chisel3.util.HasBlackBoxPath
import _root_.ysyx_26030103.config._

/** PULP ELAU AddC 的 Chisel 边界。
  *
  * 上游是 SystemVerilog 的 `AddC`：`{CO,S}=A+B+CI`，支持 width 和
  * `SLOW/MEDIUM/FAST` 三种 prefix 结构。这里只保留整数 speed 参数，
  * 避免把 `lau_pkg::speed_e` 暴露给 Chisel/Kconfig。
  */
class ysyx_26030103_ElauAdder(
    val width: Int,
    val speed: ysyx_26030103_ElauSpeed = ysyx_26030103_ElauSpeed.Fast
) extends BlackBox(Map(
  "WIDTH" -> IntParam(width),
  "SPEED" -> IntParam(speed.Code)
)) with HasBlackBoxPath {
  require(width == 32 || width == 64, s"ELAU AddC width 非法: $width")

  val io = IO(new Bundle {
    val A = Input(UInt(width.W))
    val B = Input(UInt(width.W))
    val CI = Input(Bool())
    val S = Output(UInt(width.W))
    val CO = Output(Bool())
  })

  // addPath 让 firtool 把 vendor 源码纳入 elaboration 输出；因此
  // Makefile 不需要再单独登记一个可能与生成文件重复的源文件。
  addPath(ysyx_26030103_ElauAdder.SourcePath)
}

object ysyx_26030103_ElauAdder {
  /** 从 mill 的 npc/ 工作目录、测试 sandbox 或仓库根目录都能定位
    * vendor 源文件。测试 sandbox 位于 out/test/.../sandbox，不能依赖
    * 固定相对层级，因此向上查找包含 vendor/elau 的祖先目录。
    */
  lazy val SourcePath: String = {
    var current = new java.io.File(".").getAbsoluteFile
    var found: Option[java.io.File] = None
    while (current != null && found.isEmpty) {
      val candidate = new java.io.File(current, "vendor/elau/elau_addc.sv")
      if (candidate.isFile) {
        found = Some(candidate)
      } else {
        current = current.getParentFile
      }
    }
    found.map(_.getAbsolutePath).getOrElse {
      throw new java.io.FileNotFoundException(
        "找不到 vendor/elau/elau_addc.sv；请从 NPC 仓库根目录或 npc/ 运行"
      )
    }
  }
}
