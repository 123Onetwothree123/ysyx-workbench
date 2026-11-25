module npc.configgen;
import std;
namespace npc::configgen {
namespace {
std::string dicArchCounterName(const std::string &key)
{
    std::string result{"PerfDicArchCounter"};
    bool upper = true;
    for (const char character : key) {
        if (character == '_') {
            upper = true;
            continue;
        }
        const auto byte = static_cast<unsigned char>(character);
        result.push_back(static_cast<char>(
            upper ? std::toupper(byte) : std::tolower(byte)));
        upper = false;
    }
    return result;
}
}
std::string renderModule(const std::map<std::string, std::string> &values,
                         long long SOCXlen, const std::string &digest,
                         const std::string &perfBuildMetadata,
                         const std::string &perfSynth,
                         bool savable,
                         const std::string &pdkOverride,
                         const std::vector<std::string> &overrides)
{
    const long long xlen = asInt(values, "XLEN", 32);
    if (xlen != 32 && xlen != 64)
        throw std::runtime_error("XLEN 只允许 32/64");
    if (SOCXlen != 32 && SOCXlen != 64)
        throw std::runtime_error("SOC_XLEN 只允许 32/64");
    const bool hasM = asBool(values, "RISCV_M");
    const bool hasICache = asBool(values, "ICACHE");
    const bool hasDCache = asBool(values, "DCACHE");
    const bool hasPerf = asBool(values, "PERF_DIC_ARCH_COUNTER");
    const auto flag = [&](const char *key) {
        return asBool(values, key);
    };
    std::ostringstream out;
    out << "export module npc.config;\n\n";
    out << "import std;\n\n";
    out << "export namespace npc\n{\n";
    out << "inline constexpr unsigned XLen{" << xlen << "};\n";
    out << "inline constexpr unsigned SocXLen{" << SOCXlen << "};\n";
    out << "static_assert(XLen == 32 || XLen == 64, \"XLen 只允许 32/64\");\n";
    out << "static_assert(SocXLen == 32 || SocXLen == 64, \"SocXLen 只允许 32/64\");\n\n";
    out << "using XWord = std::conditional_t<XLen == 64, std::uint64_t, std::uint32_t>;\n";
    out << "using SocXWord =\n    std::conditional_t<SocXLen == 64, std::uint64_t, std::uint32_t>;\n\n";
    out << "inline constexpr int XHexDigits{static_cast<int>(XLen / 4U)};\n";
    out << "inline constexpr int SocXHexDigits{static_cast<int>(SocXLen / 4U)};\n\n";
    out << "template <typename T>\n[[nodiscard]] constexpr bool FitsXWord(T Value) noexcept\n{\n";
    out << "    if constexpr (std::is_signed_v<T>)\n    {\n";
    out << "        if (Value < 0)\n            return false;\n    }\n";
    out << "    using Unsigned = std::make_unsigned_t<T>;\n";
    out << "    return static_cast<Unsigned>(Value) <= static_cast<Unsigned>(std::numeric_limits<XWord>::max());\n";
    out << "}\n\n";
    const long long NpcRAMSize = asInt(values, "NPC_RAM_SIZE", 0x04000000);
    const long long NpcRAMBase = asInt(values, "NPC_RAM_BASE", 0x80000000LL);
    const long long cachePadding = asInt(values, "CACHE_PADDING", 0);
    const long long pmaMromBase = asInt(values, "PMA_MROM_BASE", 0x20000000LL);
    const long long pmaMromSize = asInt(values, "PMA_MROM_SIZE", 0x00001000LL);
    const long long pmaFlashBase = asInt(values, "PMA_FLASH_BASE", 0x30000000LL);
    const long long pmaFlashSize = asInt(values, "PMA_FLASH_SIZE", 0x10000000LL);
    const long long pmaSocResetAddr =
        asInt(values, "PMA_SOC_RESET_ADDR", 0x20000000LL);
    out << "inline constexpr std::uint64_t SocMBase{0x" << std::hex
        << pmaMromBase << "ULL};\n";
    out << "inline constexpr std::uint64_t SocMSize{0x" << std::hex
        << pmaMromSize << "ULL};\n";
    out << "inline constexpr std::uint64_t SocFlashBase{0x" << std::hex
        << pmaFlashBase << "ULL};\n";
    out << "inline constexpr std::uint64_t SocFlashSize{0x" << std::hex
        << pmaFlashSize << "ULL};\n";
    out << "inline constexpr std::uint64_t SocResetPC{0x" << std::hex
        << pmaSocResetAddr << "ULL};\n";
    out << std::dec;
    const auto pmaConst = [&](const char *name, const char *key,
                              long long fallback) {
        out << "inline constexpr std::uint64_t " << name << "{0x"
            << std::hex << asInt(values, key, fallback) << "ULL};\n";
        out << std::dec;
    };
    pmaConst("PmaCLINTBase", "PMA_CLINT_BASE", 0x02000000LL);
    pmaConst("PmaCLINTSize", "PMA_CLINT_SIZE", 0x00010000LL);
    pmaConst("PmaSramBase", "PMA_SRAM_BASE", 0x0f000000LL);
    pmaConst("PmaSramSize", "PMA_SRAM_SIZE", 0x00008000LL);
    pmaConst("PmaUartBase", "PMA_UART_BASE", 0x10000000LL);
    pmaConst("PmaUartSize", "PMA_UART_SIZE", 0x00001000LL);
    pmaConst("PmaSpiBase", "PMA_SPI_BASE", 0x10001000LL);
    pmaConst("PmaSpiSize", "PMA_SPI_SIZE", 0x00001000LL);
    pmaConst("PmaGpioBase", "PMA_GPIO_BASE", 0x10002000LL);
    pmaConst("PmaGpioSize", "PMA_GPIO_SIZE", 0x00000010LL);
    pmaConst("PmaKeyboardBase", "PMA_KEYBOARD_BASE", 0x10011000LL);
    pmaConst("PmaKeyboardSize", "PMA_KEYBOARD_SIZE", 0x00000008LL);
    pmaConst("PmaVgaBase", "PMA_VGA_BASE", 0x21000000LL);
    pmaConst("PmaVgaSize", "PMA_VGA_SIZE", 0x00200000LL);
    pmaConst("PmaMromBase", "PMA_MROM_BASE", 0x20000000LL);
    pmaConst("PmaMromSize", "PMA_MROM_SIZE", 0x00001000LL);
    pmaConst("PmaFlashBase", "PMA_FLASH_BASE", 0x30000000LL);
    pmaConst("PmaFlashSize", "PMA_FLASH_SIZE", 0x10000000LL);
    pmaConst("PmaPsramBase", "PMA_PSRAM_BASE", 0x80000000LL);
    pmaConst("PmaPsramSize", "PMA_PSRAM_SIZE", 0x00400000LL);
    pmaConst("PmaSdramBase", "PMA_SDRAM_BASE", 0xa0000000LL);
    pmaConst("PmaSdramSize", "PMA_SDRAM_SIZE", 0x02000000LL);
    pmaConst("PmaChipLinkMmioBase", "PMA_CHIPLINK_MMIO_BASE", 0x40000000LL);
    pmaConst("PmaChipLinkMmioSize", "PMA_CHIPLINK_MMIO_SIZE", 0x40000000LL);
    pmaConst("PmaChipLinkMemBase", "PMA_CHIPLINK_MEM_BASE", 0xc0000000LL);
    pmaConst("PmaChipLinkMemSize", "PMA_CHIPLINK_MEM_SIZE", 0x40000000LL);
    pmaConst("PmaSocResetAddr", "PMA_SOC_RESET_ADDR", 0x20000000LL);
    out << "inline constexpr std::uint64_t NpcMBase{0x" << std::hex
        << NpcRAMBase << "ULL};\n";
    out << "inline constexpr std::uint64_t NpcMSize{0x" << std::hex << NpcRAMSize
        << std::dec << "ULL};\n";
    out << "inline constexpr std::uint64_t NpcCachePadding{" << cachePadding << "ULL};\n";
    out << "inline constexpr std::uint64_t NpcResetPC{NpcMBase + NpcCachePadding};\n\n";
    out << "#ifdef VRISCV32E_NPC\n";
    out << "inline constexpr std::uint64_t MBase{NpcMBase};\n";
    out << "inline constexpr std::uint64_t MSize{NpcMSize};\n";
    out << "inline constexpr std::uint64_t ResetPC{NpcResetPC};\n";
    out << "inline constexpr std::uint64_t CachePadding{NpcCachePadding};\n";
    out << "#else\n";
    out << "inline constexpr std::uint64_t MBase{SocMBase};\n";
    out << "inline constexpr std::uint64_t MSize{SocMSize};\n";
    out << "inline constexpr std::uint64_t ResetPC{SocResetPC};\n";
    out << "inline constexpr std::uint64_t CachePadding{0ULL};\n";
    out << "#endif\n\n";
    const std::vector<std::pair<std::string, bool>> flags{
        {"HasM", hasM},
        {"HasA", flag("RISCV_A")},
        {"HasC", flag("RISCV_C")},
        {"HasF", flag("RISCV_F")},
        {"HasD", flag("RISCV_D")},
        {"HasS", flag("HAS_S")},
        {"HasU", flag("HAS_U")},
        {"HasMMU", flag("HAS_MMU")},
        {"HasTLB", flag("HAS_TLB")},
        {"HasICache", hasICache},
        {"HasDCache", hasDCache},
        {"HasSDB", flag("SDB")},
        {"HasDiffTest", flag("DIFFTEST")},
        {"HasMTrace", flag("MTRACE")},
        {"HasITrace", flag("ITRACE")},
        {"HasITraceWriteFile", flag("ITRACE_WRITE_FILE")},
        {"HasFTrace", flag("FTRACE")},
        {"HasIRingBuf", flag("IRINGBUF")},
        {"HasWatchpoint", flag("WATCHPOINT")},
        {"HasDicArchCounter", hasPerf},
        {"HasPerfSave", flag("PERF_SAVE")},
        {"HasTraceVCD", flag("TRACE_VCD")},
        {"HasTraceFST", flag("TRACE_FST")},
        {"HasNvBoard", flag("NVBOARD")},
        {"HasVerilatorSavable", savable},
        {"HasLogToFile", flag("LOG_TO_FILE")},
        {"HasLogTimestamp", flag("LOG_TIMESTAMP")},
        {"HasLogSourceLocation", flag("LOG_SOURCE_LOCATION")},
    };
    for (const auto &[name, value] : flags)
        out << "inline constexpr bool " << name << "{" << cppBool(value) << "};\n";
    out << "inline constexpr unsigned FPULen{" << asInt(values, "FPU_FLEN", 64) << "};\n\n";
    const std::string pdk = pdkOverride.empty()
                                ? asString(values, "PDK", "icsprout55")
                                : pdkOverride;
    out << "inline constexpr std::string_view PDK{" << cppString(pdk) << "};\n";
    out << "inline constexpr std::string_view TraceFile{"
        << cppString(asString(values, "TRACE_FILE", "waveform.vcd")) << "};\n";
    out << "inline constexpr std::size_t TraceDepth{"
        << asInt(values, "TRACE_DEPTH", 0) << "};\n";
    out << "inline constexpr std::string_view LogDir{"
        << cppString(asString(values, "LOG_DIR", "log")) << "};\n";
    out << "inline constexpr int LogLevel{" << asInt(values, "LOG_LEVEL", 3) << "};\n";
    out << "inline constexpr std::size_t IRingBufSize{"
        << asInt(values, "IRINGBUF_SIZE", 512) << "};\n";
    out << "inline constexpr std::size_t WatchpointNum{"
        << asInt(values, "WATCHPOINT_NUM", 32) << "};\n";
    out << "inline constexpr std::size_t NvBoardUpdateInterval{"
        << asInt(values, "NVBOARD_UPDATE_INTERVAL", 100) << "};\n";
    out << "inline constexpr std::string_view PerfBuildMetadataFile{"
        << cppString(perfBuildMetadata) << "};\n";
    out << "inline constexpr std::string_view PerfSynthFile{"
        << cppString(perfSynth) << "};\n\n";
    for (const auto &group : dicArchCounterGroups()) {
        const bool groupOn = asBool(values, "PERF_DIC_ARCH_COUNTER_" + std::string{group.name}, true);
        for (const char *counter : group.counters) {
            bool enabled = hasPerf && groupOn &&
                           asBool(values, "PERF_DIC_ARCH_COUNTER_" + std::string{counter}, true);
            if (std::string_view{group.name} == "MDU")
                enabled = enabled && hasM;
            else if (std::string_view{group.name} == "ICACHE")
                enabled = enabled && hasICache;
            else if (std::string_view{group.name} == "DCACHE")
                enabled = enabled && hasDCache;
            out << "inline constexpr bool " << dicArchCounterName(counter) << "{"
                << cppBool(enabled) << "};\n";
        }
    }
    out << "\ninline constexpr std::string_view ConfigHash{"
        << cppString(digest) << "};\n";
    out << "}\n";
    (void)overrides;
    return out.str();
}
std::string renderScalaDefaults(const std::map<std::string, std::string> &values)
{
    std::ostringstream out;
    out << "package ysyx_26030103.common\n\n";
    out << "// 由 make npc-config 生成，不要手改。\n";
    out << "object ysyx_26030103_KconfigDefaults {\n";
    const auto emitVal = [&](const char *name, const char *key,
                             long long fallback) {
        out << "  val " << name << ": Long = 0x" << std::hex
            << asInt(values, key, fallback) << "L\n";
        out << std::dec;
    };
    emitVal("PLICBase", "PLIC_BASE", 0x0c000000LL);
    emitVal("PLICSize", "PLIC_SIZE", 0x00300000LL);
    emitVal("NpcRamBase", "NPC_RAM_BASE", 0x80000000LL);
    emitVal("NpcRamSize", "NPC_RAM_SIZE", 0x04000000LL);
    out << "  val Pma: ysyx_26030103_PMAOptions = "
           "ysyx_26030103_PMAOptions(\n";
    const auto emitField = [&](const char *name, const char *key,
                               long long fallback) {
        out << "    " << name << " = 0x" << std::hex
            << asInt(values, key, fallback) << "L,\n";
        out << std::dec;
    };
    emitField("CLINTBase", "PMA_CLINT_BASE", 0x02000000LL);
    emitField("CLINTSize", "PMA_CLINT_SIZE", 0x00010000LL);
    emitField("sramBase", "PMA_SRAM_BASE", 0x0f000000LL);
    emitField("sramSize", "PMA_SRAM_SIZE", 0x00008000LL);
    emitField("UARTBase", "PMA_UART_BASE", 0x10000000LL);
    emitField("UARTSize", "PMA_UART_SIZE", 0x00001000LL);
    emitField("SPIBase", "PMA_SPI_BASE", 0x10001000LL);
    emitField("SPISize", "PMA_SPI_SIZE", 0x00001000LL);
    emitField("GPIOBase", "PMA_GPIO_BASE", 0x10002000LL);
    emitField("GPIOSize", "PMA_GPIO_SIZE", 0x00000010LL);
    emitField("keyboardBase", "PMA_KEYBOARD_BASE", 0x10011000LL);
    emitField("keyboardSize", "PMA_KEYBOARD_SIZE", 0x00000008LL);
    emitField("VGABase", "PMA_VGA_BASE", 0x21000000LL);
    emitField("VGASize", "PMA_VGA_SIZE", 0x00200000LL);
    emitField("mromBase", "PMA_MROM_BASE", 0x20000000LL);
    emitField("mromSize", "PMA_MROM_SIZE", 0x00001000LL);
    emitField("flashBase", "PMA_FLASH_BASE", 0x30000000LL);
    emitField("flashSize", "PMA_FLASH_SIZE", 0x10000000LL);
    emitField("psramBase", "PMA_PSRAM_BASE", 0x80000000LL);
    emitField("psramSize", "PMA_PSRAM_SIZE", 0x00400000LL);
    emitField("sdramBase", "PMA_SDRAM_BASE", 0xa0000000LL);
    emitField("sdramSize", "PMA_SDRAM_SIZE", 0x02000000LL);
    emitField("ChipLinkMMIOBase", "PMA_CHIPLINK_MMIO_BASE", 0x40000000LL);
    emitField("ChipLinkMMIOSize", "PMA_CHIPLINK_MMIO_SIZE", 0x40000000LL);
    emitField("chipLinkMemBase", "PMA_CHIPLINK_MEM_BASE", 0xc0000000LL);
    emitField("chipLinkMemSize", "PMA_CHIPLINK_MEM_SIZE", 0x40000000LL);
    emitField("SOCResetAddress", "PMA_SOC_RESET_ADDR", 0x20000000LL);
    out << "    NpcRAMBase = NpcRamBase\n";
    out << "  )\n";
    const auto emitInt = [&](const char *name, const char *key,
                             long long fallback) {
        out << "  val " << name << ": Int = "
            << asInt(values, key, fallback) << "\n";
    };
    emitInt("NpcXlen", "XLEN", 32);
    emitInt("NpcFPULen", "FPU_FLEN", 64);
    emitInt("NpcAddressWidth", "ADDRESS_WIDTH", 32);
    emitInt("NpcSocXlen", "SOC_XLEN_EFFECTIVE", 32);
    emitInt("NpcApbXlen", "APB_XLEN_EFFECTIVE", 32);
    emitInt("NpcInternalBusWidth", "INTERNAL_BUS_WIDTH_EFFECTIVE", 32);
    emitInt("NpcBlockSizeLog2", "CACHE_BLOCK_SIZE_LOG2", 4);
    emitInt("NpcIndexBits", "CACHE_INDEX_BITS", 5);
    emitInt("NpcICacheBlockSizeLog2", "ICACHE_BLOCK_SIZE_LOG2", 4);
    emitInt("NpcICacheIndexBits", "ICACHE_INDEX_BITS", 5);
    emitInt("NpcDCacheBlockSizeLog2", "DCACHE_BLOCK_SIZE_LOG2", 4);
    emitInt("NpcDCacheIndexBits", "DCACHE_INDEX_BITS", 5);
    emitInt("NpcWBufDepth", "WBUF_DEPTH", 4);
    const auto emitBoolInt = [&](const char *name, const char *key,
                                 bool fallback) {
        out << "  val " << name << ": Int = "
            << (asBool(values, key, fallback) ? 1 : 0) << "\n";
    };
    emitBoolInt("NpcMisalignedSupport", "MISALIGNED_SUPPORT", true);
    emitInt("NpcMisalignedGranuleBytes", "MISALIGNED_GRANULE_BYTES", 8);
    emitBoolInt("NpcMisalignedNCSplit", "MISALIGNED_NC_SPLIT", false);
    emitBoolInt("NpcMisalignedMMIOFault", "MISALIGNED_MMIO_FAULT", true);
    emitInt("NpcBTBBits", "BTB_BITS", 4);
    emitInt("NpcBTBWays", "BTB_WAYS", 1);
    emitInt("NpcJalBTBBits", "JAL_BTB_BITS", 4);
    emitInt("NpcJalBTBWays", "JAL_BTB_WAYS", 1);
    emitInt("NpcRASBits", "RAS_BITS", 4);
    emitInt("NpcCachePadding", "CACHE_PADDING", 0);

    const long long xlen = asInt(values, "XLEN", 32);
    const long long PTEBytes =
        asInt(values, "MMU_PTE_SIZE_BYTES", xlen == 64 ? 8 : 4);
    out << "  val MMUPTEBits: Int = " << PTEBytes * 8 << "\n";
    emitInt("MMUXlen", "MMU_XLEN", xlen);
    emitInt("MMUPTEValidBit", "MMU_PTE_VALID_BIT", 0);
    emitInt("MMUPTEReadBit", "MMU_PTE_READ_BIT", 1);
    emitInt("MMUPTEWriteBit", "MMU_PTE_WRITE_BIT", 2);
    emitInt("MMUPTEExecBit", "MMU_PTE_EXEC_BIT", 3);
    emitInt("MMUPTEUserBit", "MMU_PTE_USER_BIT", 4);
    emitInt("MMUPTEGlobalBit", "MMU_PTE_GLOBAL_BIT", 5);
    emitInt("MMUPTEABit", "MMU_PTE_A_BIT", 6);
    emitInt("MMUPTEDBit", "MMU_PTE_D_BIT", 7);
    emitInt("MMUPTEPPNLow", "MMU_PTE_PPN_LOW", 10);
    emitInt("MMUPTEPPNHigh", "MMU_PTE_PPN_HIGH",
            PTEBytes * 8 == 64 ? 53 : 31);
    emitInt("MMUCustomSatpMode", "MMU_CUSTOM_SATP_MODE", 10);
    emitInt("MMUPageSizeBytes", "MMU_PAGE_SIZE_BYTES", 4096);
    emitInt("MMULevels", "MMU_LEVELS", xlen == 64 ? 3 : 2);
    emitInt("MMUPTESizeBytes", "MMU_PTE_SIZE_BYTES", PTEBytes);
    emitInt("MMUPageTableSizeBytes", "MMU_PAGE_TABLE_SIZE_BYTES", 4096);
    emitInt("MMUCacheVIPTAliasScanWidth",
            "MMU_CACHE_VIPT_ALIAS_SCAN_WIDTH", 4);
    emitInt("MMUCacheVIPTAliasDirectoryEntries",
            "MMU_CACHE_VIPT_ALIAS_DIRECTORY_ENTRIES", 0);
    emitInt("MMUCacheVIPTAliasEventQueueDepth",
            "MMU_CACHE_VIPT_ALIAS_EVENT_QUEUE_DEPTH", 4);
    emitInt("MMUPTWOutstanding", "MMU_PTW_OUTSTANDING", 2);
    emitInt("MMUPTWBusOutstanding", "MMU_PTW_BUS_OUTSTANDING", 1);
    emitInt("MMUPTWPMPFaultQueueDepth",
            "MMU_PTW_PMP_FAULT_QUEUE_DEPTH", 8);
    emitInt("MMUPTWCacheEntries", "MMU_PTW_CACHE_ENTRIES", 16);
    emitInt("MMUPTWCacheWays", "MMU_PTW_CACHE_WAYS", 2);
    emitInt("MMUTLBASIDBits", "MMU_TLB_ASID_BITS", xlen == 64 ? 16 : 9);
    emitInt("MMUItlbEntries", "ITLB_ENTRIES", 16);
    emitInt("MMUItlbWays", "ITLB_WAYS", 2);
    emitInt("MMUDtlbEntries", "DTLB_ENTRIES", 32);
    emitInt("MMUDtlbWays", "DTLB_WAYS", 4);
    emitInt("MMUUtlbEntries", "UTLB_ENTRIES", 32);
    emitInt("MMUUtlbWays", "UTLB_WAYS", 4);
    emitInt("MMUUtlbLookupPorts", "UTLB_LOOKUP_PORTS", 1);
    emitInt("MMUTLBSuperpageEntries", "TLB_SUPERPAGE_ENTRIES", 4);
    emitInt("MMUItlbMissQueueEntries", "ITLB_MISS_QUEUE_ENTRIES", 4);
    emitInt("MMUDtlbMissQueueEntries", "DTLB_MISS_QUEUE_ENTRIES", 4);
    emitInt("MMUUtlbMissQueueEntries", "UTLB_MISS_QUEUE_ENTRIES", 8);
    emitInt("MMUGlobalMissQueueEntries", "GLOBAL_MISS_QUEUE_ENTRIES", 8);
    emitInt("MMURefillPorts", "TLB_REFILL_PORTS", 1);
    emitInt("MMUPLICSources", "PLIC_SOURCES", 32);
    emitInt("MMUPLICPriorityBits", "PLIC_PRIORITY_BITS", 3);
    emitInt("MMUPLICMaxPriority", "PLIC_MAX_PRIORITY", 7);
    emitInt("MMUPMPEntries", "PMP_ENTRIES", 16);
    emitInt("MMUPMPGranularity", "PMP_GRANULARITY_LOG2", 6);
    const long long pageSize = asInt(values, "MMU_PAGE_SIZE_BYTES", 4096);
    long long pageBits = 0;
    while ((1LL << pageBits) < pageSize) ++pageBits;
    const long long pageTableBytes =
        asInt(values, "MMU_PAGE_TABLE_SIZE_BYTES", 4096);
    const long long tableEntries = pageTableBytes / PTEBytes;
    long long indexBits = 0;
    while ((1LL << indexBits) < tableEntries) ++indexBits;
    const long long mmuLevels = asInt(values, "MMU_LEVELS", xlen == 64 ? 3 : 2);
    out << "  val MMUPageBits: Int = " << pageBits << "\n";
    out << "  val MMUTableEntries: Int = " << tableEntries << "\n";
    out << "  val MMUPTEWidth: Int = " << PTEBytes * 8 << "\n";
    out << "  val MMUIndexBits: Int = " << indexBits << "\n";
    out << "  val MMUVaBits: Int = " << pageBits + mmuLevels * indexBits << "\n";
    emitInt("MMUPTWBusDepth", "PTW_BUS_DEPTH", 16);
    emitInt("AXIIDWidth", "AXI_ID_WIDTH", 4);
    emitInt("AXIArbCpuResponseQueueDepth",
            "AXI_ARB_CPU_RESP_QUEUE_DEPTH", 16);
    emitInt("AXIArbDMAResponseQueueDepth",
            "AXI_ARB_DMA_RESP_QUEUE_DEPTH", 256);
    emitInt("AXIXbarResponseQueueDepth",
            "AXI_XBAR_RESP_QUEUE_DEPTH", 4);
    emitInt("UartFifoDepth", "UART_FIFO_DEPTH", 64);
    emitInt("UartAddressWidth", "UART_ADDR_WIDTH", 5);
    emitInt("UartTxHoldCycles", "UART_TX_HOLD_CYCLES", 32);
    emitInt("UartTxShiftCycles", "UART_TX_SHIFT_CYCLES", 64);

    emitInt("MDUMulShiftIterBits", "MUL_ITER_BITS", 1);
    emitInt("MDUMulShiftSplit", "MUL_SPLIT", 1);
    emitInt("MDUMulPipeline", "MUL_PIPELINE", 0);
    emitInt("MDUMulWidth", "MUL_WIDTH", 32);
    emitInt("MDUMulRadix", "MUL_RADIX", 4);
    emitInt("MDUDivRestoringRadix", "DIV_RESTORING_RADIX", 2);
    emitInt("MDUDivRestoringIterBits", "DIV_RESTORING_ITER_BITS", 1);
    emitInt("MDUDivNonRestoringRadix", "DIV_NONRESTORING_RADIX", 2);
    emitInt("MDUDivNonRestoringIterBits", "DIV_NONRESTORING_ITER_BITS", 1);
    emitInt("MDUDivSRTRadix", "DIV_SRT_RADIX", 2);
    emitInt("MDUDivSRTIterBits", "DIV_SRT_ITER_BITS", 1);
    emitInt("MDURocketMulUnroll", "MDU_ROCKET_MUL_UNROLL", 1);
    emitInt("MDURocketDivUnroll", "MDU_ROCKET_DIV_UNROLL", 1);
    emitInt("MDURocketDivEarlyOutGranularity",
            "MDU_ROCKET_DIV_EARLY_OUT_GRANULARITY", 1);
    emitInt("MDUGpcInputs", "MUL_COMPRESSOR_INPUTS", 3);
    emitInt("MDUGpcOutputs", "MUL_COMPRESSOR_OUTPUTS", 2);
    emitInt("MDUGpcCoutOffset", "MUL_COMPRESSOR_COUT_OFFSET", 1);
    const std::string offsetsText =
        asString(values, "MUL_COMPRESSOR_OUTPUT_OFFSETS", "0,1");
    out << "  val MDUGpcOutputOffsets: IndexedSeq[Int] = IndexedSeq(";
    bool firstOffset = true;
    std::size_t offsetStart = 0;
    while (offsetStart <= offsetsText.size()) {
        const auto comma = offsetsText.find(',', offsetStart);
        const auto item = offsetsText.substr(
            offsetStart, comma == std::string::npos ? std::string::npos
                                                    : comma - offsetStart);
        if (!item.empty()) {
            if (!firstOffset) out << ", ";
            out << std::stoll(item, nullptr, 0);
        }
        firstOffset = false;
        if (comma == std::string::npos) break;
        offsetStart = comma + 1;
    }
    out << ")\n";
    emitInt("FPUPipeline", "FPU_PIPELINE", 0);
    emitInt("ALUCarrySelectGroup", "ALU_CARRY_SELECT_GROUP", 8);
    emitInt("ICacheDrainTimeout", "ICACHE_DRAIN_TIMEOUT", 1023);
    emitInt("DCacheDrainTimeout", "DCACHE_DRAIN_TIMEOUT", 255);
    emitInt("LSUDrainTimeout", "LSU_DRAIN_TIMEOUT", 255);
    emitInt("ArbiterGrantTimeout", "ARBITER_GRANT_TIMEOUT", 1023);
    const auto fieldMasks = [&](const std::vector<std::pair<std::string,
                                                           unsigned long long>> &fields,
                                const std::string &prefix) {
        unsigned long long readMask = 0;
        unsigned long long readSet = 0;
        unsigned long long writeMask = 0;
        const std::string defaultRead =
            asString(values, "CSR_FIELD_DEFAULT_READ", "normal");
        const std::string defaultWrite =
            asString(values, "CSR_FIELD_DEFAULT_WRITE", "normal");
        for (const auto &[name, bits] : fields) {
            std::string upper;
            for (const char ch : name)
                upper.push_back(static_cast<char>(std::toupper(ch)));
            const std::string readMode = asString(
                values, prefix + "_" + upper + "_READ", defaultRead);
            const std::string writeMode = asString(
                values, prefix + "_" + upper + "_WRITE", defaultWrite);
            if (readMode == "normal") readMask |= bits;
            else if (readMode == "one") { readMask |= bits; readSet |= bits; }
            if (writeMode == "normal") writeMask |= bits;
        }
        return std::tuple{readMask, readSet, writeMask};
    };
    const std::vector<std::pair<std::string, unsigned long long>> mstatusFields{
        {"mie", 0x00000008ULL}, {"mpie", 0x00000080ULL},
        {"mpp", 0x00001800ULL}, {"sie", 0x00000002ULL},
        {"spie", 0x00000020ULL}, {"spp", 0x00000100ULL},
        {"mprv", 0x00020000ULL}, {"sum", 0x00040000ULL},
        {"mxr", 0x00080000ULL}, {"tvm", 0x00100000ULL},
        {"tw", 0x00200000ULL}, {"tsr", 0x00400000ULL}};
    const auto [mstatusRead, mstatusSet, mstatusWrite] =
        fieldMasks(mstatusFields, "CSR_MSTATUS");
    out << "  val MMUMstatusReadMask: Long = "
        << static_cast<long long>(mstatusRead) << "L\n";
    out << "  val MMUMstatusReadSet: Long = "
        << static_cast<long long>(mstatusSet) << "L\n";
    out << "  val MMUMstatusWriteMask: Long = "
        << static_cast<long long>(mstatusWrite) << "L\n";
    const std::vector<std::pair<std::string, unsigned long long>> satpFields =
        xlen == 64
            ? std::vector<std::pair<std::string, unsigned long long>>{
                  {"mode", 0xfULL << 60}, {"ASID", 0xffffULL << 44},
                  {"PPN", (1ULL << 44) - 1}}
            : std::vector<std::pair<std::string, unsigned long long>>{
                  {"mode", 0x80000000ULL}, {"ASID", 0x7fc00000ULL},
                  {"PPN", 0x003fffffULL}};
    const auto [satpRead, satpSet, satpWrite] =
        fieldMasks(satpFields, "CSR_SATP");
    out << "  val MMUSatpReadMask: Long = "
        << static_cast<long long>(satpRead) << "L\n";
    out << "  val MMUSatpReadSet: Long = "
        << static_cast<long long>(satpSet) << "L\n";
    out << "  val MMUSatpWriteMask: Long = "
        << static_cast<long long>(satpWrite) << "L\n";
    out << "}\n";
    return out.str();
}
}
