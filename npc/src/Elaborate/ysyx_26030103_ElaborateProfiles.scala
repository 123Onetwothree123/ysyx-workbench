import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
object ysyx_26030103_ElaborateProfiles {
  final case class PerfSwitches(
      basic: Boolean,
      opClass: Boolean,
      MDU: Boolean,
      IFUStall: Boolean,
      ICache: Boolean,
      DCache: Boolean,
      EXUStall: Boolean,
      LSU: Boolean,
      IDUStall: Boolean,
      trap: Boolean,
      branchPred: Boolean,
      mmu: Boolean,
      fpu: Boolean,
      AXI: Boolean,
      instMix: Boolean,
      cacheDetail: Boolean,
      interrupt: Boolean,
      WBuf: Boolean,
      CSR: Boolean,
      MDUDetail: Boolean,
      queue: Boolean
  )
  private def groupEnabled(
      config: ysyx_26030103_JsonConfig.Config,
      name: String
  ): Boolean =
    config.bool(
      "debug.dic_arch_counter_groups." +
        name.stripPrefix("PERF_DIC_ARCH_COUNTER_").toLowerCase,
      true
    )
  def perfSwitches(
      config: ysyx_26030103_JsonConfig.Config
  ): PerfSwitches =
    PerfSwitches(
      basic = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_BASIC"),
      opClass = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_OPCLASS"),
      MDU = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_MDU"),
      IFUStall = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_IFU_STALL"),
      ICache = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_ICACHE"),
      DCache = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_DCACHE"),
      EXUStall = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_EXU_STALL"),
      LSU = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_LSU"),
      IDUStall = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_IDU_STALL"),
      trap = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_TRAP"),
      branchPred = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_BRANCH_PRED"),
      mmu = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_MMU"),
      fpu = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_FPU"),
      AXI = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_AXI"),
      instMix = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_INST_MIX"),
      cacheDetail =
        groupEnabled(config, "PERF_DIC_ARCH_COUNTER_CACHE_DETAIL"),
      interrupt = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_INTERRUPT"),
      WBuf = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_WBUF"),
      CSR = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_CSR"),
      MDUDetail = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_MDU_DETAIL"),
      queue = groupEnabled(config, "PERF_DIC_ARCH_COUNTER_QUEUE")
    )
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
      Radix = config.int(
        s"MDU.div.$name.radix",
        config.int("MDU.div_radix", ysyx_26030103_KconfigDefaults.MDUDivRestoringRadix)
      ),
      IterBits = config.int(
        s"MDU.div.$name.iter_bits",
        config.int("MDU.div_iter_bits", ysyx_26030103_KconfigDefaults.MDUDivRestoringIterBits)
      ),
      EarlyOut = config.bool(
        s"MDU.div.$name.early_out",
        config.bool("MDU.div_early_out", false)
      )
    )
  def MDUProfile(
      config: ysyx_26030103_JsonConfig.Config
  ): ysyx_26030103_MDUProfile = {
    // MDU.mul_* 是旧扁平键，MDU.mul.* 是新分层键，两者都要保留。
    val mulRadix = config.int("MDU.mul_radix", ysyx_26030103_KconfigDefaults.MDUMulRadix)
    val mulWidth = config.int("MDU.mul_width", ysyx_26030103_KconfigDefaults.MDUMulWidth)
    val mulEncoding = config.string("MDU.mul_encoding", "plain")
    val mulIterBits = config.int("MDU.mul_iter_bits", ysyx_26030103_KconfigDefaults.MDUMulShiftIterBits)
    val mulSplit = config.int("MDU.mul_split", ysyx_26030103_KconfigDefaults.MDUMulShiftSplit)
    val mulEarlyOut = config.bool("MDU.mul_early_out", false)
    val mulPipeline = config.int("MDU.mul_pipeline", ysyx_26030103_KconfigDefaults.MDUMulPipeline)
    val compressorInputs = config.int("MDU.mul_compressor.inputs", ysyx_26030103_KconfigDefaults.MDUGpcInputs)
    val compressorOutputs = config.int("MDU.mul_compressor.outputs", ysyx_26030103_KconfigDefaults.MDUGpcOutputs)
    val compressorOutputOffsets =
      config.ints(
        "MDU.mul_compressor.output_offsets",
        ysyx_26030103_KconfigDefaults.MDUGpcOutputOffsets
      ).toIndexedSeq
    val compressorHasCin =
      config.bool("MDU.mul_compressor.has_cin", false)
    val compressorHasCout =
      config.bool("MDU.mul_compressor.has_cout", false)
    val compressorCoutOffset =
      config.int("MDU.mul_compressor.cout_offset", ysyx_26030103_KconfigDefaults.MDUGpcCoutOffset)
    val compressorAllowRedundant =
      config.bool("MDU.mul_compressor.allow_redundant", false)
    ysyx_26030103_MDUProfile(
      MUL = ysyx_26030103_MULProfile(
        Width = config.int("MDU.mul.width", mulWidth),
        Encoding = ysyx_26030103_MULEncoding.FromString(
          config.string("MDU.mul.encoding", mulEncoding)
        ),
        Radix = config.int("MDU.mul.radix", mulRadix),
        ShiftAdd = ysyx_26030103_MULShiftAddParams(
          IterBits = config.int(
            "MDU.mul.shift_add.iter_bits",
            mulIterBits
          ),
          Split = config.int("MDU.mul.shift_add.split", mulSplit),
          EarlyOut = config.bool(
            "MDU.mul.shift_add.early_out",
            mulEarlyOut
          )
        ),
        Compression = ysyx_26030103_MULCompressionParams(
          Pipeline = config.int(
            "MDU.mul.compression.pipeline",
            mulPipeline
          ),
          Compressor = ysyx_26030103_MULGPCConfig(
            Inputs = config.int(
              "MDU.mul.compression.compressor.inputs",
              compressorInputs
            ),
            Outputs = config.int(
              "MDU.mul.compression.compressor.outputs",
              compressorOutputs
            ),
            OutputOffsets = config.ints(
              "MDU.mul.compression.compressor.output_offsets",
              compressorOutputOffsets
            ).toIndexedSeq,
            HasCin = config.bool(
              "MDU.mul.compression.compressor.has_cin",
              compressorHasCin
            ),
            HasCout = config.bool(
              "MDU.mul.compression.compressor.has_cout",
              compressorHasCout
            ),
            CoutOffset = config.int(
              "MDU.mul.compression.compressor.cout_offset",
              compressorCoutOffset
            ),
            AllowRedundant = config.bool(
              "MDU.mul.compression.compressor.allow_redundant",
              compressorAllowRedundant
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
