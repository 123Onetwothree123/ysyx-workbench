import chisel3._
import ysyx_26030103.ysyx_26030103
import java.io.File
import java.nio.file.{Files, Paths, StandardCopyOption}

object ysyx_26030103_Elaborate extends App {
  val targetDir = args(args.indexOf("--target-dir") + 1)

  val BlockSizeLog2 = sys.env.getOrElse("CACHE_BLOCK_SIZE_LOG2", "4").toInt
  val IndexBits     = sys.env.getOrElse("CACHE_INDEX_BITS", "5").toInt
  val BTBBits       = sys.env.getOrElse("BTB_BITS", "4").toInt
  val BTBWays       = sys.env.getOrElse("BTB_WAYS", "1").toInt
  val JalBTBBits    = sys.env.getOrElse("JAL_BTB_BITS", "4").toInt
  val JalBTBWays    = sys.env.getOrElse("JAL_BTB_WAYS", "1").toInt
  val RASBits       = sys.env.getOrElse("RAS_BITS", "4").toInt

  val CacheableBase_ysyxsoc = 0x00000000L
  val CacheableMask_ysyxsoc = 0x00000000L
  val CacheableBase_npc     = 0x80000000L
  val CacheableMask_npc     = 0x80000000L

  // 让 firtool 直接输出 yosys 能读的语法:
  //   disallowLocalVariables  禁止 always 块内声明变量(消除 automatic logic 声明+初始化)
  //   disallowPackedArrays    打散多维 packed 数组(消除 [3:0][7:0] 和 '{...} 赋值模式)
  val yosysFirtoolOpts: firrtl.AnnotationSeq = Seq(
    circt.stage.FirtoolOption("--lowering-options=disallowLocalVariables,disallowPackedArrays")
  )

  emitVerilog(new ysyx_26030103(
    resetAddr      = 0x30000000L,
    BlockSizeLog2  = BlockSizeLog2,
    IndexBits      = IndexBits,
    BTBBits        = BTBBits,
    BTBWays        = BTBWays,
    JalBTBBits     = JalBTBBits,
    JalBTBWays     = JalBTBWays,
    RASBits        = RASBits,
    CacheableBase  = CacheableBase_ysyxsoc,
    CacheableMask  = CacheableMask_ysyxsoc
  ), Array("--target-dir", targetDir), yosysFirtoolOpts)
  emitVerilog(new ysyx_26030103(
    resetAddr      = 0x80000000L,
    BlockSizeLog2  = BlockSizeLog2,
    IndexBits      = IndexBits,
    BTBBits        = BTBBits,
    BTBWays        = BTBWays,
    JalBTBBits     = JalBTBBits,
    JalBTBWays     = JalBTBWays,
    RASBits        = RASBits,
    CacheableBase  = CacheableBase_npc,
    CacheableMask  = CacheableMask_npc
  ), Array("--target-dir", targetDir), yosysFirtoolOpts)
  Files.move(
    Paths.get(targetDir, "ysyx_26030103.sv"),
    Paths.get(targetDir, "ysyx_26030103_npc.sv"),
      StandardCopyOption.REPLACE_EXISTING)
  emitVerilog(new ysyx_26030103(
    resetAddr      = 0x30000000L,
    BlockSizeLog2  = BlockSizeLog2,
    IndexBits      = IndexBits,
    BTBBits        = BTBBits,
    BTBWays        = BTBWays,
    JalBTBBits     = JalBTBBits,
    JalBTBWays     = JalBTBWays,
    RASBits        = RASBits,
    CacheableBase  = CacheableBase_ysyxsoc,
    CacheableMask  = CacheableMask_ysyxsoc
  ), Array("--target-dir", targetDir), yosysFirtoolOpts)

  emitVerilog(new _root_.ysyx_26030103.riscv32e_npc_AXIRAM, Array("--target-dir", targetDir), yosysFirtoolOpts)

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
