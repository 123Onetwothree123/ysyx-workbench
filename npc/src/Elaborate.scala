import chisel3._
import RV32I.RV32I

object Elaborate extends App {
  val targetDir = args(args.indexOf("--target-dir") + 1)
  emitVerilog(new RV32I, Array("--target-dir", targetDir, "--disable-verify"))
}
