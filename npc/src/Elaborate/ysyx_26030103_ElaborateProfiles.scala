import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
object ysyx_26030103_ElaborateProfiles {
  private def PickInt(
      config: ysyx_26030103_JsonConfig.Config,
      newPath: String,
      flatKey: String,
      default: Int
  ): Int =
    config.int(newPath, config.int(flatKey, default))

  private def PickBool(
      config: ysyx_26030103_JsonConfig.Config,
      newPath: String,
      flatKey: String,
      default: Boolean
  ): Boolean =
    config.bool(newPath, config.bool(flatKey, default))

  private def PickString(
      config: ysyx_26030103_JsonConfig.Config,
      newPath: String,
      flatKey: String,
      default: String
  ): String =
    config.string(newPath, config.string(flatKey, default))

  private def PickInts(
      config: ysyx_26030103_JsonConfig.Config,
      newPath: String,
      flatKey: String,
      default: Seq[Int]
  ): Seq[Int] =
    config.ints(newPath, config.ints(flatKey, default))

  private def groupEnabled(
      config: ysyx_26030103_JsonConfig.Config,
      name: String
  ): Boolean =
    config.bool(
      "debug.dic_arch_counter_groups." + name.toLowerCase,
      true
    )
  def perfSwitches(
      config: ysyx_26030103_JsonConfig.Config
  ): Map[String, Boolean] =
    ysyx_26030103_PerfDicArchCounterCatalog.PerfDicArchCounterGroups.map {
      case (group, _) => group -> groupEnabled(config, group)
    }.toMap
  def perfCounters(
      config: ysyx_26030103_JsonConfig.Config,
      enabled: Boolean
  ): Set[String] =
    ysyx_26030103_PerfDicArchCounterCatalog.AllPerfDicArchCounters.filter {
      name =>
        enabled && config.bool(
          s"debug.dic_arch_counter_events.$name",
          true
        )
    }
  def fpuProfile(
      config: ysyx_26030103_JsonConfig.Config
  ): ysyx_26030103_FPUProfile =
    ysyx_26030103_FPUProfile(
      Pipeline = config.int("fpu.pipeline", ysyx_26030103_KconfigDefaults.FPUPipeline),
      FMA = config.bool("fpu.fma", true),
      DivSqrt = config.bool("fpu.divsqrt", true),
      Rounding = config.bool("fpu.rounding", true),
      Flags = config.bool("fpu.flags", true),
      HardFloat = ysyx_26030103_HardFloatParams(
        Recode = config.bool("fpu.hardfloat.recode", true),
        DetectTininessBeforeRounding =
          config.bool("fpu.hardfloat.tininess_before_rounding", false)
      ),
      Fudian = ysyx_26030103_FudianParams(
        SharedDivSqrt = config.bool("fpu.fudian.shared_divsqrt", true)
      )
    )
  def aluProfile(
      config: ysyx_26030103_JsonConfig.Config
  ): ysyx_26030103_ALUProfile =
    ysyx_26030103_ALUProfile(
      Adder = ysyx_26030103_ALUAdderImpl.FromString(
        config.string("alu.adder_impl", "native")
      ),
      AdderParams = ysyx_26030103_ALUAdderParams(
        CarrySelectGroup = config.int("alu.carry_select_group", ysyx_26030103_KconfigDefaults.ALUCarrySelectGroup)
      ),
      Elau = ysyx_26030103_ElauParams(
        Speed = ysyx_26030103_ElauSpeed.FromString(
          config.string("alu.elau.speed", "fast")
        )
      )
    )
  private def divParams(
      config: ysyx_26030103_JsonConfig.Config,
      name: String
  ): ysyx_26030103_DIVParams =
    ysyx_26030103_DIVParams(
      Radix = PickInt(
        config,
        s"MDU.div.$name.radix",
        "MDU.div_radix",
        ysyx_26030103_KconfigDefaults.MDUDivRestoringRadix
      ),
      IterBits = PickInt(
        config,
        s"MDU.div.$name.iter_bits",
        "MDU.div_iter_bits",
        ysyx_26030103_KconfigDefaults.MDUDivRestoringIterBits
      ),
      EarlyOut = PickBool(
        config,
        s"MDU.div.$name.early_out",
        "MDU.div_early_out",
        false
      )
    )
  def MDUProfile(
      config: ysyx_26030103_JsonConfig.Config
  ): ysyx_26030103_MDUProfile = {
    // MDU.mul_* 是旧扁平键，MDU.mul.* 是新分层键，两者都要保留。
    ysyx_26030103_MDUProfile(
      MUL = ysyx_26030103_MULProfile(
        Width = PickInt(
          config,
          "MDU.mul.width",
          "MDU.mul_width",
          ysyx_26030103_KconfigDefaults.MDUMulWidth
        ),
        Encoding = ysyx_26030103_MULEncoding.FromString(
          PickString(config, "MDU.mul.encoding", "MDU.mul_encoding", "plain")
        ),
        Radix = PickInt(
          config,
          "MDU.mul.radix",
          "MDU.mul_radix",
          ysyx_26030103_KconfigDefaults.MDUMulRadix
        ),
        ShiftAdd = ysyx_26030103_MULShiftAddParams(
          IterBits = PickInt(
            config,
            "MDU.mul.shift_add.iter_bits",
            "MDU.mul_iter_bits",
            ysyx_26030103_KconfigDefaults.MDUMulShiftIterBits
          ),
          Split = PickInt(
            config,
            "MDU.mul.shift_add.split",
            "MDU.mul_split",
            ysyx_26030103_KconfigDefaults.MDUMulShiftSplit
          ),
          EarlyOut = PickBool(
            config,
            "MDU.mul.shift_add.early_out",
            "MDU.mul_early_out",
            false
          )
        ),
        Compression = ysyx_26030103_MULCompressionParams(
          Pipeline = PickInt(
            config,
            "MDU.mul.compression.pipeline",
            "MDU.mul_pipeline",
            ysyx_26030103_KconfigDefaults.MDUMulPipeline
          ),
          Compressor = ysyx_26030103_MULGPCConfig(
            Inputs = PickInt(
              config,
              "MDU.mul.compression.compressor.inputs",
              "MDU.mul_compressor.inputs",
              ysyx_26030103_KconfigDefaults.MDUGpcInputs
            ),
            Outputs = PickInt(
              config,
              "MDU.mul.compression.compressor.outputs",
              "MDU.mul_compressor.outputs",
              ysyx_26030103_KconfigDefaults.MDUGpcOutputs
            ),
            OutputOffsets = PickInts(
              config,
              "MDU.mul.compression.compressor.output_offsets",
              "MDU.mul_compressor.output_offsets",
              ysyx_26030103_KconfigDefaults.MDUGpcOutputOffsets
            ).toIndexedSeq,
            HasCin = PickBool(
              config,
              "MDU.mul.compression.compressor.has_cin",
              "MDU.mul_compressor.has_cin",
              false
            ),
            HasCout = PickBool(
              config,
              "MDU.mul.compression.compressor.has_cout",
              "MDU.mul_compressor.has_cout",
              false
            ),
            CoutOffset = PickInt(
              config,
              "MDU.mul.compression.compressor.cout_offset",
              "MDU.mul_compressor.cout_offset",
              ysyx_26030103_KconfigDefaults.MDUGpcCoutOffset
            ),
            AllowRedundant = PickBool(
              config,
              "MDU.mul.compression.compressor.allow_redundant",
              "MDU.mul_compressor.allow_redundant",
              false
            )
          )
        )
      ),
      DIV = ysyx_26030103_DIVProfile(
        Restoring = divParams(config, "restoring"),
        NonRestoring = divParams(config, "nonrestoring"),
        SRT = divParams(config, "srt")
      ),
      Rocket = ysyx_26030103_RocketMDUParams(
        MULUnroll = config.int("MDU.rocket.mul_unroll", ysyx_26030103_KconfigDefaults.MDURocketMulUnroll),
        DIVUnroll = config.int("MDU.rocket.div_unroll", ysyx_26030103_KconfigDefaults.MDURocketDivUnroll),
        MULEarlyOut = config.bool("MDU.rocket.mul_early_out", false),
        DIVEarlyOut = config.bool("MDU.rocket.div_early_out", false),
        DIVEarlyOutGranularity =
          config.int("MDU.rocket.div_early_out_granularity", ysyx_26030103_KconfigDefaults.MDURocketDivEarlyOutGranularity)
      )
    )
  }
  def MMUProfile(
      config: ysyx_26030103_JsonConfig.Config,
      xlen: Int
  ): ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile.fromEnv(key => config.flat.get(key), xlen)
}
