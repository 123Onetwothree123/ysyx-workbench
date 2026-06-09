import chisel3._
import RV32I.RV32I

object Elaborate extends App {
  val targetDir = args(args.indexOf("--target-dir") + 1)
  emitVerilog(new RV32I, Array("--target-dir", targetDir))

  // firtool会在RV32I.sv里生成一些`include "layers-*.sv"`，但emitVerilog有时不会把这些文件拆出来。
  // Verilator看到include却找不到文件就会报错，所以这里给缺失的layers文件补空壳。
  val rv32iSV = scala.io.Source.fromFile(targetDir + "/RV32I.sv")
  val includePattern = """`include "([^"]*layers-[^"]*\.sv)"""".r
  val layerFiles =
    try {
      rv32iSV
        .getLines()
        .flatMap(line => includePattern.findAllMatchIn(line).map(_.group(1)))
        .toSet
    } finally {
      rv32iSV.close()
    }

  for (file <- layerFiles) {
    val out = new java.io.File(targetDir + "/" + file)
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
