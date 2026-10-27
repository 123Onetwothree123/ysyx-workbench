module npc.configgen;
import std;
namespace npc::configgen {
bool hasConfigValue(const std::map<std::string, std::string> &values,
                    const std::string &key)
{
    const auto found = values.find(key);
    return found != values.end() && !found->second.empty();
}
void setConfigDefault(std::map<std::string, std::string> &values,
                      std::string key, std::string value)
{
    if (!hasConfigValue(values, key))
        values[std::move(key)] = std::move(value);
}
void copyConfigDefault(std::map<std::string, std::string> &values,
                       const std::string &target, const std::string &source)
{
    if (hasConfigValue(values, target))
        return;
    const auto found = values.find(source);
    if (found != values.end() && !found->second.empty())
        values[target] = found->second;
}
std::string deriveCsrEnabled(const std::map<std::string, std::string> &values)
{
    struct CsrName {
        const char *name;
        int address;
    };
    static const std::vector<CsrName> names{
        {"mstatus", 0x300},       {"misa", 0x301},
        {"mtvec", 0x305},         {"mepc", 0x341},
        {"mcause", 0x342},        {"mtval", 0x343},
        {"mscratch", 0x340},      {"mie", 0x304},
        {"mip", 0x344},           {"medeleg", 0x302},
        {"mideleg", 0x303},       {"mcounteren", 0x306},
        {"mcountinhibit", 0x320}, {"mcycle", 0xb00},
        {"minstret", 0xb02},      {"sstatus", 0x100},
        {"sie", 0x104},           {"sip", 0x144},
        {"stvec", 0x105},         {"sscratch", 0x140},
        {"sepc", 0x141},          {"scause", 0x142},
        {"stval", 0x143},         {"satp", 0x180},
        {"scounteren", 0x106},
    };
    static const std::vector<std::string> forcedCsrs{
        "mstatus", "mtvec", "mepc", "mcause", "mie", "mip",
        "medeleg", "mideleg", "sstatus", "sie", "sip", "stvec",
        "sscratch", "sepc", "scause", "stval", "satp", "scounteren",
    };
    const bool forceS = asBool(values, "HAS_MMU") || asBool(values, "HAS_S");
    std::vector<int> enabled;
    for (const auto &entry : names) {
        std::string upper{entry.name};
        std::transform(upper.begin(), upper.end(), upper.begin(),
                       [](unsigned char character) {
                           return static_cast<char>(std::toupper(character));
                       });
        const bool isForced =
            forceS &&
            std::find(forcedCsrs.begin(), forcedCsrs.end(), entry.name) !=
                forcedCsrs.end();
        if (asBool(values, "HAS_CSR_" + upper) || isForced)
            enabled.push_back(entry.address);
    }
    for (const int address : {0xf11, 0xf12, 0xf13, 0xf14}) {
        if (std::find(enabled.begin(), enabled.end(), address) == enabled.end())
            enabled.push_back(address);
    }
    std::ostringstream out;
    for (std::size_t index = 0; index < enabled.size(); ++index) {
        if (index != 0)
            out << ',';
        out << "0x" << std::hex << enabled[index];
    }
    return out.str();
}
}
