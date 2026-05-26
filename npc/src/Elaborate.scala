import chisel3._
import RV32I.RV32I
//拿来给Makefile当入口类的
object Elaborate extends App {
  val targetDir = args(args.indexOf("--target-dir") + 1)
  chisel3.emitVerilog(new RV32I, Array("--target-dir", targetDir))
}
