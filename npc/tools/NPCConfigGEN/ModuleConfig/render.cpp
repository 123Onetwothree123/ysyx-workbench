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
                         long long socXlen, const std::string &digest,
                         const std::string &perfBuildMetadata,
                         const std::string &perfSynth,
                         bool savable,
                         const std::string &pdkOverride,
                         const std::vector<std::string> &overrides)
{
    const long long xlen = asInt(values, "XLEN", 32);
    if (xlen != 32 && xlen != 64)
        throw std::runtime_error("XLEN 只允许 32/64");
    if (socXlen != 32 && socXlen != 64)
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
    out << "inline constexpr unsigned SocXLen{" << socXlen << "};\n";
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
    const long long npcRamSize = asInt(values, "NPC_RAM_SIZE", 0x04000000);
    const long long cachePadding = asInt(values, "CACHE_PADDING", 0);
    const long long pmaMromBase = asInt(values, "PMA_MROM_BASE", 0x30000000LL);
    const long long pmaMromSize = asInt(values, "PMA_MROM_SIZE", 0x10000000LL);
    const long long pmaSocResetAddr =
        asInt(values, "PMA_SOC_RESET_ADDR", 0x30000000LL);
    out << "inline constexpr std::uint64_t SocMBase{0x" << std::hex
        << pmaMromBase << "ULL};\n";
    out << "inline constexpr std::uint64_t SocMSize{0x" << std::hex
        << pmaMromSize << "ULL};\n";
    out << "inline constexpr std::uint64_t SocResetPC{0x" << std::hex
        << pmaSocResetAddr << "ULL};\n";
    out << std::dec;
    const auto pmaConst = [&](const char *name, const char *key,
                              long long fallback) {
        out << "inline constexpr std::uint64_t " << name << "{0x"
            << std::hex << asInt(values, key, fallback) << "ULL};\n";
        out << std::dec;
    };
    pmaConst("PmaClintBase", "PMA_CLINT_BASE", 0x02000000LL);
    pmaConst("PmaClintSize", "PMA_CLINT_SIZE", 0x00010000LL);
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
    pmaConst("PmaMromBase", "PMA_MROM_BASE", 0x30000000LL);
    pmaConst("PmaMromSize", "PMA_MROM_SIZE", 0x10000000LL);
    pmaConst("PmaPsramBase", "PMA_PSRAM_BASE", 0x80000000LL);
    pmaConst("PmaPsramSize", "PMA_PSRAM_SIZE", 0x00400000LL);
    pmaConst("PmaSdramBase", "PMA_SDRAM_BASE", 0xa0000000LL);
    pmaConst("PmaSdramSize", "PMA_SDRAM_SIZE", 0x02000000LL);
    pmaConst("PmaChipLinkMmioBase", "PMA_CHIPLINK_MMIO_BASE", 0x40000000LL);
    pmaConst("PmaChipLinkMmioSize", "PMA_CHIPLINK_MMIO_SIZE", 0x40000000LL);
    pmaConst("PmaChipLinkMemBase", "PMA_CHIPLINK_MEM_BASE", 0xc0000000LL);
    pmaConst("PmaChipLinkMemSize", "PMA_CHIPLINK_MEM_SIZE", 0x40000000LL);
    pmaConst("PmaSocResetAddr", "PMA_SOC_RESET_ADDR", 0x30000000LL);
    out << "inline constexpr std::uint64_t NpcMBase{0x80000000ULL};\n";
    out << "inline constexpr std::uint64_t NpcMSize{0x" << std::hex << npcRamSize
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
}
