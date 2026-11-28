module npc.configgen;
import std;
namespace npc::configgen {
// MMU profile 只通过 Kconfig 的 `default ... if MMU_PROFILE_*` 提供
// 默认值；这里不再按 profile 覆盖 .config。显式写入 .config 的值优先，
// 需要逐项手调时选择 MMU_PROFILE_CUSTOM。
void deriveEffectiveConfig(std::map<std::string, std::string> &values)
{
    const long long xlen = asInt(values, "XLEN", 32);
    if (xlen != 32 && xlen != 64)
        throw std::runtime_error("XLEN 只允许 32/64");
    const bool followCpu = asBool(values, "SOC_XLEN_FOLLOW_CPU");
    const long long SOCXlen =
        followCpu ? xlen : asInt(values, "SOC_XLEN", 32);
    if (SOCXlen != 32 && SOCXlen != 64)
        throw std::runtime_error("SOC_XLEN 只允许 32/64");
    values["SOC_XLEN_EFFECTIVE"] = std::to_string(SOCXlen);
    const bool apbFollow = asBool(values, "APB_XLEN_FOLLOW_SOC", true);
    const long long APBXlen =
        apbFollow ? SOCXlen : asInt(values, "APB_XLEN", SOCXlen);
    if (APBXlen != 32 && APBXlen != 64)
        throw std::runtime_error("APB_XLEN 只允许 32/64");
    values["APB_XLEN_EFFECTIVE"] = std::to_string(APBXlen);
    struct PeripheralXlen {
        const char *xlen;
        const char *follow;
    };
    static constexpr PeripheralXlen peripherals[] = {
        {"UART_XLEN", "UART_XLEN_FOLLOW_APB"},
        {"GPIO_XLEN", "GPIO_XLEN_FOLLOW_APB"},
        {"KEYBOARD_XLEN", "KEYBOARD_XLEN_FOLLOW_APB"},
        {"VGA_XLEN", "VGA_XLEN_FOLLOW_APB"},
        {"SPI_XLEN", "SPI_XLEN_FOLLOW_APB"},
        {"PSRAM_XLEN", "PSRAM_XLEN_FOLLOW_APB"},
        {"SDRAM_XLEN", "SDRAM_XLEN_FOLLOW_APB"},
        {"MROM_XLEN", "MROM_XLEN_FOLLOW_APB"},
        {"SRAM_XLEN", "SRAM_XLEN_FOLLOW_APB"},
    };
    for (const auto &peripheral : peripherals) {
        const bool follow = asBool(values, peripheral.follow, true);
        const long long width =
            follow ? APBXlen : asInt(values, peripheral.xlen, APBXlen);
        if (width != 32 && width != 64)
            throw std::runtime_error(std::string{peripheral.xlen} +
                                     " 只允许 32/64");
        values[std::string{peripheral.xlen} + "_EFFECTIVE"] =
            std::to_string(width);
    }
    const bool internalFollow =
        asBool(values, "INTERNAL_BUS_WIDTH_FOLLOW_CPU", true);
    const long long internalWidth =
        internalFollow ? xlen
                       : asInt(values, "INTERNAL_BUS_WIDTH",
                               xlen == 64 ? 64 : 32);
    if (internalWidth != 32 && internalWidth != 64)
        throw std::runtime_error("INTERNAL_BUS_WIDTH 只允许 32/64");
    values["INTERNAL_BUS_WIDTH_EFFECTIVE"] = std::to_string(internalWidth);
    if (asBool(values, "MISALIGNED_SUPPORT", true)) {
        const long long granule =
            asInt(values, "MISALIGNED_GRANULE_BYTES", 8);
        if (granule <= 0 || (granule & (granule - 1)) != 0)
            throw std::runtime_error(
                "MISALIGNED_GRANULE_BYTES 必须是 2 的幂");
        const long long configuredBus =
            asInt(values, "INTERNAL_BUS_WIDTH", internalWidth);
        (void)configuredBus;
        values["MISALIGNED_GRANULE_BYTES"] = std::to_string(granule);
    }
    setConfigDefault(values, "ADDRESS_WIDTH",
                     xlen == 64 ? "64" : "32");
    values["APB_CPU_FREQ_MHZ"] = values["SYNTH_FREQ"];
    values["AXI_CPU_FREQ_MHZ"] = values["SYNTH_FREQ"];
    values["APB_DEVICE_FREQ_MHZ"] = values["APB_DEVICE_FREQ"];
    values["APB_DELAY_SCALE_FACTOR"] = values["APB_DELAY_SCALE"];
    values["AXI_DEVICE_FREQ_MHZ"] = values["AXI_DEVICE_FREQ"];
    values["AXI_DELAY_SCALE_FACTOR"] = values["AXI_DELAY_SCALE"];
    if (asBool(values, "ITRACE") || asBool(values, "ITRACE_WRITE_FILE"))
        values["ITRACE"] = "y";
    const bool hasMMU = asBool(values, "HAS_MMU");
    const bool hasTLB = asBool(values, "HAS_TLB");
    values["MMU_ENABLED"] = hasMMU ? "1" : "0";
    values["MMU_TLB_ENABLED"] = (hasMMU && hasTLB) ? "1" : "0";
    values["MMU_XLEN"] = std::to_string(xlen);
    copyConfigDefault(values, "MMU_TLB_ORGANIZATION", "TLB_ORGANIZATION");
    copyConfigDefault(values, "MMU_TLB_STORAGE", "TLB_STORAGE");
    copyConfigDefault(values, "MMU_TLB_REPLACEMENT", "TLB_REPLACEMENT");
    copyConfigDefault(values, "MMU_TLB_ASID_BITS", "TLB_ASID_BITS");
    copyConfigDefault(values, "MMU_TLB_GLOBAL", "TLB_GLOBAL");
    copyConfigDefault(values, "MMU_ITLB_ENTRIES", "ITLB_ENTRIES");
    copyConfigDefault(values, "MMU_ITLB_WAYS", "ITLB_WAYS");
    copyConfigDefault(values, "MMU_DTLB_ENTRIES", "DTLB_ENTRIES");
    copyConfigDefault(values, "MMU_DTLB_WAYS", "DTLB_WAYS");
    copyConfigDefault(values, "MMU_UTLB_ENTRIES", "UTLB_ENTRIES");
    copyConfigDefault(values, "MMU_UTLB_WAYS", "UTLB_WAYS");
    copyConfigDefault(values, "MMU_TLB_SUPERPAGE", "TLB_SUPERPAGE");
    copyConfigDefault(values, "MMU_TLB_SUPERPAGE_ENTRIES",
                      "TLB_SUPERPAGE_ENTRIES");
    copyConfigDefault(values, "MMU_TLB_MISS_QUEUE_MODE",
                      "TLB_MISS_QUEUE_MODE");
    copyConfigDefault(values, "MMU_ITLB_MISS_QUEUE_ENTRIES",
                      "ITLB_MISS_QUEUE_ENTRIES");
    copyConfigDefault(values, "MMU_DTLB_MISS_QUEUE_ENTRIES",
                      "DTLB_MISS_QUEUE_ENTRIES");
    copyConfigDefault(values, "MMU_UTLB_MISS_QUEUE_ENTRIES",
                      "UTLB_MISS_QUEUE_ENTRIES");
    copyConfigDefault(values, "MMU_GLOBAL_MISS_QUEUE_ENTRIES",
                      "GLOBAL_MISS_QUEUE_ENTRIES");
    copyConfigDefault(values, "MMU_TLB_REFILL_PORTS", "TLB_REFILL_PORTS");
    setConfigDefault(values, "CSR_ENABLED", deriveCSREnabled(values));
    for (const char *key : {"HAS_S", "HAS_U", "HAS_S_TIMER_IRQ",
                            "HAS_S_EXTERNAL_IRQ", "HAS_PMP"}) {
        if (hasConfigValue(values, key))
            values[key] = asBool(values, key) ? "1" : "0";
    }
    // Cache 总开关与统一/分别配置：Kconfig 的隐藏项会保留旧值，
    // 因此在 effective 配置里显式归一化，保证 RTL 与 C++ 看到同一组参数。
    const bool cacheEnabled = asBool(values, "CACHE_ENABLE", true);
    if (!cacheEnabled) {
        values["ICACHE"] = "0";
        values["DCACHE"] = "0";
    } else if (asBool(values, "CACHE_CONFIG_UNIFIED", true)) {
        const std::string blockSize =
            values.contains("CACHE_BLOCK_SIZE_LOG2")
                ? values["CACHE_BLOCK_SIZE_LOG2"] : "4";
        const std::string indexBits =
            values.contains("CACHE_INDEX_BITS")
                ? values["CACHE_INDEX_BITS"] : "5";
        values["ICACHE_BLOCK_SIZE_LOG2"] = blockSize;
        values["DCACHE_BLOCK_SIZE_LOG2"] = blockSize;
        values["ICACHE_INDEX_BITS"] = indexBits;
        values["DCACHE_INDEX_BITS"] = indexBits;
    }

    for (const auto &[key, fallback] :
         std::initializer_list<std::pair<const char *, long long>>{
             {"PLIC_BASE", 0x0c000000LL},
             {"PLIC_SIZE", 0x00300000LL}}) {
        if (hasConfigValue(values, key)) {
            std::ostringstream formatted;
            formatted << "0x" << std::hex
                      << asInt(values, key, fallback);
            values[key] = formatted.str();
        }
    }
}
}
