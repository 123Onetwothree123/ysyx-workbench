import chisel3._
import ysyx_26030103.ysyx_26030103
import java.io.File
import java.nio.file.{Files, Paths, StandardCopyOption}

object ysyx_26030103_Elaborate extends App {
  val targetDir = args(args.indexOf("--target-dir") + 1)

  emitVerilog(new ysyx_26030103, Array("--target-dir", targetDir))
  emitVerilog(new ysyx_26030103(0x80000000L), Array("--target-dir", targetDir))
  Files.move(
    Paths.get(targetDir, "ysyx_26030103.sv"),
    Paths.get(targetDir, "ysyx_26030103_npc.sv"),
    StandardCopyOption.REPLACE_EXISTING)
  emitVerilog(new ysyx_26030103, Array("--target-dir", targetDir))

  emitVerilog(new _root_.ysyx_26030103.riscv32e_npc_AXIRAM, Array("--target-dir", targetDir))

  for (top <- Seq("ysyx_26030103", "ysyx_26030103_npc")) {
    val svFile = s"$targetDir/$top.sv"
    val includePattern = """`include "([^"]*layers-[^"]*\.sv)"""".r
    val rvSV = scala.io.Source.fromFile(svFile)
    val layerFiles =
      try {
        rvSV
          .getLines()
          .flatMap(line => includePattern.findAllMatchIn(line).map(_.group(1)))
          .toSet
      } finally {
        rvSV.close()
      }

    for (file <- layerFiles) {
      val out = new File(targetDir + "/" + file)
      if (!out.exists()) {
        val name = file.stripSuffix(".sv").replace("-", "_")
        val pw = new java.io.PrintWriter(out)
        pw.println(s"`ifndef $name")
        pw.println(s"  `define $name")
        pw.println("`endif")
        pw.close()
      }
    }
  }
}
