import chisel3._
import ysyx_26030103.ysyx_26030103
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.common.ysyx_26030103_PhysicalMemoryMap
import java.io.File
import java.nio.file.{Files, Paths, StandardCopyOption}

object ysyx_26030103_Elaborate extends App {
  val targetDir = args(args.indexOf("--target-dir") + 1)

  val BlockSizeLog2 = sys.env.getOrElse("CACHE_BLOCK_SIZE_LOG2", "4").toInt
  val IndexBits = sys.env.getOrElse("CACHE_INDEX_BITS", "5").toInt
  // 每级缓存可单独配置几何；未设置时跟随共享的 CACHE_BLOCK_SIZE_LOG2/INDEX_BITS。
  private def envIntOr(key: String, fallback: Int): Int =
    sys.env.get(key).filter(_.nonEmpty).map(_.toInt).getOrElse(fallback)
  private def envBoolOr(key: String, fallback: Boolean): Boolean =
    sys.env.get(key).filter(_.nonEmpty).map(_ == "y").getOrElse(fallback)
  val ICacheBlockSizeLog2 = envIntOr("ICACHE_BLOCK_SIZE_LOG2", BlockSizeLog2)
  val ICacheIndexBits = envIntOr("ICACHE_INDEX_BITS", IndexBits)
  val DCacheBlockSizeLog2 = envIntOr("DCACHE_BLOCK_SIZE_LOG2", BlockSizeLog2)
  val DCacheIndexBits = envIntOr("DCACHE_INDEX_BITS", IndexBits)
  val ICacheEnable = sys.env.getOrElse("ICACHE", "y") == "y"
  // ICache数据阵列存储介质："reg"=寄存器阵列(默认)，"sram"=SyncReadMem同步读阵列
  val ICacheStorageName =
    sys.env.get("ICACHE_STORAGE").filter(_.nonEmpty).getOrElse("reg")
  val DCacheEnable = sys.env.getOrElse("DCACHE", "n") == "y"
  // DCache数据阵列存储介质："reg"=寄存器阵列(默认)，"sram"=SyncReadMem同步读阵列
  val DCacheStorageName =
    sys.env.get("DCACHE_STORAGE").filter(_.nonEmpty).getOrElse("reg")
  // 一致性模式：默认分别是标准 fence.i 与既有 DMA 写 B 失效行为。
  // 未实现的值会在 FromString 中明确报错，绝不静默回退。
  val ICacheCoherenceName =
    sys.env.get("ICACHE_COHERENCE").filter(_.nonEmpty).getOrElse("fence_i")
  val DmaCoherenceName =
    sys.env.get("DMA_COHERENCE").filter(_.nonEmpty).getOrElse("software_flush")
  val WBufDepth = sys.env.getOrElse("WBUF_DEPTH", "4").toInt
  val BTBBits = sys.env.getOrElse("BTB_BITS", "4").toInt
  val BTBWays = sys.env.getOrElse("BTB_WAYS", "1").toInt
  val JalBTBBits = sys.env.getOrElse("JAL_BTB_BITS", "4").toInt
  val JalBTBWays = sys.env.getOrElse("JAL_BTB_WAYS", "1").toInt
  val RASBits = sys.env.getOrElse("RAS_BITS", "4").toInt
  // ICache 对齐填充：npc 独立仿真的复位地址 = 0x80000000 + CACHE_PADDING
  // 与 Makefile 的 NPC_RESET_PC、ImageLoader 写入 direct RAM 的前置零保持一致
  val CachePadding = sys.env.getOrElse("CACHE_PADDING", "0").toInt
  val CacheableBase_ysyxsoc = 0x00000000L
  val CacheableMask_ysyxsoc = 0x00000000L
  val CacheableBase_npc = 0x80000000L
  val CacheableMask_npc = 0x80000000L
  val HasChipLink = sys.env.getOrElse("CHIPLINK", "n") == "y"
  // 用kconfig来指挥的指令集扩展开关，然后没有实现的部分就直接让NPCConfig的require判断为失败的条件就可以了
  val XLEN = envIntOr("XLEN", 32)
  // 物理地址宽度跟随架构位宽：RV32 保持 32，RV64 默认 64。允许
  // ADDRESS_WIDTH 显式覆盖，但 NPCConfig 会拒绝 XLEN=64 使用 32 位
  // 地址，避免架构 PC/分支目标再次被截断。
  val AddressWidth = envIntOr("ADDRESS_WIDTH", if (XLEN == 64) 64 else 32)
  // ysyxSoC CPU 侧接口位宽独立于 CPU XLEN：
  //   SOC_XLEN_FOLLOW_CPU=y  -> EffectiveSocXLEN=XLEN
  //   SOC_XLEN_FOLLOW_CPU=n  -> EffectiveSocXLEN=SOC_XLEN（默认，32）
  // SOC_XLEN 只在 FOLLOW=n 时生效，NPCConfig 会拒绝 32/64 之外的值。
  val SocXLENFollowCpu =
    sys.env.getOrElse("SOC_XLEN_FOLLOW_CPU", "n") == "y"
  val SocXLEN = envIntOr("SOC_XLEN", 32)
  // 扩展名与位宽解耦，统一使用 RISCV_M/A/C。
  val UseM = sys.env.getOrElse("RISCV_M", "n") == "y"
  val UseA = sys.env.getOrElse("RISCV_A", "n") == "y"
  val UseC = sys.env.getOrElse("RISCV_C", "n") == "y"
  // F/D 与 FPU 后端。后端未接入时 NPCConfig 会明确拒绝，绝不静默
  // 忽略 RISCV_F；未设置的环境变量取 Kconfig 默认值，保证既有构建
  // 脚本无需修改即可保持 F/D=n、backend=none。
  val UseF = sys.env.getOrElse("RISCV_F", "n") == "y"
  val UseD = sys.env.getOrElse("RISCV_D", "n") == "y"
  val FPUImplName =
    sys.env.get("FPU_IMPL").filter(_.nonEmpty).getOrElse("none")
  val FPULen = envIntOr("FPU_FLEN", 64)
  val FPUPipeline = envIntOr("FPU_PIPELINE", 0)
  val FPUFMA = envBoolOr("FPU_FMA", true)
  val FPUDivSqrt = envBoolOr("FPU_DIVSQRT", true)
  val FPURounding = envBoolOr("FPU_ROUNDING", true)
  val FPUFlags = envBoolOr("FPU_FLAGS", true)
  val FPUHardFloatRecode = envBoolOr("FPU_HARDFLOAT_RECODE", true)
  val FPUHardFloatTininessBefore =
    envBoolOr("FPU_HARDFLOAT_TININESS_BEFORE_ROUNDING", false)
  val FPUFudianSharedDivSqrt =
    envBoolOr("FPU_FUDIAN_SHARED_DIVSQRT", true)
  val MULImplName =
    sys.env.get("MUL_IMPL").filter(_.nonEmpty).getOrElse("shift_add")
  val MULEncodingName =
    sys.env.get("MUL_ENCODING").filter(_.nonEmpty).getOrElse("plain")
  val DIVImplName =
    sys.env.get("DIV_IMPL").filter(_.nonEmpty).getOrElse("restoring")
  val MULRadix = sys.env.getOrElse("MUL_RADIX", "4").toInt
  val MULWidth = sys.env.getOrElse("MUL_WIDTH", "32").toInt
  val MULCompressorInputs = sys.env.getOrElse("MUL_COMPRESSOR_INPUTS", "3").toInt
  val MULCompressorOutputs = sys.env.getOrElse("MUL_COMPRESSOR_OUTPUTS", "2").toInt
  val MULCompressorOutputOffsets = sys.env
    .getOrElse("MUL_COMPRESSOR_OUTPUT_OFFSETS", "0,1")
    .split(',')
    .map(_.trim)
    .filter(_.nonEmpty)
    .map(_.toInt)
    .toIndexedSeq
  val MULCompressorHasCin = sys.env.getOrElse("MUL_COMPRESSOR_HAS_CIN", "n") == "y"
  val MULCompressorHasCout = sys.env.getOrElse("MUL_COMPRESSOR_HAS_COUT", "n") == "y"
  val MULCompressorCoutOffset = sys.env.getOrElse("MUL_COMPRESSOR_COUT_OFFSET", "1").toInt
  val MULCompressorAllowRedundant = sys.env.getOrElse("MUL_COMPRESSOR_ALLOW_REDUNDANT", "n") == "y"
  val DIVRadix = sys.env.getOrElse("DIV_RADIX", "2").toInt
  val DIVIterBits = sys.env.getOrElse("DIV_ITER_BITS", "1").toInt
  val DIVEarlyOut = sys.env.getOrElse("DIV_EARLY_OUT", "n") == "y"
  val MULIterBits = sys.env.getOrElse("MUL_ITER_BITS", "1").toInt
  val MULPipeline = sys.env.getOrElse("MUL_PIPELINE", "0").toInt
  val MULSplit = sys.env.getOrElse("MUL_SPLIT", "1").toInt
  val MULEarlyOut = sys.env.getOrElse("MUL_EARLY_OUT", "n") == "y"
  val SDBEnable = sys.env.getOrElse("SDB", "n") == "y"
  val DiffTestEnable = sys.env.getOrElse("DIFFTEST", "n") == "y"
  val MTraceEnable = sys.env.getOrElse("MTRACE", "n") == "y"
  val ITraceEnable = sys.env.getOrElse("ITRACE", "n") == "y"
  val FTraceEnable = sys.env.getOrElse("FTRACE", "n") == "y"
  val IRingBufEnable = sys.env.getOrElse("IRINGBUF", "n") == "y"
  val PerfStatsEnable = sys.env.getOrElse("PERF_STATS", "n") == "y"
  // 性能计数器：组总开关与单计数器开关。主开关关闭时全部关闭；未提供的
  // 符号按默认开启，与Kconfig默认值以及尚未重新生成配置的工作区保持一致。
  // 单计数器与组总开关的相与在NPCConfig里统一完成。
  def PerfGroupEnabled(name: String): Boolean =
    sys.env.getOrElse(name, "y") == "y"
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
      PerfStatsEnable && sys.env
        .getOrElse(s"PERF_COUNTER_${name.toUpperCase}", "y") == "y"
    }
  val mmuProfile = ysyx_26030103_MMUProfile.fromEnv(sys.env.get, XLEN)
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
    MULImpl = ysyx_26030103_MULImpl.FromString(MULImplName),
    MULEncoding = ysyx_26030103_MULEncoding.FromString(MULEncodingName),
    DIVImpl = ysyx_26030103_DIVImpl.FromString(DIVImplName),
    MULRadix = MULRadix,
    MULWidth = MULWidth,
    MULCompressorInputs = MULCompressorInputs,
    MULCompressorOutputs = MULCompressorOutputs,
    MULCompressorOutputOffsets = MULCompressorOutputOffsets,
    MULCompressorHasCin = MULCompressorHasCin,
    MULCompressorHasCout = MULCompressorHasCout,
    MULCompressorCoutOffset = MULCompressorCoutOffset,
    MULCompressorAllowRedundant = MULCompressorAllowRedundant,
    DIVRadix = DIVRadix,
    DIVIterBits = DIVIterBits,
    DIVEarlyOut = DIVEarlyOut,
    MULIterBits = MULIterBits,
    MULPipeline = MULPipeline,
    MULSplit = MULSplit,
    MULEarlyOut = MULEarlyOut,
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
        PMARegions = ysyx_26030103_PhysicalMemoryMap.NPC
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
    new _root_.ysyx_26030103.riscv32e_npc_AXIRAM,
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
