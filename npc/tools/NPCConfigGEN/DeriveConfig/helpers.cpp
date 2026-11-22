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
std::string deriveCSREnabled(const std::map<std::string, std::string> &values)
{
    struct CSRName {
        const char *name;
        int address;
    };
    static const std::vector<CSRName> names{
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
    static const std::vector<std::string> forcedCSRs{
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
            std::find(forcedCSRs.begin(), forcedCSRs.end(), entry.name) !=
                forcedCSRs.end();
        if (asBool(values, "HAS_CSR_" + upper) || isForced)
            enabled.push_back(entry.address);
    }
    // RV32 的 mcycle/minstret 是 64 位计数器，高半字必须和低半字一起
    // 暴露；否则 csrr/csrw minstreth/mcycleh 会被当成非法 CSR。
    const auto xlenIt = values.find("XLEN");
    if (xlenIt != values.end() && std::stoi(xlenIt->second, nullptr, 0) == 32) {
        const auto enableHigh = [&enabled](int low, int high) {
            if (std::find(enabled.begin(), enabled.end(), low) != enabled.end() &&
                std::find(enabled.begin(), enabled.end(), high) == enabled.end())
                enabled.push_back(high);
        };
        enableHigh(0xb00, 0xb80);
        enableHigh(0xb02, 0xb82);
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
