import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.common.ysyx_26030103_PMAOptions
object ysyx_26030103_ElaborateConfig {
  def build(
      cfg: ysyx_26030103_JsonConfig.Config
  ): ysyx_26030103_ElaborateSettings = {
    val BlockSizeLog2 = cfg.int("cache.block_size_log2", 4)
    val IndexBits = cfg.int("cache.index_bits", 5)
    val ICacheBlockSizeLog2 =
      cfg.int("cache.icache.block_size_log2", BlockSizeLog2)
    val ICacheIndexBits = cfg.int("cache.icache.index_bits", IndexBits)
    val DCacheBlockSizeLog2 =
      cfg.int("cache.dcache.block_size_log2", BlockSizeLog2)
    val DCacheIndexBits = cfg.int("cache.dcache.index_bits", IndexBits)
    val ICacheEnable = cfg.bool("cache.icache.enabled", true)
    val ICacheStorageName = cfg.string("cache.icache.storage", "reg")
    val DCacheEnable = cfg.bool("cache.dcache.enabled", false)
    val DCacheStorageName = cfg.string("cache.dcache.storage", "reg")
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
    val CachePadding = cfg.int("platform.cache_padding", 0)
    val NpcRamSize: Long = {
      val parsed = cfg.long("platform.ram_size", 0x04000000L)
      require(parsed >= 4096L, s"NPC_RAM_SIZE=$parsed 太小")
      require(
        (parsed & (parsed - 1L)) == 0L,
        s"NPC_RAM_SIZE=$parsed 必须是 2 的幂"
      )
      require(parsed % 4L == 0L, s"NPC_RAM_SIZE=$parsed 必须是 4 字节的倍数")
      parsed
    }
    println(
      s"[NPC config] direct-NPC RAM: 0x${NpcRamSize.toHexString}" +
        s" ($NpcRamSize bytes)"
    )
    val HasChipLink = cfg.bool("platform.chip_link", false)
    val Pma = ysyx_26030103_PMAOptions(
      clintBase = cfg.long("pma.clint_base", 0x02000000L),
      clintSize = cfg.long("pma.clint_size", 0x00010000L),
      sramBase = cfg.long("pma.sram_base", 0x0f000000L),
      sramSize = cfg.long("pma.sram_size", 0x00008000L),
      uartBase = cfg.long("pma.uart_base", 0x10000000L),
      uartSize = cfg.long("pma.uart_size", 0x00001000L),
      spiBase = cfg.long("pma.spi_base", 0x10001000L),
      spiSize = cfg.long("pma.spi_size", 0x00001000L),
      gpioBase = cfg.long("pma.gpio_base", 0x10002000L),
      gpioSize = cfg.long("pma.gpio_size", 0x00000010L),
      keyboardBase = cfg.long("pma.keyboard_base", 0x10011000L),
      keyboardSize = cfg.long("pma.keyboard_size", 0x00000008L),
      vgaBase = cfg.long("pma.vga_base", 0x21000000L),
      vgaSize = cfg.long("pma.vga_size", 0x00200000L),
      mromBase = cfg.long("pma.mrom_base", 0x30000000L),
      mromSize = cfg.long("pma.mrom_size", 0x10000000L),
      psramBase = cfg.long("pma.psram_base", 0x80000000L),
      psramSize = cfg.long("pma.psram_size", 0x00400000L),
      sdramBase = cfg.long("pma.sdram_base", 0xa0000000L),
      sdramSize = cfg.long("pma.sdram_size", 0x02000000L),
      chipLinkMmioBase = cfg.long("pma.chiplink_mmio_base", 0x40000000L),
      chipLinkMmioSize = cfg.long("pma.chiplink_mmio_size", 0x40000000L),
      chipLinkMemBase = cfg.long("pma.chiplink_mem_base", 0xc0000000L),
      chipLinkMemSize = cfg.long("pma.chiplink_mem_size", 0x40000000L),
      socResetAddr = cfg.long("pma.soc_reset_addr", 0x30000000L)
    )
    val XLEN = cfg.int("isa.xlen", 32)
    val AddressWidth =
      cfg.int("platform.address_width", if (XLEN == 64) 64 else 32)
    val SocXLENFollowCpu = cfg.bool("isa.soc_xlen_follow_cpu", false)
    val SocXLEN = cfg.int("isa.soc_xlen", 32)
    val InternalBusWidthFollowCpu =
      cfg.bool("isa.internal_bus_width_follow_cpu", true)
    val InternalBusWidth =
      cfg.int("isa.internal_bus_width", if (XLEN == 64) 64 else 32)
    val UseM = cfg.bool("isa.m", false)
    val UseA = cfg.bool("isa.a", false)
    val UseC = cfg.bool("isa.c", false)
    val UseF = cfg.bool("isa.f", false)
    val UseD = cfg.bool("isa.d", false)
    val FPUImplName = cfg.string("fpu.impl", "none")
    val FPULen = cfg.int("fpu.flen", 64)
    val ALUImplName = cfg.string("alu.impl", "standard")
    val IDUDecodeMinimizerName =
      cfg.string("idu.decode_minimizer", "qmc")
    val MDUImplName = cfg.string("mdu.impl", "standard")
    val MULImplName = cfg.string("mdu.mul_impl", "shift_add")
    val DIVImplName = cfg.string("mdu.div_impl", "restoring")
    val SDBEnable = cfg.bool("debug.sdb", false)
    val DiffTestEnable = cfg.bool("debug.difftest", false)
    val MTraceEnable = cfg.bool("debug.mtrace", false)
    val ITraceEnable = cfg.bool("debug.itrace", false)
    val FTraceEnable = cfg.bool("debug.ftrace", false)
    val IRingBufEnable = cfg.bool("debug.iringbuf", false)
    val DicArchCounterEnable = cfg.bool("debug.dic_arch_counter", false)
    val RiscvArchPMU = cfg.bool("debug.riscv_arch_pmu", false)
    val RiscvArchPMUZihpm =
      cfg.bool("debug.riscv_arch_pmu_zihpm", true)
    val RiscvArchPMUSscofpmf =
      cfg.bool("debug.riscv_arch_pmu_sscofpmf", true)
    val Perf = ysyx_26030103_ElaborateProfiles.perfSwitches(cfg)
    val PerfCounters = ysyx_26030103_ElaborateProfiles.perfCounters(
      cfg,
      DicArchCounterEnable
    )
    val MduProfile = ysyx_26030103_ElaborateProfiles.mduProfile(cfg)
    val MmuProfile =
      ysyx_26030103_ElaborateProfiles.mmuProfile(cfg, XLEN)
    val config = ysyx_26030103_NPCConfig(
      XLEN = XLEN,
      AddressWidth = AddressWidth,
      SocXLENFollowCpu = SocXLENFollowCpu,
      SocXLEN = SocXLEN,
      InternalBusWidthFollowCpu = InternalBusWidthFollowCpu,
      InternalBusWidth = InternalBusWidth,
      UseM = UseM,
      UseA = UseA,
      UseC = UseC,
      UseF = UseF,
      UseD = UseD,
      FPUImpl = ysyx_26030103_FPUImpl.FromString(FPUImplName),
      FPULen = FPULen,
      FPUProfile = ysyx_26030103_ElaborateProfiles.fpuProfile(cfg),
      ALUImpl = ysyx_26030103_ALUImpl.FromString(ALUImplName),
      ALUProfile = ysyx_26030103_ElaborateProfiles.aluProfile(cfg),
      IDUDecodeMinimizer =
        ysyx_26030103_IDUDecodeMinimizer.FromString(
          IDUDecodeMinimizerName
        ),
      MDUImpl = ysyx_26030103_MDUImpl.FromString(MDUImplName),
      MULImpl = ysyx_26030103_MULImpl.FromString(MULImplName),
      DIVImpl = ysyx_26030103_DIVImpl.FromString(DIVImplName),
      MDUProfile = MduProfile,
      ICacheEnable = ICacheEnable,
      ICacheStorage =
        ysyx_26030103_CacheStorage.FromString(ICacheStorageName),
      DCacheEnable = DCacheEnable,
      DCacheStorage =
        ysyx_26030103_CacheStorage.FromString(DCacheStorageName),
      ICacheCoherence =
        ysyx_26030103_ICacheCoherence.FromString(ICacheCoherenceName),
      DmaCoherence =
        ysyx_26030103_DmaCoherence.FromString(DmaCoherenceName),
      PMARegions = Pma.socRegions(
        HasChipLink,
        MmuProfile.plicBase,
        MmuProfile.plicSize
      ),
      ResetAddr = Pma.socResetAddr,
      CLINTBase = Pma.clintBase,
      CLINTSize = Pma.clintSize,
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
      DicArchCounterEnable = DicArchCounterEnable,
      PerfDicArchCounterBasic = Perf.basic,
      PerfDicArchCounterOpClass = Perf.opClass,
      PerfDicArchCounterMDU = Perf.mdu,
      PerfDicArchCounterIfuStall = Perf.ifuStall,
      PerfDicArchCounterICache = Perf.icache,
      PerfDicArchCounterDCache = Perf.dcache,
      PerfDicArchCounterExuStall = Perf.exuStall,
      PerfDicArchCounterLSU = Perf.lsu,
      PerfDicArchCounterIduStall = Perf.iduStall,
      PerfDicArchCounterTrap = Perf.trap,
      PerfDicArchCounterBranchPred = Perf.branchPred,
      PerfDicArchCounterMMU = Perf.mmu,
      PerfDicArchCounterFPU = Perf.fpu,
      PerfDicArchCounterAXI = Perf.axi,
      PerfDicArchCounterInstMix = Perf.instMix,
      PerfDicArchCounterCacheDetail = Perf.cacheDetail,
      PerfDicArchCounterInterrupt = Perf.interrupt,
      PerfDicArchCounterWBuf = Perf.wbuf,
      PerfDicArchCounterCSR = Perf.csr,
      PerfDicArchCounterMDUDetail = Perf.mduDetail,
      PerfDicArchCounterQueue = Perf.queue,
      RiscvArchPMU = RiscvArchPMU,
      RiscvArchPMUZihpm = RiscvArchPMUZihpm,
      RiscvArchPMUSscofpmf = RiscvArchPMUSscofpmf,
      PerfDicArchCounters = PerfCounters,
      MMU = MmuProfile
    )
    ysyx_26030103_ElaborateSettings(
      config = config,
      cachePadding = CachePadding,
      npcRamSize = NpcRamSize,
      hasChipLink = HasChipLink,
      pma = Pma
    )
  }
}
