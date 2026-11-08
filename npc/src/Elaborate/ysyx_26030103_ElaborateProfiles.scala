import _root_.ysyx_26030103.config._
object ysyx_26030103_ElaborateProfiles {
  final case class PerfSwitches(
      basic: Boolean,
      opClass: Boolean,
      mdu: Boolean,
      ifuStall: Boolean,
      icache: Boolean,
      dcache: Boolean,
      exuStall: Boolean,
      lsu: Boolean,
      iduStall: Boolean,
      trap: Boolean,
      branchPred: Boolean,
      mmu: Boolean,
      fpu: Boolean,
      axi: Boolean,
      instMix: Boolean,
      cacheDetail: Boolean,
      interrupt: Boolean,
      wbuf: Boolean,
      csr: Boolean,
      mduDetail: Boolean,
      queue: Boolean
  )
  private def groupEnabled(
      cfg: ysyx_26030103_JsonConfig.Config,
      name: String
  ): Boolean =
    cfg.bool(
      "debug.dic_arch_counter_groups." +
        name.stripPrefix("PERF_DIC_ARCH_COUNTER_").toLowerCase,
      true
    )
  def perfSwitches(
      cfg: ysyx_26030103_JsonConfig.Config
  ): PerfSwitches =
    PerfSwitches(
      basic = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_BASIC"),
      opClass = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_OPCLASS"),
      mdu = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_MDU"),
      ifuStall = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_IFU_STALL"),
      icache = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_ICACHE"),
      dcache = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_DCACHE"),
      exuStall = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_EXU_STALL"),
      lsu = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_LSU"),
      iduStall = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_IDU_STALL"),
      trap = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_TRAP"),
      branchPred = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_BRANCH_PRED"),
      mmu = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_MMU"),
      fpu = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_FPU"),
      axi = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_AXI"),
      instMix = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_INST_MIX"),
      cacheDetail =
        groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_CACHE_DETAIL"),
      interrupt = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_INTERRUPT"),
      wbuf = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_WBUF"),
      csr = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_CSR"),
      mduDetail = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_MDU_DETAIL"),
      queue = groupEnabled(cfg, "PERF_DIC_ARCH_COUNTER_QUEUE")
    )
  def perfCounters(
      cfg: ysyx_26030103_JsonConfig.Config,
      enabled: Boolean
  ): Set[String] =
    ysyx_26030103_PerfDicArchCounterCatalog.AllPerfDicArchCounters.filter {
      name =>
        enabled && cfg.bool(
          s"debug.dic_arch_counter_events.$name",
          true
        )
    }
  def fpuProfile(
      cfg: ysyx_26030103_JsonConfig.Config
  ): ysyx_26030103_FPUProfile =
    ysyx_26030103_FPUProfile(
      Pipeline = cfg.int("fpu.pipeline", 0),
      FMA = cfg.bool("fpu.fma", true),
      DivSqrt = cfg.bool("fpu.divsqrt", true),
      Rounding = cfg.bool("fpu.rounding", true),
      Flags = cfg.bool("fpu.flags", true),
      HardFloat = ysyx_26030103_HardFloatParams(
        Recode = cfg.bool("fpu.hardfloat.recode", true),
        DetectTininessBeforeRounding =
          cfg.bool("fpu.hardfloat.tininess_before_rounding", false)
      ),
      Fudian = ysyx_26030103_FudianParams(
        SharedDivSqrt = cfg.bool("fpu.fudian.shared_divsqrt", true)
      )
    )
  def aluProfile(
      cfg: ysyx_26030103_JsonConfig.Config
  ): ysyx_26030103_ALUProfile =
    ysyx_26030103_ALUProfile(
      Adder = ysyx_26030103_ALUAdderImpl.FromString(
        cfg.string("alu.adder_impl", "native")
      ),
      AdderParams = ysyx_26030103_ALUAdderParams(
        CarrySelectGroup = cfg.int("alu.carry_select_group", 8)
      ),
      Elau = ysyx_26030103_ElauParams(
        Speed = ysyx_26030103_ElauSpeed.FromString(
          cfg.string("alu.elau.speed", "fast")
        )
      )
    )
  private def divParams(
      cfg: ysyx_26030103_JsonConfig.Config,
      name: String
  ): ysyx_26030103_DIVParams =
    ysyx_26030103_DIVParams(
      Radix = cfg.int(
        s"mdu.div.$name.radix",
        cfg.int("mdu.div_radix", 2)
      ),
      IterBits = cfg.int(
        s"mdu.div.$name.iter_bits",
        cfg.int("mdu.div_iter_bits", 1)
      ),
      EarlyOut = cfg.bool(
        s"mdu.div.$name.early_out",
        cfg.bool("mdu.div_early_out", false)
      )
    )
  def mduProfile(
      cfg: ysyx_26030103_JsonConfig.Config
  ): ysyx_26030103_MDUProfile = {
    // mdu.mul_* 是旧扁平键，mdu.mul.* 是新分层键，两者都要保留。
    val MulRadix = cfg.int("mdu.mul_radix", 4)
    val MulWidth = cfg.int("mdu.mul_width", 32)
    val MulEncoding = cfg.string("mdu.mul_encoding", "plain")
    val MulIterBits = cfg.int("mdu.mul_iter_bits", 1)
    val MulSplit = cfg.int("mdu.mul_split", 1)
    val MulEarlyOut = cfg.bool("mdu.mul_early_out", false)
    val MulPipeline = cfg.int("mdu.mul_pipeline", 0)
    val CompressorInputs = cfg.int("mdu.mul_compressor.inputs", 3)
    val CompressorOutputs = cfg.int("mdu.mul_compressor.outputs", 2)
    val CompressorOutputOffsets =
      cfg.ints("mdu.mul_compressor.output_offsets", Seq(0, 1)).toIndexedSeq
    val CompressorHasCin =
      cfg.bool("mdu.mul_compressor.has_cin", false)
    val CompressorHasCout =
      cfg.bool("mdu.mul_compressor.has_cout", false)
    val CompressorCoutOffset =
      cfg.int("mdu.mul_compressor.cout_offset", 1)
    val CompressorAllowRedundant =
      cfg.bool("mdu.mul_compressor.allow_redundant", false)
    ysyx_26030103_MDUProfile(
      MUL = ysyx_26030103_MULProfile(
        Width = cfg.int("mdu.mul.width", MulWidth),
        Encoding = ysyx_26030103_MULEncoding.FromString(
          cfg.string("mdu.mul.encoding", MulEncoding)
        ),
        Radix = cfg.int("mdu.mul.radix", MulRadix),
        ShiftAdd = ysyx_26030103_MULShiftAddParams(
          IterBits = cfg.int(
            "mdu.mul.shift_add.iter_bits",
            MulIterBits
          ),
          Split = cfg.int("mdu.mul.shift_add.split", MulSplit),
          EarlyOut = cfg.bool(
            "mdu.mul.shift_add.early_out",
            MulEarlyOut
          )
        ),
        Compression = ysyx_26030103_MULCompressionParams(
          Pipeline = cfg.int(
            "mdu.mul.compression.pipeline",
            MulPipeline
          ),
          Compressor = ysyx_26030103_MULGPCConfig(
            Inputs = cfg.int(
              "mdu.mul.compression.compressor.inputs",
              CompressorInputs
            ),
            Outputs = cfg.int(
              "mdu.mul.compression.compressor.outputs",
              CompressorOutputs
            ),
            OutputOffsets = cfg.ints(
              "mdu.mul.compression.compressor.output_offsets",
              CompressorOutputOffsets
            ).toIndexedSeq,
            HasCin = cfg.bool(
              "mdu.mul.compression.compressor.has_cin",
              CompressorHasCin
            ),
            HasCout = cfg.bool(
              "mdu.mul.compression.compressor.has_cout",
              CompressorHasCout
            ),
            CoutOffset = cfg.int(
              "mdu.mul.compression.compressor.cout_offset",
              CompressorCoutOffset
            ),
            AllowRedundant = cfg.bool(
              "mdu.mul.compression.compressor.allow_redundant",
              CompressorAllowRedundant
            )
          )
        )
      ),
      DIV = ysyx_26030103_DIVProfile(
        Restoring = divParams(cfg, "restoring"),
        NonRestoring = divParams(cfg, "nonrestoring"),
        SRT = divParams(cfg, "srt")
      ),
      Rocket = ysyx_26030103_RocketMDUParams(
        MULUnroll = cfg.int("mdu.rocket.mul_unroll", 1),
        DIVUnroll = cfg.int("mdu.rocket.div_unroll", 1),
        MULEarlyOut = cfg.bool("mdu.rocket.mul_early_out", false),
        DIVEarlyOut = cfg.bool("mdu.rocket.div_early_out", false),
        DIVEarlyOutGranularity =
          cfg.int("mdu.rocket.div_early_out_granularity", 1)
      )
    )
  }
  def mmuProfile(
      cfg: ysyx_26030103_JsonConfig.Config,
      xlen: Int
  ): ysyx_26030103_MMUProfile =
    ysyx_26030103_MMUProfile.fromEnv(key => cfg.flat.get(key), xlen)
}
