import chisel3._
import circt.stage.ChiselStage
import _root_.ysyx_26030103.ysyx_26030103
import _root_.ysyx_26030103.config.ysyx_26030103_NPCConfig
import java.io.File
import java.nio.file.{Files, Paths, StandardCopyOption}
object ysyx_26030103_ElaborateEmitter {
  private val yosysFirtoolOptions: firrtl.AnnotationSeq = Seq(
    circt.stage.FirtoolOption(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays"
    ),
    chisel3.stage.UseSRAMBlackbox
  )
  def emit(
    settings:  ysyx_26030103_ElaborateSettings,
    targetDir: String
  ): Unit = {
    val config   = settings.config
    val PLICBase = config.MMU.PLICBase
    val PLICSize = config.MMU.PLICSize
    emitVerilog(
      new ysyx_26030103(
        config.copy(
          ResetAddress = settings.PMA.NpcRAMBase + settings.cachePadding,
          PMARegions = settings.PMA.npcRegions(
            settings.NpcRAMSize,
            PLICBase,
            PLICSize
          )
        )
      ),
      Array("--target-dir", targetDir),
      yosysFirtoolOptions
    )
    Files.move(
      Paths.get(targetDir, "ysyx_26030103.sv"),
      Paths.get(targetDir, "ysyx_26030103_npc.sv"),
      StandardCopyOption.REPLACE_EXISTING
    )
    emitVerilog(
      new ysyx_26030103(
        config.copy(
          ResetAddress = settings.PMA.SOCResetAddress,
          PMARegions = settings.PMA.socRegions(
            settings.hasChipLink,
            PLICBase,
            PLICSize
          )
        )
      ),
      Array("--target-dir", targetDir),
      yosysFirtoolOptions
    )
    emitVerilog(
      new _root_.ysyx_26030103.riscv32e_npc_AXIRAM(
        settings.NpcRAMSize,
        settings.config.EffectiveSocXLEN,
        UARTBase = settings.PMA.UARTBase,
        RAMBase = settings.PMA.NpcRAMBase
      ),
      Array("--target-dir", targetDir),
      yosysFirtoolOptions
    )
    // direct-NPC的Verilator顶层也由Chisel生成；该文件包含CPU与
    // AXIRAM的完整层次，Verilator只应再包含这一个文件
    emitVerilog(
      new _root_.ysyx_26030103.Riscv32eNpcSimTop(
        directSimTopConfig(settings),
        SOCXLEN = settings.config.EffectiveSocXLEN,
        RAMBytes = settings.NpcRAMSize,
        UARTBase = settings.PMA.UARTBase,
        RAMBase = settings.PMA.NpcRAMBase
      ),
      Array("--target-dir", targetDir),
      yosysFirtoolOptions
    )
    writeLayerStubs(targetDir)
  }
  private def directNpcConfig(
    settings: ysyx_26030103_ElaborateSettings
  ): ysyx_26030103_NPCConfig = {
    val config   = settings.config
    val PLICBase = config.MMU.PLICBase
    val PLICSize = config.MMU.PLICSize
    config.copy(
      ResetAddress = settings.PMA.NpcRAMBase + settings.cachePadding,
      PMARegions = settings.PMA.npcRegions(
        settings.NpcRAMSize,
        PLICBase,
        PLICSize
      )
    )
  }
  // SimTop的CPU侧AXI宽度跟随config.EffectiveSocXLEN，AXIRAM也使用同一
  // 宽度，32/64都由SimTop直接同宽连接
  private def directSimTopConfig(
    settings: ysyx_26030103_ElaborateSettings
  ): ysyx_26030103_NPCConfig =
    directNpcConfig(settings)
  // GSIM直接消费CHIRRTL；Verilator和GSIM共用同一个direct-NPC
  // SimTop，避免两套顶层连接逐渐分叉
  def emitCHIRRTL(
    settings:  ysyx_26030103_ElaborateSettings,
    targetDir: String
  ): Unit = {
    ChiselStage.emitCHIRRTLFile(
      new _root_.ysyx_26030103.Riscv32eNpcSimTop(
        directSimTopConfig(settings),
        SOCXLEN = settings.config.EffectiveSocXLEN,
        RAMBytes = settings.NpcRAMSize,
        UARTBase = settings.PMA.UARTBase,
        RAMBase = settings.PMA.NpcRAMBase
      ),
      Array("--target-dir", targetDir)
    )
  }

  private def writeLayerStubs(targetDir: String): Unit = {
    val includePattern = """`include "([^"]*layers-[^"]*\.sv)"""".r
    for (
      top <- Seq(
               "ysyx_26030103",
               "ysyx_26030103_npc",
               "riscv32e_npc_AXIRAM",
               "riscv32e_npc_SimTop"
             )
    ) {
      val svFile     = s"$targetDir/$top.sv"
      val source     = scala.io.Source.fromFile(svFile)
      val layerFiles =
        try {
          source
            .getLines()
            .flatMap(line => includePattern.findAllMatchIn(line).map(_.group(1)))
            .toSet
        } finally {
          source.close()
        }
      for (file <- layerFiles) {
        val out = new File(targetDir + "/" + file)
        if (!out.exists()) {
          val name   = file.stripSuffix(".sv").replace("-", "_")
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
