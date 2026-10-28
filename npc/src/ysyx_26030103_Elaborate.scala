import chisel3._
import ysyx_26030103.ysyx_26030103
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.common.ysyx_26030103_PhysicalMemoryMap
import java.io.File
import java.nio.file.{Files, Paths, StandardCopyOption}

object ysyx_26030103_Elaborate extends App {
  val targetDir = args(args.indexOf("--target-dir") + 1)

  // 硬件配置只从生成后的 typed JSON 读取；命令行覆盖由 Makefile
  // 通过 npc_config_gen --set 合并进同一份文件，Scala 不再读取 sys.env
  // 中的 CACHE_*/FPU_* 等开关，避免 RTL 与 C++ 模块出现两套配置。
  val configJsonPath = ysyx_26030103_JsonConfig.resolvePath()
  val cfg = ysyx_26030103_JsonConfig.load(configJsonPath)
  println(s"[NPC config] source: $configJsonPath")

  val BlockSizeLog2 = cfg.int("cache.block_size_log2", 4)
  val IndexBits = cfg.int("cache.index_bits", 5)
  // 每级缓存可单独配置几何；未设置时跟随共享的 CACHE_BLOCK_SIZE_LOG2/INDEX_BITS。
  val ICacheBlockSizeLog2 =
    cfg.int("cache.icache.block_size_log2", BlockSizeLog2)
  val ICacheIndexBits = cfg.int("cache.icache.index_bits", IndexBits)
  val DCacheBlockSizeLog2 =
    cfg.int("cache.dcache.block_size_log2", BlockSizeLog2)
  val DCacheIndexBits = cfg.int("cache.dcache.index_bits", IndexBits)
  val ICacheEnable = cfg.bool("cache.icache.enabled", true)
  // ICache数据阵列存储介质："reg"=寄存器阵列(默认)，"sram"=SyncReadMem同步读阵列
  val ICacheStorageName = cfg.string("cache.icache.storage", "reg")
  val DCacheEnable = cfg.bool("cache.dcache.enabled", false)
  // DCache数据阵列存储介质："reg"=寄存器阵列(默认)，"sram"=SyncReadMem同步读阵列
  val DCacheStorageName = cfg.string("cache.dcache.storage", "reg")
  // 一致性模式：默认分别是标准 fence.i 与既有 DMA 写 B 失效行为。
  // 未实现的值会在 FromString 中明确报错，绝不静默回退。
  val ICacheCoherenceName =
    cfg.string("cache.icache.coherence", "fence_i")
  val DmaCoherenceName =
    cfg.string("cache.dma_coherence", "software_flush")
  val WBufDepth = cfg.int("cache.wbuf_depth", 4)
  val BTBBits = cfg.int("frontend.btb_bits", 4)
  val BTBWays = cfg.int("frontend.btb_ways", 1)
  val JalBTBBits = cfg.int("frontend.jal_btb_bits", 4)
  val JalBTBWays = cfg.int("frontend.jal_btb_ways", 1)
  val RASBits = cfg.int("frontend.ras_bits", 4)
  // ICache 对齐填充：npc 独立仿真的复位地址 = 0x80000000 + CACHE_PADDING
  // 与 Makefile 的 NPC_RESET_PC、ImageLoader 写入 direct RAM 的前置零保持一致
  val CachePadding = cfg.int("platform.cache_padding", 0)
  // direct-NPC RAM 大小（字节），Kconfig 的 NPC_RAM_SIZE 经 Makefile 投影
  // 到同一份 JSON。AXIRAM 深度、CPU PMA 与 C++ CONFIG_MSIZE 使用同一个值。
  val NpcRamSize: Long = {
    val parsed = cfg.long("platform.ram_size", 0x04000000L)
    require(parsed >= 4096L, s"NPC_RAM_SIZE=$parsed 太小")
    require((parsed & (parsed - 1L)) == 0L, s"NPC_RAM_SIZE=$parsed 必须是 2 的幂")
    require(parsed % 4L == 0L, s"NPC_RAM_SIZE=$parsed 必须是 4 字节的倍数")
    parsed
  }
  println(s"[NPC config] direct-NPC RAM: 0x${NpcRamSize.toHexString} ($NpcRamSize bytes)")
  val CacheableBase_ysyxsoc = 0x00000000L
  val CacheableMask_ysyxsoc = 0x00000000L
  val CacheableBase_npc = 0x80000000L
  val CacheableMask_npc = 0x80000000L
  val HasChipLink = cfg.bool("platform.chip_link", false)
  // 用kconfig来指挥的指令集扩展开关，然后没有实现的部分就直接让NPCConfig的require判断为失败的条件就可以了
  val XLEN = cfg.int("isa.xlen", 32)
  // 物理地址宽度跟随架构位宽：RV32 保持 32，RV64 默认 64。允许
  // ADDRESS_WIDTH 显式覆盖，但 NPCConfig 会拒绝 XLEN=64 使用 32 位
  // 地址，避免架构 PC/分支目标再次被截断。
  val AddressWidth =
    cfg.int("platform.address_width", if (XLEN == 64) 64 else 32)
  // ysyxSoC CPU 侧接口位宽独立于 CPU XLEN：
  //   SOC_XLEN_FOLLOW_CPU=y  -> EffectiveSocXLEN=XLEN
  //   SOC_XLEN_FOLLOW_CPU=n  -> EffectiveSocXLEN=SOC_XLEN（默认，32）
  // SOC_XLEN 只在 FOLLOW=n 时生效，NPCConfig 会拒绝 32/64 之外的值。
  val SocXLENFollowCpu = cfg.bool("isa.soc_xlen_follow_cpu", false)
  val SocXLEN = cfg.int("isa.soc_xlen", 32)
  // 扩展名与位宽解耦，统一使用 RISCV_M/A/C。
  val UseM = cfg.bool("isa.m", false)
  val UseA = cfg.bool("isa.a", false)
  val UseC = cfg.bool("isa.c", false)
  // F/D 与 FPU 后端。后端未接入时 NPCConfig 会明确拒绝，绝不静默
  // 忽略 RISCV_F；JSON 未提供时取 Kconfig 默认值，保证既有构建脚本
  // 无需修改即可保持 F/D=n、backend=none。
  val UseF = cfg.bool("isa.f", false)
  val UseD = cfg.bool("isa.d", false)
  val FPUImplName = cfg.string("fpu.impl", "none")
  val FPULen = cfg.int("fpu.flen", 64)
  val FPUPipeline = cfg.int("fpu.pipeline", 0)
  val FPUFMA = cfg.bool("fpu.fma", true)
  val FPUDivSqrt = cfg.bool("fpu.divsqrt", true)
  val FPURounding = cfg.bool("fpu.rounding", true)
  val FPUFlags = cfg.bool("fpu.flags", true)
  val FPUHardFloatRecode = cfg.bool("fpu.hardfloat.recode", true)
  val FPUHardFloatTininessBefore =
    cfg.bool("fpu.hardfloat.tininess_before_rounding", false)
  val FPUFudianSharedDivSqrt =
    cfg.bool("fpu.fudian.shared_divsqrt", true)
  val MDUImplName = cfg.string("mdu.impl", "standard")
  val MULImplName = cfg.string("mdu.mul_impl", "shift_add")
  val MULEncodingName = cfg.string("mdu.mul_encoding", "plain")
  val DIVImplName = cfg.string("mdu.div_impl", "restoring")
  val MULRadix = cfg.int("mdu.mul_radix", 4)
  val MULWidth = cfg.int("mdu.mul_width", 32)
  val MULCompressorInputs = cfg.int("mdu.mul_compressor.inputs", 3)
  val MULCompressorOutputs = cfg.int("mdu.mul_compressor.outputs", 2)
  val MULCompressorOutputOffsets =
    cfg.ints("mdu.mul_compressor.output_offsets", Seq(0, 1)).toIndexedSeq
  val MULCompressorHasCin = cfg.bool("mdu.mul_compressor.has_cin", false)
  val MULCompressorHasCout = cfg.bool("mdu.mul_compressor.has_cout", false)
  val MULCompressorCoutOffset = cfg.int("mdu.mul_compressor.cout_offset", 1)
  val MULCompressorAllowRedundant =
    cfg.bool("mdu.mul_compressor.allow_redundant", false)
  val DIVRadix = cfg.int("mdu.div_radix", 2)
  val DIVIterBits = cfg.int("mdu.div_iter_bits", 1)
  val DIVEarlyOut = cfg.bool("mdu.div_early_out", false)
  val MULIterBits = cfg.int("mdu.mul_iter_bits", 1)
  val MULPipeline = cfg.int("mdu.mul_pipeline", 0)
  val MULSplit = cfg.int("mdu.mul_split", 1)
  val MULEarlyOut = cfg.bool("mdu.mul_early_out", false)
  def divParams(name: String): ysyx_26030103_DIVParams =
    ysyx_26030103_DIVParams(
      Radix = cfg.int(s"mdu.div.$name.radix", DIVRadix),
      IterBits = cfg.int(s"mdu.div.$name.iter_bits", DIVIterBits),
      EarlyOut = cfg.bool(s"mdu.div.$name.early_out", DIVEarlyOut)
    )
  val mduProfile = ysyx_26030103_MDUProfile(
    MUL = ysyx_26030103_MULProfile(
      Width = cfg.int("mdu.mul.width", MULWidth),
      Encoding = ysyx_26030103_MULEncoding.FromString(
        cfg.string("mdu.mul.encoding", MULEncodingName)
      ),
      Radix = cfg.int("mdu.mul.radix", MULRadix),
      ShiftAdd = ysyx_26030103_MULShiftAddParams(
        IterBits = cfg.int("mdu.mul.shift_add.iter_bits", MULIterBits),
        Split = cfg.int("mdu.mul.shift_add.split", MULSplit),
        EarlyOut = cfg.bool("mdu.mul.shift_add.early_out", MULEarlyOut)
      ),
      Compression = ysyx_26030103_MULCompressionParams(
        Pipeline = cfg.int("mdu.mul.compression.pipeline", MULPipeline),
        Compressor = ysyx_26030103_MULGPCConfig(
          Inputs = cfg.int("mdu.mul.compression.compressor.inputs",
            MULCompressorInputs),
          Outputs = cfg.int("mdu.mul.compression.compressor.outputs",
            MULCompressorOutputs),
          OutputOffsets = cfg.ints(
            "mdu.mul.compression.compressor.output_offsets",
            MULCompressorOutputOffsets
          ).toIndexedSeq,
          HasCin = cfg.bool("mdu.mul.compression.compressor.has_cin",
            MULCompressorHasCin),
          HasCout = cfg.bool("mdu.mul.compression.compressor.has_cout",
            MULCompressorHasCout),
          CoutOffset = cfg.int("mdu.mul.compression.compressor.cout_offset",
            MULCompressorCoutOffset),
          AllowRedundant = cfg.bool(
            "mdu.mul.compression.compressor.allow_redundant",
            MULCompressorAllowRedundant
          )
        )
      )
    ),
    DIV = ysyx_26030103_DIVProfile(
      Restoring = divParams("restoring"),
      NonRestoring = divParams("nonrestoring"),
      SRT = divParams("srt")
    )
  )
  val SDBEnable = cfg.bool("debug.sdb", false)
  val DiffTestEnable = cfg.bool("debug.difftest", false)
  val MTraceEnable = cfg.bool("debug.mtrace", false)
  val ITraceEnable = cfg.bool("debug.itrace", false)
  val FTraceEnable = cfg.bool("debug.ftrace", false)
  val IRingBufEnable = cfg.bool("debug.iringbuf", false)
  val PerfStatsEnable = cfg.bool("debug.perf_stats", false)
  // 性能计数器：组总开关与单计数器开关。主开关关闭时全部关闭；未提供的
  // 符号按默认开启，与Kconfig默认值以及尚未重新生成配置的工作区保持一致。
  // 单计数器与组总开关的相与在NPCConfig里统一完成。
  def PerfGroupEnabled(name: String): Boolean =
    cfg.bool(s"debug.perf_groups.${name.stripPrefix("PERF_COUNTER_").toLowerCase}",
      true)
  val PerfCounterBasic = PerfGroupEnabled("PERF_COUNTER_BASIC")
  val PerfCounterOpClass = PerfGroupEnabled("PERF_COUNTER_OPCLASS")
  val PerfCounterMDU = PerfGroupEnabled("PERF_COUNTER_MDU")
  val PerfCounterIfuStall = PerfGroupEnabled("PERF_COUNTER_IFU_STALL")
  val PerfCounterICache = PerfGroupEnabled("PERF_COUNTER_ICACHE")
  val PerfCounterDCache = PerfGroupEnabled("PERF_COUNTER_DCACHE")
  val PerfCounterExuStall = PerfGroupEnabled("PERF_COUNTER_EXU_STALL")
  val PerfCounterLSU = PerfGroupEnabled("PERF_COUNTER_LSU")
  val PerfCounterIduStall = PerfGroupEnabled("PERF_COUNTER_IDU_STALL")
  val PerfCounterTrap = PerfGroupEnabled("PERF_COUNTER_TRAP")
  val PerfCounters: Set[String] =
    ysyx_26030103_PerfCounterCatalog.AllPerfCounters.filter { name =>
      PerfStatsEnable && cfg.bool(
        s"debug.perf_counters.$name",
        true
      )
    }
  val mmuProfile =
    ysyx_26030103_MMUProfile.fromEnv(key => cfg.flat.get(key), XLEN)
  val config = ysyx_26030103_NPCConfig(
    XLEN = XLEN,
    AddressWidth = AddressWidth,
    SocXLENFollowCpu = SocXLENFollowCpu,
    SocXLEN = SocXLEN,
    UseM = UseM,
    UseA = UseA,
    UseC = UseC,
    UseF = UseF,
    UseD = UseD,
    FPUImpl = ysyx_26030103_FPUImpl.FromString(FPUImplName),
    FPULen = FPULen,
    FPUProfile = ysyx_26030103_FPUProfile(
      Pipeline = FPUPipeline,
      FMA = FPUFMA,
      DivSqrt = FPUDivSqrt,
      Rounding = FPURounding,
      Flags = FPUFlags,
      HardFloat = ysyx_26030103_HardFloatParams(
        Recode = FPUHardFloatRecode,
        DetectTininessBeforeRounding = FPUHardFloatTininessBefore
      ),
      Fudian = ysyx_26030103_FudianParams(
        SharedDivSqrt = FPUFudianSharedDivSqrt
      )
    ),
    MDUImpl = ysyx_26030103_MDUImpl.FromString(MDUImplName),
    MULImpl = ysyx_26030103_MULImpl.FromString(MULImplName),
    DIVImpl = ysyx_26030103_DIVImpl.FromString(DIVImplName),
    MDUProfile = mduProfile,
    ICacheEnable = ICacheEnable,
    ICacheStorage = ysyx_26030103_CacheStorage.FromString(ICacheStorageName),
    DCacheEnable = DCacheEnable,
    DCacheStorage = ysyx_26030103_CacheStorage.FromString(DCacheStorageName),
    ICacheCoherence =
      ysyx_26030103_ICacheCoherence.FromString(ICacheCoherenceName),
    DmaCoherence =
      ysyx_26030103_DmaCoherence.FromString(DmaCoherenceName),
    // 日志中的 Describe 必须反映实际 SoC PMA（DMA 窗口是否存在），
    // 否则 CHIPLINK=y 时 hardware_snoop/uncached 会误标成 inactive。
    PMARegions = ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink),
    BlockSizeLog2 = BlockSizeLog2,
    IndexBits = IndexBits,
    ICacheBlockSizeLog2 = ICacheBlockSizeLog2,
    ICacheIndexBits = ICacheIndexBits,
    DCacheBlockSizeLog2 = DCacheBlockSizeLog2,
    DCacheIndexBits = DCacheIndexBits,
    WBufDepth = WBufDepth,
    BTBBits = BTBBits,
    BTBWays = BTBWays,
    JalBTBBits = JalBTBBits,
    JalBTBWays = JalBTBWays,
    RASBits = RASBits,
    SDBEnable = SDBEnable,
    DiffTestEnable = DiffTestEnable,
    MTraceEnable = MTraceEnable,
    ITraceEnable = ITraceEnable,
    FTraceEnable = FTraceEnable,
    IRingBufEnable = IRingBufEnable,
    PerfStatsEnable = PerfStatsEnable,
    PerfCounterBasic = PerfCounterBasic,
    PerfCounterOpClass = PerfCounterOpClass,
    PerfCounterMDU = PerfCounterMDU,
    PerfCounterIfuStall = PerfCounterIfuStall,
    PerfCounterICache = PerfCounterICache,
    PerfCounterDCache = PerfCounterDCache,
    PerfCounterExuStall = PerfCounterExuStall,
    PerfCounterLSU = PerfCounterLSU,
    PerfCounterIduStall = PerfCounterIduStall,
    PerfCounterTrap = PerfCounterTrap,
    PerfCounters = PerfCounters,
    MMU = mmuProfile
  )
  println(s"[NPC config] ${config.Describe}")
  if (config.PiptCacheLineExceedsPage) {
    println(
      "[NPC config] PIPT cache line 大于 MMU 页大小：" +
        "索引与 refill 使用完整物理地址，不按页内偏移截断；" +
        "VIPT 下该几何会在 elaboration 期被拒绝。"
    )
  }
  if (config.ICacheViptAliasHardware || config.DCacheViptAliasHardware) {
    val mode = config.MMU.cacheViptAliasScanMode
    val detail = mode match {
      case "parallel_n" =>
        s"ICache=${config.ICacheViptAliasScanWidth}/DCache=${config.DCacheViptAliasScanWidth} set/拍；"
      case "directory" =>
        s"目录项 ICache=${config.ICacheViptAliasDirectoryEntries}/" +
          s"DCache=${config.DCacheViptAliasDirectoryEntries}；"
      case "serial" => "serial 模式在扫描期间反压新请求；"
      case _ => ""
    }
    println(
      s"[NPC config] VIPT alias 维护已生成：scan_mode=$mode；$detail" +
        "refill/store/snoop 在旧副本清理完成前不交付新请求。"
    )
  }
  println(
    s"[NPC config] ICache 一致性: ${config.ICacheCoherence.Name}" +
      (if (config.ICacheCoherence ==
            ysyx_26030103_ICacheCoherence.HardwareSnoop) {
         if (config.ICacheEnable) "（store B 完成时失效对应行；仍需 fence.i）"
         else "（未生效：ICACHE=n）"
       } else "（标准语义：store 后需执行 fence.i）")
  )
  println(
    s"[NPC config] DMA 一致性: ${config.DmaCoherence.Name}" +
      (if (config.DmaCoherence == ysyx_26030103_DmaCoherence.Uncached) {
         if (config.DmaWindowPresent)
           "（0xc0000000--0xffffffff 在 CPU 侧不可缓存，绕过 DCache/ICache）"
         else "（未生效：当前 PMA 没有可代理的 DMA 窗口，CHIPLINK=n？）"
       } else if (config.DmaCoherence ==
                    ysyx_26030103_DmaCoherence.HardwareSnoop) {
         if (!config.DmaWindowPresent)
           "（未生效：当前 PMA 没有可代理的 DMA 窗口，CHIPLINK=n？）"
         else if (!config.ICacheEnable && !config.DCacheEnable)
           "（未生效：ICACHE=n 且 DCACHE=n，没有可失效的缓存）"
         else
           "（DMA 写 B 到达后按物理字节范围失效对应行，B 等待失效完成；" +
             "DMA 读不产生动作，已取入流水线的旧指令仍需 fence.i）"
       } else "（保持 DMA 写 B 完成时失效 DCache；软件负责 DMA 完成顺序）")
  )
  if (config.SocXLENNeedsAxiBridge) {
    println(
      s"[NPC config] EffectiveSocXLEN=${config.EffectiveSocXLEN}: " +
        "顶层 master/slave AXI 使用 64 位地址/数据，32<->64 边界桥已启用；" +
        "内部 Xbar/PMA/Cache 仍使用 32 位数据 beat。"
    )
  }

  // 让 firtool 直接输出 yosys 能读的语法:
  //   disallowLocalVariables  禁止 always 块内声明变量(消除 automatic logic 声明+初始化)
  //   disallowPackedArrays    打散多维 packed 数组(消除 [3:0][7:0] 和 '{...} 赋值模式)
  // UseSRAMBlackbox: SRAM API的内存由Chisel自己生成Verilog(内联黑盒),
  // 不经过CIRCT的memory降低, 因此多写口的字节掩码不会被丢弃。
  val yosysFirtoolOpts: firrtl.AnnotationSeq = Seq(
    circt.stage.FirtoolOption(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays"
    ),
    chisel3.stage.UseSRAMBlackbox
  )

  emitVerilog(
    new ysyx_26030103(
      config.copy(
        ResetAddr = 0x80000000L + CachePadding,
        CacheableBase = CacheableBase_npc,
        CacheableMask = CacheableMask_npc,
        PMARegions = ysyx_26030103_PhysicalMemoryMap.NPCWithSize(NpcRamSize)
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
        CacheableMask = CacheableMask_ysyxsoc,
        PMARegions = ysyx_26030103_PhysicalMemoryMap.SoC(HasChipLink)
      )
    ),
    Array("--target-dir", targetDir),
    yosysFirtoolOpts
  )

  emitVerilog(
    new _root_.ysyx_26030103.riscv32e_npc_AXIRAM(NpcRamSize),
    Array("--target-dir", targetDir),
    yosysFirtoolOpts
  )

  // 也扫描 AXIRAM：它的层文件引用同样需要空桩，否则叶子构建会缺 include。
  for (top <- Seq("ysyx_26030103", "ysyx_26030103_npc", "riscv32e_npc_AXIRAM")) {
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
