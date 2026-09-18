import chisel3._
import ysyx_26030103.ysyx_26030103
import _root_.ysyx_26030103.common.ysyx_26030103_NPCConfig
import _root_.ysyx_26030103.common.ysyx_26030103_MulImpl
import java.io.File
import java.nio.file.{Files, Paths, StandardCopyOption}

object ysyx_26030103_Elaborate extends App {
  val targetDir = args(args.indexOf("--target-dir") + 1)

  val BlockSizeLog2 = sys.env.getOrElse("CACHE_BLOCK_SIZE_LOG2", "4").toInt
  val IndexBits = sys.env.getOrElse("CACHE_INDEX_BITS", "5").toInt
  val BTBBits = sys.env.getOrElse("BTB_BITS", "4").toInt
  val BTBWays = sys.env.getOrElse("BTB_WAYS", "1").toInt
  val JalBTBBits = sys.env.getOrElse("JAL_BTB_BITS", "4").toInt
  val JalBTBWays = sys.env.getOrElse("JAL_BTB_WAYS", "1").toInt
  val RASBits = sys.env.getOrElse("RAS_BITS", "4").toInt
  // ICache 对齐填充：npc 独立仿真的复位地址 = 0x80000000 + CACHE_PADDING
  // 与 Makefile 的 NPC_RESET_PC、program.hex 头部填充保持一致
  val CachePadding = sys.env.getOrElse("CACHE_PADDING", "0").toInt
  val CacheableBase_ysyxsoc = 0x00000000L
  val CacheableMask_ysyxsoc = 0x00000000L
  val CacheableBase_npc = 0x80000000L
  val CacheableMask_npc = 0x80000000L
  // 用kconfig来指挥的指令集扩展开关，然后没有实现的部分就直接让NPCConfig的require判断为失败的条件就可以了
  val UseM = sys.env.getOrElse("RV32_M", "n") == "y"
  val UseA = sys.env.getOrElse("RV32_A", "n") == "y"
  val UseC = sys.env.getOrElse("RV32_C", "n") == "y"
  val MulImplName =
    sys.env.get("MUL_IMPL").filter(_.nonEmpty).getOrElse("shift_add")
  val config = ysyx_26030103_NPCConfig(
    UseM = UseM,
    UseA = UseA,
    UseC = UseC,
    MulImpl = ysyx_26030103_MulImpl.FromString(MulImplName),
    BlockSizeLog2 = BlockSizeLog2,
    IndexBits = IndexBits,
    BTBBits = BTBBits,
    BTBWays = BTBWays,
    JalBTBBits = JalBTBBits,
    JalBTBWays = JalBTBWays,
    RASBits = RASBits
  )
  println(s"[NPC config] ${config.Describe}")

  // 让 firtool 直接输出 yosys 能读的语法:
  //   disallowLocalVariables  禁止 always 块内声明变量(消除 automatic logic 声明+初始化)
  //   disallowPackedArrays    打散多维 packed 数组(消除 [3:0][7:0] 和 '{...} 赋值模式)
  val yosysFirtoolOpts: firrtl.AnnotationSeq = Seq(
    circt.stage.FirtoolOption(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays"
    )
  )

  emitVerilog(
    new ysyx_26030103(
      config.copy(
        ResetAddr = 0x80000000L + CachePadding,
        CacheableBase = CacheableBase_npc,
        CacheableMask = CacheableMask_npc
      )
    ),
    Array("--target-dir", targetDir),
    yosysFirtoolOpts
  )
  Files.move(
    Paths.get(targetDir, "ysyx_26030103.sv"),
    Paths.get(targetDir, "ysyx_26030103_npc.sv"),
    StandardCopyOption.REPLACE_EXISTING
  )
  emitVerilog(
    new ysyx_26030103(
      config.copy(
        ResetAddr = 0x30000000L,
        CacheableBase = CacheableBase_ysyxsoc,
        CacheableMask = CacheableMask_ysyxsoc
      )
    ),
    Array("--target-dir", targetDir),
    yosysFirtoolOpts
  )

  emitVerilog(
    new _root_.ysyx_26030103.riscv32e_npc_AXIRAM,
    Array("--target-dir", targetDir),
    yosysFirtoolOpts
  )

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
