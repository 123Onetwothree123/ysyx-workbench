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
      config.int("cache.icache.block_size_log2", blockSizeLog2)
    val iCacheIndexBits = config.int("cache.icache.index_bits", indexBits)
    val dCacheBlockSizeLog2 =
      config.int("cache.dcache.block_size_log2", blockSizeLog2)
    val dCacheIndexBits = config.int("cache.dcache.index_bits", indexBits)
    val iCacheEnable = config.bool("cache.icache.enabled", true)
    val iCacheStorageName = config.string("cache.icache.storage", "reg")
    val dCacheEnable = config.bool("cache.dcache.enabled", false)
    val dCacheStorageName = config.string("cache.dcache.storage", "reg")
    val iCacheCoherenceName =
      config.string("cache.icache.coherence", "fence_i")
    val dmaCoherenceName =
      config.string("cache.dma_coherence", "software_flush")
    val wbufDepth = config.int("cache.wbuf_depth", ysyx_26030103_KconfigDefaults.NpcWBufDepth)
    val btbBits = config.int("frontend.btb_bits", ysyx_26030103_KconfigDefaults.NpcBTBBits)
    val btbWays = config.int("frontend.btb_ways", ysyx_26030103_KconfigDefaults.NpcBTBWays)
    val jalBTBBits = config.int("frontend.jal_btb_bits", ysyx_26030103_KconfigDefaults.NpcJalBTBBits)
    val jalBTBWays = config.int("frontend.jal_btb_ways", ysyx_26030103_KconfigDefaults.NpcJalBTBWays)
    val rasBits = config.int("frontend.ras_bits", ysyx_26030103_KconfigDefaults.NpcRASBits)
    val cachePadding = config.int("platform.cache_padding", ysyx_26030103_KconfigDefaults.NpcCachePadding)
    val npcRamSize: Long = {
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
      s"[NPC config] direct-NPC RAM: 0x${npcRamSize.toHexString}" +
        s" ($npcRamSize bytes)"
    )
    val npcRamBase: Long = {
      val parsed = config.long(
        "platform.ram_base",
        ysyx_26030103_KconfigDefaults.NpcRamBase
      )
      require(parsed >= 0 && parsed % 4L == 0L,
        s"NPC_RAM_BASE=0x${parsed.toHexString} 必须非负且按 4 字节对齐")
      require(
        BigInt(parsed) + BigInt(npcRamSize) <= (BigInt(1) << 32),
        s"NPC_RAM_BASE + NPC_RAM_SIZE 超出 32 位地址空间"
      )
      parsed
    }
    val hasChipLink = config.bool("platform.chip_link", false)
    val pmaDefaults = ysyx_26030103_KconfigDefaults.Pma
    val pma = ysyx_26030103_PMAOptions(
      clintBase = config.long("pma.clint_base", pmaDefaults.clintBase),
      clintSize = config.long("pma.clint_size", pmaDefaults.clintSize),
      sramBase = config.long("pma.sram_base", pmaDefaults.sramBase),
      sramSize = config.long("pma.sram_size", pmaDefaults.sramSize),
      uartBase = config.long("pma.uart_base", pmaDefaults.uartBase),
      uartSize = config.long("pma.uart_size", pmaDefaults.uartSize),
      spiBase = config.long("pma.spi_base", pmaDefaults.spiBase),
      spiSize = config.long("pma.spi_size", pmaDefaults.spiSize),
      gpioBase = config.long("pma.gpio_base", pmaDefaults.gpioBase),
      gpioSize = config.long("pma.gpio_size", pmaDefaults.gpioSize),
      keyboardBase = config.long("pma.keyboard_base", pmaDefaults.keyboardBase),
      keyboardSize = config.long("pma.keyboard_size", pmaDefaults.keyboardSize),
      vgaBase = config.long("pma.vga_base", pmaDefaults.vgaBase),
      vgaSize = config.long("pma.vga_size", pmaDefaults.vgaSize),
      mromBase = config.long("pma.mrom_base", pmaDefaults.mromBase),
      mromSize = config.long("pma.mrom_size", pmaDefaults.mromSize),
      psramBase = config.long("pma.psram_base", pmaDefaults.psramBase),
      psramSize = config.long("pma.psram_size", pmaDefaults.psramSize),
      sdramBase = config.long("pma.sdram_base", pmaDefaults.sdramBase),
      sdramSize = config.long("pma.sdram_size", pmaDefaults.sdramSize),
      chipLinkMmioBase =
        config.long("pma.chiplink_mmio_base", pmaDefaults.chipLinkMmioBase),
      chipLinkMmioSize =
        config.long("pma.chiplink_mmio_size", pmaDefaults.chipLinkMmioSize),
      chipLinkMemBase =
        config.long("pma.chiplink_mem_base", pmaDefaults.chipLinkMemBase),
      chipLinkMemSize =
        config.long("pma.chiplink_mem_size", pmaDefaults.chipLinkMemSize),
      socResetAddress = config.long("pma.soc_reset_addr", pmaDefaults.socResetAddress),
      npcRamBase = npcRamBase
    )
    val XLEN = config.int("isa.xlen", ysyx_26030103_KconfigDefaults.NpcXlen)
    val addressWidth =
      config.int("platform.address_width", if (XLEN == 64) 64 else 32)
    val socXLENFollowCPU = config.bool("isa.soc_xlen_follow_cpu", false)
    val socXLEN = config.int("isa.soc_xlen", ysyx_26030103_KconfigDefaults.NpcSocXlen)
    val internalBusWidthFollowCPU =
      config.bool("isa.internal_bus_width_follow_cpu", true)
    val internalBusWidth =
      config.int("isa.internal_bus_width", if (XLEN == 64) 64 else 32)
    val useM = config.bool("isa.m", false)
    val useA = config.bool("isa.a", false)
    val useC = config.bool("isa.c", false)
    val useF = config.bool("isa.f", false)
    val useD = config.bool("isa.d", false)
    val fpuImplName = config.string("fpu.impl", "none")
    val fpuLen = config.int("fpu.flen", ysyx_26030103_KconfigDefaults.NpcFPULen)
    val aluImplName = config.string("alu.impl", "standard")
    val iduDecodeMinimizerName =
      config.string("idu.decode_minimizer", "qmc")
    val mduImplName = config.string("mdu.impl", "standard")
    val mulImplName = config.string("mdu.mul_impl", "shift_add")
    val divImplName = config.string("mdu.div_impl", "restoring")
    val sdbEnable = config.bool("debug.sdb", false)
    val diffTestEnable = config.bool("debug.difftest", false)
    val mtraceEnable = config.bool("debug.mtrace", false)
    val itraceEnable = config.bool("debug.itrace", false)
    val ftraceEnable = config.bool("debug.ftrace", false)
    val iringBufEnable = config.bool("debug.iringbuf", false)
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
    val mduProfile = ysyx_26030103_ElaborateProfiles.mduProfile(config)
    val mmuProfile =
      ysyx_26030103_ElaborateProfiles.mmuProfile(config, XLEN)
    val npcConfig = ysyx_26030103_NPCConfig(
      XLEN = XLEN,
      AddressWidth = addressWidth,
      SocXLENFollowCpu = socXLENFollowCPU,
      SocXLEN = socXLEN,
      InternalBusWidthFollowCpu = internalBusWidthFollowCPU,
      InternalBusWidth = internalBusWidth,
      UseM = useM,
      UseA = useA,
      UseC = useC,
      UseF = useF,
      UseD = useD,
      FPUImpl = ysyx_26030103_FPUImpl.FromString(fpuImplName),
      FPULen = fpuLen,
      FPUProfile = ysyx_26030103_ElaborateProfiles.fpuProfile(config),
      ALUImpl = ysyx_26030103_ALUImpl.FromString(aluImplName),
      ALUProfile = ysyx_26030103_ElaborateProfiles.aluProfile(config),
      IDUDecodeMinimizer =
        ysyx_26030103_IDUDecodeMinimizer.FromString(
          iduDecodeMinimizerName
        ),
      MDUImpl = ysyx_26030103_MDUImpl.FromString(mduImplName),
      MULImpl = ysyx_26030103_MULImpl.FromString(mulImplName),
      DIVImpl = ysyx_26030103_DIVImpl.FromString(divImplName),
      MDUProfile = mduProfile,
      ICacheEnable = iCacheEnable,
      ICacheStorage =
        ysyx_26030103_CacheStorage.FromString(iCacheStorageName),
      DCacheEnable = dCacheEnable,
      DCacheStorage =
        ysyx_26030103_CacheStorage.FromString(dCacheStorageName),
      ICacheCoherence =
        ysyx_26030103_ICacheCoherence.FromString(iCacheCoherenceName),
      DMACoherence =
        ysyx_26030103_DMACoherence.FromString(dmaCoherenceName),
      PMARegions = pma.socRegions(
        hasChipLink,
        mmuProfile.plicBase,
        mmuProfile.plicSize
      ),
      ResetAddress = pma.socResetAddress,
      CLINTBase = pma.clintBase,
      CLINTSize = pma.clintSize,
      ChipLinkMemoryBase = pma.chipLinkMemBase,
      ChipLinkMemorySize = pma.chipLinkMemSize,
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
      WBufDepth = wbufDepth,
      BTBBits = btbBits,
      BTBWays = btbWays,
      JalBTBBits = jalBTBBits,
      JalBTBWays = jalBTBWays,
      RASBits = rasBits,
      SDBEnable = sdbEnable,
      DiffTestEnable = diffTestEnable,
      MTraceEnable = mtraceEnable,
      ITraceEnable = itraceEnable,
      FTraceEnable = ftraceEnable,
      IRingBufEnable = iringBufEnable,
      DicArchCounterEnable = dicArchCounterEnable,
      PerfDicArchCounterBasic = perf.basic,
      PerfDicArchCounterOpClass = perf.opClass,
      PerfDicArchCounterMDU = perf.mdu,
      PerfDicArchCounterIFUStall = perf.ifuStall,
      PerfDicArchCounterICache = perf.icache,
      PerfDicArchCounterDCache = perf.dcache,
      PerfDicArchCounterEXUStall = perf.exuStall,
      PerfDicArchCounterLSU = perf.lsu,
      PerfDicArchCounterIDUStall = perf.iduStall,
      PerfDicArchCounterTrap = perf.trap,
      PerfDicArchCounterBranchPred = perf.branchPred,
      PerfDicArchCounterMMU = perf.mmu,
      PerfDicArchCounterFPU = perf.fpu,
      PerfDicArchCounterAXI = perf.axi,
      PerfDicArchCounterInstMix = perf.instMix,
      PerfDicArchCounterCacheDetail = perf.cacheDetail,
      PerfDicArchCounterInterrupt = perf.interrupt,
      PerfDicArchCounterWBuf = perf.wbuf,
      PerfDicArchCounterCSR = perf.csr,
      PerfDicArchCounterMDUDetail = perf.mduDetail,
      PerfDicArchCounterQueue = perf.queue,
      RiscvArchPMU = riscvArchPMU,
      RiscvArchPMUZihpm = riscvArchPMUZihpm,
      RiscvArchPMUSscofpmf = riscvArchPMUSscofpmf,
      PerfDicArchCounters = perfCounters,
      MMU = mmuProfile
    )
    ysyx_26030103_ElaborateSettings(
      config = npcConfig,
      cachePadding = cachePadding,
      npcRamSize = npcRamSize,
      hasChipLink = hasChipLink,
      pma = pma
    )
  }
}
