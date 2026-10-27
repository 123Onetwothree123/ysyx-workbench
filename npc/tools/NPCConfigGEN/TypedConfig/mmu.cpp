module npc.configgen;
import std;
namespace npc::configgen {
void addMmu(TypedFields &root, const Config &values, long long xlen)
{
    const long long defaultPteBytes = xlen == 64 ? 8 : 4;
    const long long mmuPteBytes =
        flatInt(values, "MMU_PTE_SIZE_BYTES", defaultPteBytes);
    const long long defaultPpnHigh = mmuPteBytes * 8 == 64 ? 53 : 31;
    TypedFields mmu;
    addField(mmu, "enabled",
             TypedJson::boolean(flatBool(values, "MMU_ENABLED",
                                         flatBool(values, "HAS_MMU"))));
    addField(mmu, "xlen",
             TypedJson::integer(flatInt(values, "MMU_XLEN", xlen)));
    addField(mmu, "tlb_enabled",
             TypedJson::boolean(flatBool(values, "MMU_TLB_ENABLED")));
    addField(mmu, "geometry_mode",
             TypedJson::string(
                 flatString(values, "MMU_GEOMETRY_MODE", "standard")));
    addField(mmu, "custom_satp_mode",
             TypedJson::integer(
                 flatInt(values, "MMU_CUSTOM_SATP_MODE", 10)));
    addField(mmu, "page_size_bytes",
             TypedJson::integer(
                 flatInt(values, "MMU_PAGE_SIZE_BYTES", 4096)));
    addField(mmu, "levels",
             TypedJson::integer(flatInt(values, "MMU_LEVELS",
                                        xlen == 64 ? 3 : 2)));
    addField(mmu, "pte_size_bytes", TypedJson::integer(mmuPteBytes));
    addField(mmu, "page_table_size_bytes",
             TypedJson::integer(
                 flatInt(values, "MMU_PAGE_TABLE_SIZE_BYTES", 4096)));
    addField(mmu, "pte_format",
             TypedJson::string(flatString(
                 values, "MMU_PTE_FORMAT",
                 xlen == 64 ? "riscv64" : "riscv32")));
    addField(mmu, "pte_valid_bit",
             TypedJson::integer(
                 flatInt(values, "MMU_PTE_VALID_BIT", 0)));
    addField(mmu, "pte_read_bit",
             TypedJson::integer(
                 flatInt(values, "MMU_PTE_READ_BIT", 1)));
    addField(mmu, "pte_write_bit",
             TypedJson::integer(
                 flatInt(values, "MMU_PTE_WRITE_BIT", 2)));
    addField(mmu, "pte_exec_bit",
             TypedJson::integer(
                 flatInt(values, "MMU_PTE_EXEC_BIT", 3)));
    addField(mmu, "pte_user_bit",
             TypedJson::integer(
                 flatInt(values, "MMU_PTE_USER_BIT", 4)));
    addField(mmu, "pte_global_bit",
             TypedJson::integer(
                 flatInt(values, "MMU_PTE_GLOBAL_BIT", 5)));
    addField(mmu, "pte_a_bit",
             TypedJson::integer(flatInt(values, "MMU_PTE_A_BIT", 6)));
    addField(mmu, "pte_d_bit",
             TypedJson::integer(flatInt(values, "MMU_PTE_D_BIT", 7)));
    addField(mmu, "pte_ppn_low",
             TypedJson::integer(
                 flatInt(values, "MMU_PTE_PPN_LOW", 10)));
    addField(mmu, "pte_ppn_high",
             TypedJson::integer(flatInt(values, "MMU_PTE_PPN_HIGH",
                                        defaultPpnHigh)));
    addField(mmu, "superpage",
             TypedJson::boolean(flatBool(values, "MMU_SUPERPAGE")));
    addField(mmu, "ad_mode",
             TypedJson::string(flatString(values, "MMU_AD_MODE", "fault")));
    addField(mmu, "cache_mode",
             TypedJson::string(flatString(values, "MMU_CACHE_MODE", "pipt")));
    addField(mmu, "cache_vipt_alias_safe",
             TypedJson::boolean(
                 flatBool(values, "MMU_CACHE_VIPT_ALIAS_SAFE")));
    addField(mmu, "cache_vipt_alias_scan_mode",
             TypedJson::string(flatString(
                 values, "MMU_CACHE_VIPT_ALIAS_SCAN_MODE", "all_sets")));
    addField(mmu, "cache_vipt_alias_scan_width",
             TypedJson::integer(
                 flatInt(values, "MMU_CACHE_VIPT_ALIAS_SCAN_WIDTH", 4)));
    addField(mmu, "cache_vipt_alias_directory_entries",
             TypedJson::integer(flatInt(
                 values, "MMU_CACHE_VIPT_ALIAS_DIRECTORY_ENTRIES", 0)));
    addField(mmu, "ptw_path",
             TypedJson::string(
                 flatString(values, "MMU_PTW_PATH", "dedicated")));
    addField(mmu, "ptw_arch",
             TypedJson::string(
                 flatString(values, "MMU_PTW_ARCH", "independent")));
    addField(mmu, "ptw_outstanding",
             TypedJson::integer(
                 flatInt(values, "MMU_PTW_OUTSTANDING", 2)));
    addField(mmu, "ptw_bus_outstanding",
             TypedJson::integer(
                 flatInt(values, "MMU_PTW_BUS_OUTSTANDING", 1)));
    addField(mmu, "ptw_order",
             TypedJson::string(
                 flatString(values, "MMU_PTW_ORDER", "in_order")));
    addField(mmu, "ptw_cache",
             TypedJson::boolean(flatBool(values, "MMU_PTW_CACHE")));
    addField(mmu, "ptw_cache_entries",
             TypedJson::integer(
                 flatInt(values, "MMU_PTW_CACHE_ENTRIES", 16)));
    addField(mmu, "ptw_cache_ways",
             TypedJson::integer(
                 flatInt(values, "MMU_PTW_CACHE_WAYS", 2)));
    addField(mmu, "tlb_organization",
             TypedJson::string(
                 flatString(values, "MMU_TLB_ORGANIZATION", "split")));
    addField(mmu, "tlb_storage",
             TypedJson::string(
                 flatString(values, "MMU_TLB_STORAGE", "reg")));
    addField(mmu, "tlb_replacement",
             TypedJson::string(
                 flatString(values, "MMU_TLB_REPLACEMENT", "plru")));
    addField(mmu, "tlb_asid_bits",
             TypedJson::integer(flatInt(
                 values, "MMU_TLB_ASID_BITS", xlen == 64 ? 16 : 9)));
    addField(mmu, "tlb_global",
             TypedJson::boolean(flatBool(values, "MMU_TLB_GLOBAL", true)));
    addField(mmu, "itlb_entries",
             TypedJson::integer(
                 flatInt(values, "MMU_ITLB_ENTRIES", 16)));
    addField(mmu, "itlb_ways",
             TypedJson::integer(flatInt(values, "MMU_ITLB_WAYS", 2)));
    addField(mmu, "dtlb_entries",
             TypedJson::integer(
                 flatInt(values, "MMU_DTLB_ENTRIES", 32)));
    addField(mmu, "dtlb_ways",
             TypedJson::integer(flatInt(values, "MMU_DTLB_WAYS", 4)));
    addField(mmu, "utlb_entries",
             TypedJson::integer(
                 flatInt(values, "MMU_UTLB_ENTRIES", 32)));
    addField(mmu, "utlb_ways",
             TypedJson::integer(flatInt(values, "MMU_UTLB_WAYS", 4)));
    addField(mmu, "utlb_lookup_ports",
             TypedJson::integer(
                 flatInt(values, "UTLB_LOOKUP_PORTS", 1)));
    addField(mmu, "tlb_superpage",
             TypedJson::boolean(
                 flatBool(values, "MMU_TLB_SUPERPAGE")));
    addField(mmu, "tlb_superpage_entries",
             TypedJson::integer(
                 flatInt(values, "MMU_TLB_SUPERPAGE_ENTRIES", 4)));
    addField(mmu, "tlb_miss_queue_mode",
             TypedJson::string(flatString(
                 values, "MMU_TLB_MISS_QUEUE_MODE", "follow_tlb")));
    addField(mmu, "itlb_miss_queue_entries",
             TypedJson::integer(
                 flatInt(values, "MMU_ITLB_MISS_QUEUE_ENTRIES", 4)));
    addField(mmu, "dtlb_miss_queue_entries",
             TypedJson::integer(
                 flatInt(values, "MMU_DTLB_MISS_QUEUE_ENTRIES", 4)));
    addField(mmu, "utlb_miss_queue_entries",
             TypedJson::integer(
                 flatInt(values, "MMU_UTLB_MISS_QUEUE_ENTRIES", 8)));
    addField(mmu, "global_miss_queue_entries",
             TypedJson::integer(
                 flatInt(values, "MMU_GLOBAL_MISS_QUEUE_ENTRIES", 8)));
    addField(mmu, "tlb_refill_ports",
             TypedJson::integer(
                 flatInt(values, "MMU_TLB_REFILL_PORTS", 1)));
    addField(mmu, "pmp_enabled",
             TypedJson::boolean(flatBool(values, "HAS_PMP")));
    addField(mmu, "pmp_entries",
             TypedJson::integer(flatInt(values, "PMP_ENTRIES", 16)));
    TypedFields tlbKconfig;
    for (const auto &[key, value] : values) {
        const bool rawTlb =
            (key.starts_with("TLB_") || key.starts_with("ITLB_") ||
             key.starts_with("DTLB_") || key.starts_with("UTLB_")) &&
            !key.starts_with("MMU_");
        if (!rawTlb)
            continue;
        std::string name = key;
        std::transform(name.begin(), name.end(), name.begin(),
                       [](unsigned char character) {
                           return static_cast<char>(std::tolower(character));
                       });
        if (key.ends_with("_ENTRIES") || key.ends_with("_WAYS") ||
            key.ends_with("_BITS") || key.ends_with("_PORTS")) {
            addField(tlbKconfig, std::move(name),
                     TypedJson::integer(flatInt(values, key, 0)));
        } else if (key.ends_with("_ORGANIZATION") ||
                   key.ends_with("_STORAGE") ||
                   key.ends_with("_REPLACEMENT") ||
                   key.ends_with("_MODE")) {
            addField(tlbKconfig, std::move(name),
                     TypedJson::string(flatString(values, key)));
        } else {
            addField(tlbKconfig, std::move(name),
                     TypedJson::boolean(flatBool(values, key)));
        }
    }
    addField(mmu, "kconfig", TypedJson::object(std::move(tlbKconfig)));
    addField(root, "mmu", TypedJson::object(std::move(mmu)));
}
}
