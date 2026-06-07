import chisel3._
import RV32I.RV32I

object Elaborate extends App {
  val targetDir = args(args.indexOf("--target-dir") + 1)
  emitVerilog(new RV32I, Array("--target-dir", targetDir))
  // firtool 生成了 include 但没拆出文件，补一个空壳
  val pw = new java.io.PrintWriter(targetDir + "/layers-RV32I-Verification.sv")
  pw.println("`ifndef layers_RV32I_Verification")
  pw.println("  `define layers_RV32I_Verification")
  pw.println("`endif")
  pw.close()
}
