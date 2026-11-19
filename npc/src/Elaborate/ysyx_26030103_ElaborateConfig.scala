import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.common.ysyx_26030103_PMAOptions
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
object ysyx_26030103_ElaborateConfig {
  def build(
      config: ysyx_26030103_JsonConfig.Config
  ): ysyx_26030103_ElaborateSettings = {
    val blockSizeLog2 = config.int("cache.block_size_log2", ysyx_26030103_KconfigDefaults.NpcBlockSizeLog2)
    val indexBits = config.int("cache.index_bits", ysyx_26030103_KconfigDefaults.NpcIndexBits)
    val iCacheBlockSizeLog2 =
      config.int("cache.ICache.block_size_log2", blockSizeLog2)
    val iCacheIndexBits = config.int("cache.ICache.index_bits", indexBits)
    val dCacheBlockSizeLog2 =
      config.int("cache.DCache.block_size_log2", blockSizeLog2)
    val dCacheIndexBits = config.int("cache.DCache.index_bits", indexBits)
    val iCacheEnable = config.bool("cache.ICache.enabled", true)
    val iCacheStorageName = config.string("cache.ICache.storage", "reg")
    val dCacheEnable = config.bool("cache.DCache.enabled", false)
    val dCacheStorageName = config.string("cache.DCache.storage", "reg")
    val iCacheCoherenceName =
      config.string("cache.ICache.coherence", "fence_i")
    val DMACoherenceName =
      config.string("cache.dma_coherence", "software_flush")
    val WBufDepth = config.int("cache.wbuf_depth", ysyx_26030103_KconfigDefaults.NpcWBufDepth)
    val BTBBits = config.int("frontend.btb_bits", ysyx_26030103_KconfigDefaults.NpcBTBBits)
    val BTBWays = config.int("frontend.btb_ways", ysyx_26030103_KconfigDefaults.NpcBTBWays)
    val JalBTBBits = config.int("frontend.jal_btb_bits", ysyx_26030103_KconfigDefaults.NpcJalBTBBits)
    val JalBTBWays = config.int("frontend.jal_btb_ways", ysyx_26030103_KconfigDefaults.NpcJalBTBWays)
    val RASBits = config.int("frontend.ras_bits", ysyx_26030103_KconfigDefaults.NpcRASBits)
    val cachePadding = config.int("platform.cache_padding", ysyx_26030103_KconfigDefaults.NpcCachePadding)
    val NpcRAMSize: Long = {
      val parsed = config.long(
        "platform.ram_size",
        ysyx_26030103_KconfigDefaults.NpcRamSize
      )
      require(parsed >= 4096L, s"NPC_RAM_SIZE=$parsed 太小")
      require(
        (parsed & (parsed - 1L)) == 0L,
        s"NPC_RAM_SIZE=$parsed 必须是 2 的幂"
      )
      require(parsed % 4L == 0L, s"NPC_RAM_SIZE=$parsed 必须是 4 字节的倍数")
      parsed
    }
    println(
      s"[NPC config] direct-NPC RAM: 0x${NpcRAMSize.toHexString}" +
        s" ($NpcRAMSize bytes)"
    )
    val NpcRAMBase: Long = {
      val parsed = config.long(
        "platform.ram_base",
        ysyx_26030103_KconfigDefaults.NpcRamBase
      )
      require(parsed >= 0 && parsed % 4L == 0L,
        s"NPC_RAM_BASE=0x${parsed.toHexString} 必须非负且按 4 字节对齐")
      require(
        BigInt(parsed) + BigInt(NpcRAMSize) <= (BigInt(1) << 32),
        s"NPC_RAM_BASE + NPC_RAM_SIZE 超出 32 位地址空间"
      )
      parsed
    }
    val hasChipLink = config.bool("platform.chip_link", false)
    val PMADefaults = ysyx_26030103_KconfigDefaults.Pma
    val PMA = ysyx_26030103_PMAOptions(
      CLINTBase = config.long("PMA.clint_base", PMADefaults.CLINTBase),
      CLINTSize = config.long("PMA.clint_size", PMADefaults.CLINTSize),
      sramBase = config.long("PMA.sram_base", PMADefaults.sramBase),
      sramSize = config.long("PMA.sram_size", PMADefaults.sramSize),
      UARTBase = config.long("PMA.uart_base", PMADefaults.UARTBase),
      UARTSize = config.long("PMA.uart_size", PMADefaults.UARTSize),
      SPIBase = config.long("PMA.spi_base", PMADefaults.SPIBase),
      SPISize = config.long("PMA.spi_size", PMADefaults.SPISize),
      GPIOBase = config.long("PMA.gpio_base", PMADefaults.GPIOBase),
      GPIOSize = config.long("PMA.gpio_size", PMADefaults.GPIOSize),
      keyboardBase = config.long("PMA.keyboard_base", PMADefaults.keyboardBase),
      keyboardSize = config.long("PMA.keyboard_size", PMADefaults.keyboardSize),
      VGABase = config.long("PMA.vga_base", PMADefaults.VGABase),
      VGASize = config.long("PMA.vga_size", PMADefaults.VGASize),
      mromBase = config.long("PMA.mrom_base", PMADefaults.mromBase),
      mromSize = config.long("PMA.mrom_size", PMADefaults.mromSize),
      psramBase = config.long("PMA.psram_base", PMADefaults.psramBase),
      psramSize = config.long("PMA.psram_size", PMADefaults.psramSize),
      sdramBase = config.long("PMA.sdram_base", PMADefaults.sdramBase),
      sdramSize = config.long("PMA.sdram_size", PMADefaults.sdramSize),
      ChipLinkMMIOBase =
        config.long("PMA.chiplink_mmio_base", PMADefaults.ChipLinkMMIOBase),
      ChipLinkMMIOSize =
        config.long("PMA.chiplink_mmio_size", PMADefaults.ChipLinkMMIOSize),
      chipLinkMemBase =
        config.long("PMA.chiplink_mem_base", PMADefaults.chipLinkMemBase),
      chipLinkMemSize =
        config.long("PMA.chiplink_mem_size", PMADefaults.chipLinkMemSize),
      SOCResetAddress = config.long("PMA.soc_reset_addr", PMADefaults.SOCResetAddress),
      NpcRAMBase = NpcRAMBase
    )
    val XLEN = config.int("isa.xlen", ysyx_26030103_KconfigDefaults.NpcXlen)
    val addressWidth =
      config.int("platform.address_width", if (XLEN == 64) 64 else 32)
    val SOCXLENFollowCPU = config.bool("isa.soc_xlen_follow_cpu", false)
    val SOCXLEN = config.int("isa.soc_xlen", ysyx_26030103_KconfigDefaults.NpcSocXlen)
    val internalBusWidthFollowCPU =
      config.bool("isa.internal_bus_width_follow_cpu", true)
    val internalBusWidth =
      config.int("isa.internal_bus_width", if (XLEN == 64) 64 else 32)
    val useM = config.bool("isa.m", false)
    val useA = config.bool("isa.a", false)
    val useC = config.bool("isa.c", false)
    val useF = config.bool("isa.f", false)
    val useD = config.bool("isa.d", false)
    val FPUImplName = config.string("fpu.impl", "none")
    val FPULength = config.int("fpu.flen", ysyx_26030103_KconfigDefaults.NpcFPULen)
    val ALUImplName = config.string("alu.impl", "standard")
    val IDUDecodeMinimizerName =
      config.string("idu.decode_minimizer", "qmc")
    val MDUImplName = config.string("MDU.impl", "standard")
    val mulImplName = config.string("MDU.mul_impl", "shift_add")
    val divImplName = config.string("MDU.div_impl", "restoring")
    val sdbEnable = config.bool("debug.sdb", false)
    val diffTestEnable = config.bool("debug.difftest", false)
    val MTraceEnable = config.bool("debug.mtrace", false)
    val ITraceEnable = config.bool("debug.itrace", false)
    val FTraceEnable = config.bool("debug.ftrace", false)
    val IringBufferEnable = config.bool("debug.iringbuf", false)
    val dicArchCounterEnable = config.bool("debug.dic_arch_counter", false)
    val riscvArchPMU = config.bool("debug.riscv_arch_pmu", false)
    val riscvArchPMUZihpm =
      config.bool("debug.riscv_arch_pmu_zihpm", true)
    val riscvArchPMUSscofpmf =
      config.bool("debug.riscv_arch_pmu_sscofpmf", true)
    val perf = ysyx_26030103_ElaborateProfiles.perfSwitches(config)
    val perfCounters = ysyx_26030103_ElaborateProfiles.perfCounters(
      config,
      dicArchCounterEnable
    )
    val MDUProfile = ysyx_26030103_ElaborateProfiles.MDUProfile(config)
    val MMUProfile =
      ysyx_26030103_ElaborateProfiles.MMUProfile(config, XLEN)
    val npcConfig = ysyx_26030103_NPCConfig(
      XLEN = XLEN,
      AddressWidth = addressWidth,
      SocXLENFollowCpu = SOCXLENFollowCPU,
      SocXLEN = SOCXLEN,
      InternalBusWidthFollowCpu = internalBusWidthFollowCPU,
      InternalBusWidth = internalBusWidth,
      UseM = useM,
      UseA = useA,
      UseC = useC,
      UseF = useF,
      UseD = useD,
      FPUImpl = ysyx_26030103_FPUImpl.FromString(FPUImplName),
      FPULen = FPULength,
      FPUProfile = ysyx_26030103_ElaborateProfiles.fpuProfile(config),
      ALUImpl = ysyx_26030103_ALUImpl.FromString(ALUImplName),
      ALUProfile = ysyx_26030103_ElaborateProfiles.aluProfile(config),
      IDUDecodeMinimizer =
        ysyx_26030103_IDUDecodeMinimizer.FromString(
          IDUDecodeMinimizerName
        ),
      MDUImpl = ysyx_26030103_MDUImpl.FromString(MDUImplName),
      MULImpl = ysyx_26030103_MULImpl.FromString(mulImplName),
      DIVImpl = ysyx_26030103_DIVImpl.FromString(divImplName),
      MDUProfile = MDUProfile,
      ICacheEnable = iCacheEnable,
      ICacheStorage =
        ysyx_26030103_CacheStorage.FromString(iCacheStorageName),
      DCacheEnable = dCacheEnable,
      DCacheStorage =
        ysyx_26030103_CacheStorage.FromString(dCacheStorageName),
      ICacheCoherence =
        ysyx_26030103_ICacheCoherence.FromString(iCacheCoherenceName),
      DMACoherence =
        ysyx_26030103_DMACoherence.FromString(DMACoherenceName),
      PMARegions = PMA.socRegions(
        hasChipLink,
        MMUProfile.PLICBase,
        MMUProfile.PLICSize
      ),
      ResetAddress = PMA.SOCResetAddress,
      CLINTBase = PMA.CLINTBase,
      CLINTSize = PMA.CLINTSize,
      ChipLinkMemoryBase = PMA.chipLinkMemBase,
      ChipLinkMemorySize = PMA.chipLinkMemSize,
      ICacheDrainTimeout =
        config.int("platform.icache_drain_timeout", ysyx_26030103_KconfigDefaults.ICacheDrainTimeout),
      DCacheDrainTimeout =
        config.int("platform.dcache_drain_timeout", ysyx_26030103_KconfigDefaults.DCacheDrainTimeout),
      LSUDrainTimeout =
        config.int("platform.lsu_drain_timeout", ysyx_26030103_KconfigDefaults.LSUDrainTimeout),
      ArbiterGrantTimeout =
        config.int("platform.arbiter_grant_timeout", ysyx_26030103_KconfigDefaults.ArbiterGrantTimeout),
      AXIArbCpuResponseQueueDepth =
        config.int("platform.axi_arb_cpu_resp_queue_depth",
          ysyx_26030103_KconfigDefaults.AXIArbCpuResponseQueueDepth),
      AXIArbDMAResponseQueueDepth =
        config.int("platform.axi_arb_dma_resp_queue_depth",
          ysyx_26030103_KconfigDefaults.AXIArbDMAResponseQueueDepth),
      AXIXbarResponseQueueDepth =
        config.int("platform.axi_xbar_resp_queue_depth",
          ysyx_26030103_KconfigDefaults.AXIXbarResponseQueueDepth),
      BlockSizeLog2 = blockSizeLog2,
      IndexBits = indexBits,
      ICacheBlockSizeLog2 = iCacheBlockSizeLog2,
      ICacheIndexBits = iCacheIndexBits,
      DCacheBlockSizeLog2 = dCacheBlockSizeLog2,
      DCacheIndexBits = dCacheIndexBits,
      WBufDepth = WBufDepth,
      BTBBits = BTBBits,
      BTBWays = BTBWays,
      JalBTBBits = JalBTBBits,
      JalBTBWays = JalBTBWays,
      RASBits = RASBits,
      SDBEnable = sdbEnable,
      DiffTestEnable = diffTestEnable,
      MTraceEnable = MTraceEnable,
      ITraceEnable = ITraceEnable,
      FTraceEnable = FTraceEnable,
      IRingBufEnable = IringBufferEnable,
      DicArchCounterEnable = dicArchCounterEnable,
      PerfDicArchCounterBasic = perf.basic,
      PerfDicArchCounterOpClass = perf.opClass,
      PerfDicArchCounterMDU = perf.MDU,
      PerfDicArchCounterIFUStall = perf.IFUStall,
      PerfDicArchCounterICache = perf.ICache,
      PerfDicArchCounterDCache = perf.DCache,
      PerfDicArchCounterEXUStall = perf.EXUStall,
      PerfDicArchCounterLSU = perf.LSU,
      PerfDicArchCounterIDUStall = perf.IDUStall,
      PerfDicArchCounterTrap = perf.trap,
      PerfDicArchCounterBranchPred = perf.branchPred,
      PerfDicArchCounterMMU = perf.mmu,
      PerfDicArchCounterFPU = perf.fpu,
      PerfDicArchCounterAXI = perf.AXI,
      PerfDicArchCounterInstMix = perf.instMix,
      PerfDicArchCounterCacheDetail = perf.cacheDetail,
      PerfDicArchCounterInterrupt = perf.interrupt,
      PerfDicArchCounterWBuf = perf.WBuf,
      PerfDicArchCounterCSR = perf.CSR,
      PerfDicArchCounterMDUDetail = perf.MDUDetail,
      PerfDicArchCounterQueue = perf.queue,
      RiscvArchPMU = riscvArchPMU,
      RiscvArchPMUZihpm = riscvArchPMUZihpm,
      RiscvArchPMUSscofpmf = riscvArchPMUSscofpmf,
      PerfDicArchCounters = perfCounters,
      MMU = MMUProfile
    )
    ysyx_26030103_ElaborateSettings(
      config = npcConfig,
      cachePadding = cachePadding,
      NpcRAMSize = NpcRAMSize,
      hasChipLink = hasChipLink,
      PMA = PMA
    )
  }
}
