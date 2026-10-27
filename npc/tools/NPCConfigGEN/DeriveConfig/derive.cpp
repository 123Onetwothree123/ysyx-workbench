module npc.configgen;
import std;
namespace npc::configgen {
void deriveEffectiveConfig(std::map<std::string, std::string> &values)
{
    const long long xlen = asInt(values, "XLEN", 32);
    if (xlen != 32 && xlen != 64)
        throw std::runtime_error("XLEN 只允许 32/64");
    setConfigDefault(values, "SOC_XLEN_FOLLOW_CPU", "n");
    setConfigDefault(values, "SOC_XLEN", "32");
    const bool followCpu = asBool(values, "SOC_XLEN_FOLLOW_CPU");
    const long long socXlen =
        followCpu ? xlen : asInt(values, "SOC_XLEN", 32);
    if (socXlen != 32 && socXlen != 64)
        throw std::runtime_error("SOC_XLEN 只允许 32/64");
    values["SOC_XLEN_EFFECTIVE"] = std::to_string(socXlen);
    setConfigDefault(values, "ADDRESS_WIDTH",
                     xlen == 64 ? "64" : "32");
    setConfigDefault(values, "SYNTH_FREQ", "450");
    setConfigDefault(values, "APB_DEVICE_FREQ", "100");
    setConfigDefault(values, "APB_DELAY_SCALE", "64");
    setConfigDefault(values, "AXI_DEVICE_FREQ", "100");
    setConfigDefault(values, "AXI_DELAY_SCALE", "64");
    values["APB_CPU_FREQ_MHZ"] = values["SYNTH_FREQ"];
    values["AXI_CPU_FREQ_MHZ"] = values["SYNTH_FREQ"];
    values["APB_DEVICE_FREQ_MHZ"] = values["APB_DEVICE_FREQ"];
    values["APB_DELAY_SCALE_FACTOR"] = values["APB_DELAY_SCALE"];
    values["AXI_DEVICE_FREQ_MHZ"] = values["AXI_DEVICE_FREQ"];
    values["AXI_DELAY_SCALE_FACTOR"] = values["AXI_DELAY_SCALE"];
    setConfigDefault(values, "NPC_RAM_SIZE", "0x04000000");
    setConfigDefault(values, "CACHE_PADDING", "0");
    setConfigDefault(values, "CACHE_BLOCK_SIZE_LOG2", "4");
    setConfigDefault(values, "CACHE_INDEX_BITS", "5");
    setConfigDefault(values, "ICACHE_BLOCK_SIZE_LOG2",
                     values["CACHE_BLOCK_SIZE_LOG2"]);
    setConfigDefault(values, "ICACHE_INDEX_BITS", values["CACHE_INDEX_BITS"]);
    setConfigDefault(values, "DCACHE_BLOCK_SIZE_LOG2",
                     values["CACHE_BLOCK_SIZE_LOG2"]);
    setConfigDefault(values, "DCACHE_INDEX_BITS", values["CACHE_INDEX_BITS"]);
    setConfigDefault(values, "WBUF_DEPTH", "4");
    setConfigDefault(values, "BTB_BITS", "4");
    setConfigDefault(values, "BTB_WAYS", "1");
    setConfigDefault(values, "JAL_BTB_BITS", "4");
    setConfigDefault(values, "JAL_BTB_WAYS", "1");
    setConfigDefault(values, "RAS_BITS", "4");
    if (asBool(values, "ITRACE") || asBool(values, "ITRACE_WRITE_FILE"))
        values["ITRACE"] = "y";
    else
        setConfigDefault(values, "ITRACE", "n");
    setConfigDefault(values, "CHIPLINK", "n");
    setConfigDefault(values, "SDRAM_USE_AXI", "y");
    setConfigDefault(values, "FPU_FUDIAN_SHARED_DIVSQRT", "y");
    setConfigDefault(values, "MUL_RADIX", "4");
    setConfigDefault(values, "MUL_COMPRESSOR_INPUTS", "3");
    setConfigDefault(values, "MUL_COMPRESSOR_OUTPUTS", "2");
    setConfigDefault(values, "MUL_COMPRESSOR_OUTPUT_OFFSETS", "0,1");
    setConfigDefault(values, "MUL_COMPRESSOR_HAS_CIN", "n");
    setConfigDefault(values, "MUL_COMPRESSOR_HAS_COUT", "n");
    setConfigDefault(values, "MUL_COMPRESSOR_COUT_OFFSET", "1");
    setConfigDefault(values, "MUL_COMPRESSOR_ALLOW_REDUNDANT", "n");
    setConfigDefault(values, "MUL_PIPELINE", "0");
    setConfigDefault(values, "PMP_ENTRIES", "16");
    const bool hasMmu = asBool(values, "HAS_MMU");
    const bool hasTlb = asBool(values, "HAS_TLB");
    values["MMU_ENABLED"] = hasMmu ? "1" : "0";
    values["MMU_TLB_ENABLED"] = (hasMmu && hasTlb) ? "1" : "0";
    values["MMU_XLEN"] = std::to_string(xlen);
    setConfigDefault(values, "MMU_GEOMETRY_MODE", "standard");
    setConfigDefault(values, "MMU_CUSTOM_SATP_MODE", "10");
    setConfigDefault(values, "MMU_PAGE_SIZE_BYTES", "4096");
    setConfigDefault(values, "MMU_LEVELS", xlen == 64 ? "3" : "2");
    setConfigDefault(values, "MMU_PTE_SIZE_BYTES", xlen == 64 ? "8" : "4");
    setConfigDefault(values, "MMU_PAGE_TABLE_SIZE_BYTES", "4096");
    setConfigDefault(values, "MMU_PTE_FORMAT",
                     xlen == 64 ? "riscv64" : "riscv32");
    const long long pteBytes = asInt(values, "MMU_PTE_SIZE_BYTES",
                                     xlen == 64 ? 8 : 4);
    const long long ppnHigh = pteBytes * 8 == 64 ? 53 : 31;
    setConfigDefault(values, "MMU_PTE_PPN_HIGH", std::to_string(ppnHigh));
    setConfigDefault(values, "MMU_PTE_VALID_BIT", "0");
    setConfigDefault(values, "MMU_PTE_READ_BIT", "1");
    setConfigDefault(values, "MMU_PTE_WRITE_BIT", "2");
    setConfigDefault(values, "MMU_PTE_EXEC_BIT", "3");
    setConfigDefault(values, "MMU_PTE_USER_BIT", "4");
    setConfigDefault(values, "MMU_PTE_GLOBAL_BIT", "5");
    setConfigDefault(values, "MMU_PTE_A_BIT", "6");
    setConfigDefault(values, "MMU_PTE_D_BIT", "7");
    setConfigDefault(values, "MMU_PTE_PPN_LOW", "10");
    setConfigDefault(values, "MMU_SUPERPAGE", "n");
    setConfigDefault(values, "MMU_AD_MODE", "fault");
    setConfigDefault(values, "MMU_CACHE_MODE", "pipt");
    setConfigDefault(values, "MMU_CACHE_VIPT_ALIAS_SAFE", "n");
    setConfigDefault(values, "MMU_CACHE_VIPT_ALIAS_SCAN_MODE", "all_sets");
    setConfigDefault(values, "MMU_CACHE_VIPT_ALIAS_SCAN_WIDTH", "4");
    setConfigDefault(values, "MMU_CACHE_VIPT_ALIAS_DIRECTORY_ENTRIES", "0");
    setConfigDefault(values, "MMU_PTW_PATH", "dedicated");
    setConfigDefault(values, "MMU_PTW_ARCH", "independent");
    setConfigDefault(values, "MMU_PTW_OUTSTANDING", "2");
    setConfigDefault(values, "MMU_PTW_BUS_OUTSTANDING", "1");
    setConfigDefault(values, "MMU_PTW_ORDER", "in_order");
    setConfigDefault(values, "MMU_PTW_CACHE", "n");
    setConfigDefault(values, "MMU_PTW_CACHE_ENTRIES", "16");
    setConfigDefault(values, "MMU_PTW_CACHE_WAYS", "2");
    copyConfigDefault(values, "MMU_TLB_ORGANIZATION", "TLB_ORGANIZATION");
    setConfigDefault(values, "MMU_TLB_ORGANIZATION", "split");
    copyConfigDefault(values, "MMU_TLB_STORAGE", "TLB_STORAGE");
    setConfigDefault(values, "MMU_TLB_STORAGE", "reg");
    copyConfigDefault(values, "MMU_TLB_REPLACEMENT", "TLB_REPLACEMENT");
    setConfigDefault(values, "MMU_TLB_REPLACEMENT", "plru");
    copyConfigDefault(values, "MMU_TLB_ASID_BITS", "TLB_ASID_BITS");
    setConfigDefault(values, "MMU_TLB_ASID_BITS", xlen == 64 ? "16" : "9");
    copyConfigDefault(values, "MMU_TLB_GLOBAL", "TLB_GLOBAL");
    setConfigDefault(values, "MMU_TLB_GLOBAL", "y");
    copyConfigDefault(values, "MMU_ITLB_ENTRIES", "ITLB_ENTRIES");
    setConfigDefault(values, "MMU_ITLB_ENTRIES", "16");
    copyConfigDefault(values, "MMU_ITLB_WAYS", "ITLB_WAYS");
    setConfigDefault(values, "MMU_ITLB_WAYS", "2");
    copyConfigDefault(values, "MMU_DTLB_ENTRIES", "DTLB_ENTRIES");
    setConfigDefault(values, "MMU_DTLB_ENTRIES", "32");
    copyConfigDefault(values, "MMU_DTLB_WAYS", "DTLB_WAYS");
    setConfigDefault(values, "MMU_DTLB_WAYS", "4");
    copyConfigDefault(values, "MMU_UTLB_ENTRIES", "UTLB_ENTRIES");
    setConfigDefault(values, "MMU_UTLB_ENTRIES", "32");
    copyConfigDefault(values, "MMU_UTLB_WAYS", "UTLB_WAYS");
    setConfigDefault(values, "MMU_UTLB_WAYS", "4");
    setConfigDefault(values, "UTLB_LOOKUP_PORTS", "2");
    copyConfigDefault(values, "MMU_TLB_SUPERPAGE", "TLB_SUPERPAGE");
    setConfigDefault(values, "MMU_TLB_SUPERPAGE", "n");
    copyConfigDefault(values, "MMU_TLB_SUPERPAGE_ENTRIES",
                      "TLB_SUPERPAGE_ENTRIES");
    setConfigDefault(values, "MMU_TLB_SUPERPAGE_ENTRIES", "4");
    copyConfigDefault(values, "MMU_TLB_MISS_QUEUE_MODE",
                      "TLB_MISS_QUEUE_MODE");
    setConfigDefault(values, "MMU_TLB_MISS_QUEUE_MODE", "follow_tlb");
    copyConfigDefault(values, "MMU_ITLB_MISS_QUEUE_ENTRIES",
                      "ITLB_MISS_QUEUE_ENTRIES");
    setConfigDefault(values, "MMU_ITLB_MISS_QUEUE_ENTRIES", "4");
    copyConfigDefault(values, "MMU_DTLB_MISS_QUEUE_ENTRIES",
                      "DTLB_MISS_QUEUE_ENTRIES");
    setConfigDefault(values, "MMU_DTLB_MISS_QUEUE_ENTRIES", "4");
    copyConfigDefault(values, "MMU_UTLB_MISS_QUEUE_ENTRIES",
                      "UTLB_MISS_QUEUE_ENTRIES");
    setConfigDefault(values, "MMU_UTLB_MISS_QUEUE_ENTRIES", "8");
    copyConfigDefault(values, "MMU_GLOBAL_MISS_QUEUE_ENTRIES",
                      "GLOBAL_MISS_QUEUE_ENTRIES");
    setConfigDefault(values, "MMU_GLOBAL_MISS_QUEUE_ENTRIES", "8");
    copyConfigDefault(values, "MMU_TLB_REFILL_PORTS", "TLB_REFILL_PORTS");
    setConfigDefault(values, "MMU_TLB_REFILL_PORTS", "1");
    setConfigDefault(values, "CSR_ENABLED", deriveCsrEnabled(values));
    for (const char *key : {"HAS_S", "HAS_U", "HAS_S_TIMER_IRQ",
                            "HAS_S_EXTERNAL_IRQ", "HAS_PMP"}) {
        if (hasConfigValue(values, key))
            values[key] = asBool(values, key) ? "1" : "0";
    }
    if (hasConfigValue(values, "PLIC_BASE")) {
        std::ostringstream base;
        base << "0x" << std::hex
             << asInt(values, "PLIC_BASE", 0x0c000000LL);
        values["PLIC_BASE"] = base.str();
    }
    for (const auto &group : perfGroups()) {
        setConfigDefault(values,
                         "PERF_COUNTER_" + std::string{group.name}, "y");
        for (const char *counter : group.counters)
            setConfigDefault(values,
                             "PERF_COUNTER_" + std::string{counter}, "y");
    }
}
}
