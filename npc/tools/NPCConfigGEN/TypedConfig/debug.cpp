module npc.configgen;
import std;
namespace npc::configgen {
void addCsr(TypedFields &root, const Config &values)
{
    TypedFields csr;
    for (const auto &[key, value] : values) {
        if (!key.starts_with("CSR_"))
            continue;
        std::string name = key.substr(4);
        std::transform(name.begin(), name.end(), name.begin(),
                       [](unsigned char character) {
                           return static_cast<char>(std::tolower(character));
                       });
        if (key == "CSR_ENABLED" || key.ends_with("_READ") ||
            key.ends_with("_WRITE") || key.ends_with("_MODE")) {
            addField(csr, std::move(name),
                     TypedJson::string(flatString(values, key)));
        } else if (key.ends_with("_MASK") || key.ends_with("_SET")) {
            addField(csr, std::move(name),
                     TypedJson::integer(flatInt(values, key, 0)));
        } else {
            addField(csr, std::move(name),
                     TypedJson::boolean(flatBool(values, key)));
        }
    }
    addField(root, "csr", TypedJson::object(std::move(csr)));
}
void addInterrupt(TypedFields &root, const Config &values)
{
    TypedFields interrupt;
    addField(interrupt, "timer_path",
             TypedJson::string(
                 flatString(values, "TIMER_PATH", "clint_mtip")));
    addField(interrupt, "s_timer_irq",
             TypedJson::boolean(
                 flatBool(values, "HAS_S_TIMER_IRQ", true)));
    addField(interrupt, "s_external_irq",
             TypedJson::boolean(
                 flatBool(values, "HAS_S_EXTERNAL_IRQ", true)));
    TypedFields plic;
    addField(plic, "sources",
             TypedJson::integer(flatInt(values, "PLIC_SOURCES", 32)));
    addField(plic, "priority_bits",
             TypedJson::integer(
                 flatInt(values, "PLIC_PRIORITY_BITS", 3)));
    addField(plic, "max_priority",
             TypedJson::integer(
                 flatInt(values, "PLIC_MAX_PRIORITY", 7)));
    addField(plic, "base",
             TypedJson::integer(
                 flatInt(values, "PLIC_BASE", 0x0c000000LL)));
    addField(interrupt, "plic", TypedJson::object(std::move(plic)));
    addField(root, "interrupt", TypedJson::object(std::move(interrupt)));
}
void addDebug(TypedFields &root, const Config &values)
{
    TypedFields debug;
    addField(debug, "sdb", TypedJson::boolean(flatBool(values, "SDB")));
    addField(debug, "difftest",
             TypedJson::boolean(flatBool(values, "DIFFTEST")));
    addField(debug, "mtrace",
             TypedJson::boolean(flatBool(values, "MTRACE")));
    addField(debug, "itrace",
             TypedJson::boolean(flatBool(values, "ITRACE")));
    addField(debug, "itrace_write_file",
             TypedJson::boolean(flatBool(values, "ITRACE_WRITE_FILE")));
    addField(debug, "ftrace",
             TypedJson::boolean(flatBool(values, "FTRACE")));
    addField(debug, "iringbuf",
             TypedJson::boolean(flatBool(values, "IRINGBUF")));
    addField(debug, "dic_arch_counter",
             TypedJson::boolean(flatBool(values, "PERF_DIC_ARCH_COUNTER")));
    addField(debug, "riscv_arch_pmu",
             TypedJson::boolean(flatBool(values, "PERF_RISCV_ARCH_PMU", false)));
    addField(debug, "riscv_arch_pmu_zihpm",
             TypedJson::boolean(flatBool(values, "PERF_RISCV_ARCH_PMU_ZIHPM", true)));
    addField(debug, "riscv_arch_pmu_sscofpmf",
             TypedJson::boolean(flatBool(values, "PERF_RISCV_ARCH_PMU_SSCOFPMF", true)));
    addField(debug, "perf_save",
             TypedJson::boolean(flatBool(values, "PERF_SAVE")));
    addField(debug, "watchpoint",
             TypedJson::boolean(flatBool(values, "WATCHPOINT")));
    addField(debug, "watchpoint_num",
             TypedJson::integer(flatInt(values, "WATCHPOINT_NUM", 32)));
    TypedFields dicArchCounterGroupFields;
    for (const auto &group : dicArchCounterGroups()) {
        std::string name = group.name;
        std::transform(name.begin(), name.end(), name.begin(),
                       [](unsigned char character) {
                           return static_cast<char>(std::tolower(character));
                       });
        addField(dicArchCounterGroupFields, std::move(name),
                 TypedJson::boolean(flatBool(
                     values, "PERF_DIC_ARCH_COUNTER_" + std::string{group.name}, true)));
    }
    addField(debug, "dic_arch_counter_groups",
             TypedJson::object(std::move(dicArchCounterGroupFields)));
    TypedFields dicArchCounterFields;
    for (const auto &group : dicArchCounterGroups()) {
        for (const char *counter : group.counters) {
            std::string name = counter;
            std::transform(name.begin(), name.end(), name.begin(),
                           [](unsigned char character) {
                               return static_cast<char>(
                                   std::tolower(character));
                           });
            addField(dicArchCounterFields, std::move(name),
                     TypedJson::boolean(flatBool(
                         values, "PERF_DIC_ARCH_COUNTER_" + std::string{counter},
                         true)));
        }
    }
    addField(debug, "dic_arch_counter_events",
             TypedJson::object(std::move(dicArchCounterFields)));
    addField(root, "debug", TypedJson::object(std::move(debug)));
}
}
