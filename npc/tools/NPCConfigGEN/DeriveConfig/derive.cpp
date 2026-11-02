module npc.configgen;
import std;
namespace npc::configgen {
void deriveEffectiveConfig(std::map<std::string, std::string> &values)
{
    const long long xlen = asInt(values, "XLEN", 32);
    if (xlen != 32 && xlen != 64)
        throw std::runtime_error("XLEN 只允许 32/64");
    const bool followCpu = asBool(values, "SOC_XLEN_FOLLOW_CPU");
    const long long socXlen =
        followCpu ? xlen : asInt(values, "SOC_XLEN", 32);
    if (socXlen != 32 && socXlen != 64)
        throw std::runtime_error("SOC_XLEN 只允许 32/64");
    values["SOC_XLEN_EFFECTIVE"] = std::to_string(socXlen);
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
    const bool hasMmu = asBool(values, "HAS_MMU");
    const bool hasTlb = asBool(values, "HAS_TLB");
    values["MMU_ENABLED"] = hasMmu ? "1" : "0";
    values["MMU_TLB_ENABLED"] = (hasMmu && hasTlb) ? "1" : "0";
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
    setConfigDefault(values, "CSR_ENABLED", deriveCsrEnabled(values));
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
