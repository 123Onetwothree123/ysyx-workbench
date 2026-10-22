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
  // 扩展名与位宽解耦，统一使用 RISCV_M/A/C。
  val UseM = sys.env.getOrElse("RISCV_M", "n") == "y"
  val UseA = sys.env.getOrElse("RISCV_A", "n") == "y"
  val UseC = sys.env.getOrElse("RISCV_C", "n") == "y"
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
  val mmuProfile = ysyx_26030103_MMUProfile.fromEnv(sys.env.get)
  val config = ysyx_26030103_NPCConfig(
    XLEN = XLEN,
    AddressWidth = AddressWidth,
    UseM = UseM,
    UseA = UseA,
    UseC = UseC,
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
