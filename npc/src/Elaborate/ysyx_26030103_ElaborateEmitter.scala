import chisel3._
import _root_.ysyx_26030103.ysyx_26030103
import java.io.File
import java.nio.file.{Files, Paths, StandardCopyOption}
object ysyx_26030103_ElaborateEmitter {
  private val YosysFirtoolOptions: firrtl.AnnotationSeq = Seq(
    circt.stage.FirtoolOption(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays"
    ),
    chisel3.stage.UseSRAMBlackbox
  )
  def emit(
      settings: ysyx_26030103_ElaborateSettings,
      targetDir: String
  ): Unit = {
    val config = settings.config
    val plicBase = config.MMU.plicBase
    val plicSize = config.MMU.plicSize
    emitVerilog(
      new ysyx_26030103(
        config.copy(
          ResetAddr = 0x80000000L + settings.cachePadding,
          CacheableBase = 0x80000000L,
          CacheableMask = 0x80000000L,
          PMARegions = settings.pma.npcRegions(
            settings.npcRamSize,
            plicBase,
            plicSize
          )
        )
      ),
      Array("--target-dir", targetDir),
      YosysFirtoolOptions
    )
    Files.move(
      Paths.get(targetDir, "ysyx_26030103.sv"),
      Paths.get(targetDir, "ysyx_26030103_npc.sv"),
      StandardCopyOption.REPLACE_EXISTING
    )
    emitVerilog(
      new ysyx_26030103(
        config.copy(
          ResetAddr = settings.pma.socResetAddr,
          CacheableBase = 0x00000000L,
          CacheableMask = 0x00000000L,
          PMARegions = settings.pma.socRegions(
            settings.hasChipLink,
            plicBase,
            plicSize
          )
        )
      ),
      Array("--target-dir", targetDir),
      YosysFirtoolOptions
    )
    emitVerilog(
      new _root_.ysyx_26030103.riscv32e_npc_AXIRAM(
        settings.npcRamSize,
        uartBase = settings.pma.uartBase
      ),
      Array("--target-dir", targetDir),
      YosysFirtoolOptions
    )
    writeLayerStubs(targetDir)
  }
  private def writeLayerStubs(targetDir: String): Unit = {
    val includePattern = """`include "([^"]*layers-[^"]*\.sv)"""".r
    for (top <- Seq(
           "ysyx_26030103",
           "ysyx_26030103_npc",
           "riscv32e_npc_AXIRAM"
         )) {
      val svFile = s"$targetDir/$top.sv"
      val source = scala.io.Source.fromFile(svFile)
      val layerFiles =
        try {
          source
            .getLines()
            .flatMap(line =>
              includePattern.findAllMatchIn(line).map(_.group(1))
            )
            .toSet
        } finally {
          source.close()
        }
      for (file <- layerFiles) {
        val out = new File(targetDir + "/" + file)
        if (!out.exists()) {
          val name = file.stripSuffix(".sv").replace("-", "_")
          val writer = new java.io.PrintWriter(out)
          writer.println(s"`ifndef $name")
          writer.println(s"  `define $name")
          writer.println("`endif")
          writer.close()
        }
      }
    }
  }
}
