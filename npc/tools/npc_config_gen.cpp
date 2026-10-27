// Build-time JSON -> C++ module generator for NPC.
//
// Kconfig's c_conf emits include/generated/npc_config.json.  This tool parses
// that flat JSON object, applies explicit command-line overrides, derives the
// remaining effective values (SoC XLEN, address width, MMU/TLB aliases, CSR
// masks, APB/AXI aliases, perf-counter defaults, ...), and emits
// csrc/generated/npc_config.ixx plus effective flat and nested typed JSON.
// Makefile only orchestrates the build and forwards explicit overrides.
// Runtime JSON parsing is intentionally not used because XLEN, cache geometry,
// array sizes and if constexpr switches must be known at C++ compile time.

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <system_error>
#include <vector>

namespace {

class JsonError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

class FlatJsonParser {
public:
    explicit FlatJsonParser(std::string_view text) : text_(text) {}

    std::map<std::string, std::string> parse()
    {
        std::map<std::string, std::string> values;
        skipWhitespace();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            ++position_;
            return values;
        }
        for (;;) {
            auto key = parseString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            auto value = parseString();
            values[std::move(key)] = std::move(value);
            skipWhitespace();
            if (peek() == ',') {
                ++position_;
                skipWhitespace();
                continue;
            }
            expect('}');
            break;
        }
        return values;
    }

private:
    char peek() const
    {
        return position_ < text_.size() ? text_[position_] : '\0';
    }

    void expect(char character)
    {
        if (peek() != character) {
            throw JsonError(std::string{"JSON 解析失败：期望 '"} + character +
                            "'，实际位置 " + std::to_string(position_));
        }
        ++position_;
    }

    void skipWhitespace()
    {
        while (position_ < text_.size() &&
               std::isspace(static_cast<unsigned char>(text_[position_]))) {
            ++position_;
        }
    }

    static int hexValue(char character)
    {
        if (character >= '0' && character <= '9')
            return character - '0';
        if (character >= 'a' && character <= 'f')
            return character - 'a' + 10;
        if (character >= 'A' && character <= 'F')
            return character - 'A' + 10;
        return -1;
    }

    void appendUtf8(std::string &out, unsigned codepoint)
    {
        if (codepoint <= 0x7f) {
            out.push_back(static_cast<char>(codepoint));
        } else if (codepoint <= 0x7ff) {
            out.push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
            out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
        } else {
            out.push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
            out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
            out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
        }
    }

    std::string parseString()
    {
        expect('"');
        std::string result;
        while (position_ < text_.size()) {
            const char character = text_[position_++];
            if (character == '"')
                return result;
            if (character != '\\') {
                result.push_back(character);
                continue;
            }
            if (position_ >= text_.size())
                break;
            const char escaped = text_[position_++];
            switch (escaped) {
            case '"': result.push_back('"'); break;
            case '\\': result.push_back('\\'); break;
            case '/': result.push_back('/'); break;
            case 'b': result.push_back('\b'); break;
            case 'f': result.push_back('\f'); break;
            case 'n': result.push_back('\n'); break;
            case 'r': result.push_back('\r'); break;
            case 't': result.push_back('\t'); break;
            case 'u': {
                unsigned codepoint = 0;
                for (int index = 0; index < 4; ++index) {
                    if (position_ >= text_.size())
                        throw JsonError("JSON Unicode 转义不完整");
                    const int digit = hexValue(text_[position_++]);
                    if (digit < 0)
                        throw JsonError("JSON Unicode 转义包含非法十六进制字符");
                    codepoint = (codepoint << 4) | static_cast<unsigned>(digit);
                }
                appendUtf8(result, codepoint);
                break;
            }
            default:
                throw JsonError("JSON 包含未知转义字符");
            }
        }
        throw JsonError("JSON 字符串未闭合");
    }

    std::string_view text_;
    std::size_t position_{0};
};

bool asBool(const std::map<std::string, std::string> &values,
            const std::string &key, bool fallback = false)
{
    const auto found = values.find(key);
    if (found == values.end())
        return fallback;
    const auto &value = found->second;
    return value == "y" || value == "1" || value == "true";
}

long long asInt(const std::map<std::string, std::string> &values,
                const std::string &key, long long fallback)
{
    const auto found = values.find(key);
    if (found == values.end() || found->second.empty())
        return fallback;
    errno = 0;
    char *end = nullptr;
    const long long parsed = std::strtoll(found->second.c_str(), &end, 0);
    if (errno != 0 || end == nullptr || *end != '\0')
        throw std::runtime_error("配置项 " + key + " 不是整数: " + found->second);
    return parsed;
}

std::string asString(const std::map<std::string, std::string> &values,
                     const std::string &key, const std::string &fallback = "")
{
    const auto found = values.find(key);
    return found == values.end() ? fallback : found->second;
}

std::string cppBool(bool value)
{
    return value ? "true" : "false";
}

std::string cppString(const std::string &value)
{
    std::string result{"\""};
    for (const char character : value) {
        switch (character) {
        case '\\': result += "\\\\"; break;
        case '"': result += "\\\""; break;
        case '\n': result += "\\n"; break;
        case '\r': result += "\\r"; break;
        case '\t': result += "\\t"; break;
        default: result.push_back(character); break;
        }
    }
    result.push_back('"');
    return result;
}

std::string jsonEscape(const std::string &value)
{
    std::string result;
    result.reserve(value.size());
    for (const unsigned char character : value) {
        switch (character) {
        case '"': result += "\\\""; break;
        case '\\': result += "\\\\"; break;
        case '\b': result += "\\b"; break;
        case '\f': result += "\\f"; break;
        case '\n': result += "\\n"; break;
        case '\r': result += "\\r"; break;
        case '\t': result += "\\t"; break;
        default:
            if (character < 0x20) {
                char buffer[7];
                std::snprintf(buffer, sizeof(buffer), "\\u%04x", character);
                result += buffer;
            } else {
                result.push_back(static_cast<char>(character));
            }
            break;
        }
    }
    return result;
}

std::string renderEffectiveJson(const std::map<std::string, std::string> &values)
{
    std::ostringstream out;
    out << "{\n";
    bool first = true;
    for (const auto &[key, value] : values) {
        if (!first)
            out << ",\n";
        first = false;
        out << "  \"" << jsonEscape(key) << "\": \"" << jsonEscape(value)
            << "\"";
    }
    out << "\n}\n";
    return out.str();
}

struct PerfGroup {
    const char *name;
    std::vector<const char *> counters;
};

const std::vector<PerfGroup> &perfGroups()
{
    static const std::vector<PerfGroup> groups{
        {"BASIC", {"IFU_FETCH", "EXU_DONE", "LSU_LOAD", "LSU_STORE"}},
        {"OPCLASS", {"ALU_OP", "MEM_OP", "CSR_OP", "BRANCH_OP", "JAL_OP",
                     "JALR_OP", "EXECUTION_ACTIVE"}},
        {"MDU", {"MDU_REQ", "MDU_DONE", "MDU_OP", "MDU_ACTIVE", "MDU_WAIT"}},
        {"IFU_STALL", {"IFU_STALL_PIPELINE", "IFU_STALL_AXI", "IFU_STALL_AR",
                       "IFU_STALL_R", "IFU_STALL_REDIRECT", "IFU_STALL_IDLE"}},
        {"ICACHE", {"ICACHE_HIT", "ICACHE_MISS"}},
        {"DCACHE", {"DCACHE_HIT", "DCACHE_MISS", "DCACHE_REFILL_REQ",
                    "DCACHE_REFILL_RESP"}},
        {"EXU_STALL", {"EXU_STALL_LSU", "EXU_IDLE_NOINPUT", "MEM_WAITSLOT"}},
        {"LSU", {"LSU_ACTIVE", "LSU_LOAD_ACTIVE", "LSU_STORE_ACTIVE",
                 "LSU_STALL_READ_AR", "LSU_STALL_READ_R",
                 "LSU_STALL_WRITE_REQ", "LSU_STALL_WRITE_B"}},
        {"IDU_STALL", {"IDU_STALL_RAW", "IDU_STALL_RAW_LOADUSE",
                       "IDU_STALL_RAW_ALU"}},
        {"TRAP", {"TRAP_COUNT"}},
    };
    return groups;
}

// ---------------------------------------------------------------------------
// Effective configuration derivation
//
// Kconfig owns user choices. This C++ layer owns values that are derived from
// those choices (effective SoC XLEN, address width, APB/AXI aliases, TLB
// aliases, CSR masks, perf-counter defaults, ...). Makefile only forwards
// explicit command-line overrides and build paths; it must not carry a second
// derivation implementation.
// ---------------------------------------------------------------------------

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
    // Identity CSRs are always implemented; they are not part of the
    // trimable MMU feature set.
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

    // These symbols may be absent from older .config files even though the
    // typed projection and RTL defaults already assume them.
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

    // Keep the legacy effective-JSON representation for interrupt/PMP
    // switches.  Kconfig spells these as y/n; the old MMU profile projection
    // used 1/0 and downstream scripts historically consumed that spelling.
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

// ---------------------------------------------------------------------------
// Typed JSON projection
// ---------------------------------------------------------------------------

struct TypedJson {
    enum class Kind { Null, Boolean, Integer, String, Object, Array };

    Kind kind{Kind::Null};
    bool booleanValue{false};
    long long integerValue{0};
    std::string stringValue;
    std::vector<std::pair<std::string, TypedJson>> objectValue;
    std::vector<TypedJson> arrayValue;

    static TypedJson null()
    {
        return TypedJson{};
    }

    static TypedJson boolean(bool value)
    {
        TypedJson result;
        result.kind = Kind::Boolean;
        result.booleanValue = value;
        return result;
    }

    static TypedJson integer(long long value)
    {
        TypedJson result;
        result.kind = Kind::Integer;
        result.integerValue = value;
        return result;
    }

    static TypedJson string(std::string value)
    {
        TypedJson result;
        result.kind = Kind::String;
        result.stringValue = std::move(value);
        return result;
    }

    static TypedJson object(
        std::vector<std::pair<std::string, TypedJson>> fields)
    {
        TypedJson result;
        result.kind = Kind::Object;
        result.objectValue = std::move(fields);
        return result;
    }

    static TypedJson array(std::vector<TypedJson> items)
    {
        TypedJson result;
        result.kind = Kind::Array;
        result.arrayValue = std::move(items);
        return result;
    }

    void write(std::ostream &out, int indent) const
    {
        switch (kind) {
        case Kind::Null:
            out << "null";
            break;
        case Kind::Boolean:
            out << (booleanValue ? "true" : "false");
            break;
        case Kind::Integer:
            out << integerValue;
            break;
        case Kind::String:
            out << '"' << jsonEscape(stringValue) << '"';
            break;
        case Kind::Object:
            if (objectValue.empty()) {
                out << "{}";
                break;
            }
            out << "{\n";
            for (std::size_t index = 0; index < objectValue.size(); ++index) {
                const auto &[key, value] = objectValue[index];
                out << std::string(static_cast<std::size_t>(indent + 2), ' ')
                    << '"' << jsonEscape(key) << "\": ";
                value.write(out, indent + 2);
                if (index + 1 != objectValue.size())
                    out << ',';
                out << '\n';
            }
            out << std::string(static_cast<std::size_t>(indent), ' ') << '}';
            break;
        case Kind::Array:
            if (arrayValue.empty()) {
                out << "[]";
                break;
            }
            out << "[\n";
            for (std::size_t index = 0; index < arrayValue.size(); ++index) {
                out << std::string(static_cast<std::size_t>(indent + 2), ' ');
                arrayValue[index].write(out, indent + 2);
                if (index + 1 != arrayValue.size())
                    out << ',';
                out << '\n';
            }
            out << std::string(static_cast<std::size_t>(indent), ' ') << ']';
            break;
        }
    }
};

using TypedFields = std::vector<std::pair<std::string, TypedJson>>;

void addField(TypedFields &fields, std::string key, TypedJson value)
{
    fields.emplace_back(std::move(key), std::move(value));
}

bool flatBool(const std::map<std::string, std::string> &values,
              const std::string &key, bool fallback = false)
{
    return asBool(values, key, fallback);
}

long long flatInt(const std::map<std::string, std::string> &values,
                  const std::string &key, long long fallback)
{
    return asInt(values, key, fallback);
}

std::string flatString(const std::map<std::string, std::string> &values,
                       const std::string &key,
                       const std::string &fallback = "")
{
    return asString(values, key, fallback);
}

std::vector<long long> parseIntList(const std::string &key,
                                    const std::string &text)
{
    std::vector<long long> result;
    std::size_t start = 0;
    while (start <= text.size()) {
        const auto separator = text.find(',', start);
        std::string item = text.substr(
            start, separator == std::string::npos ? std::string::npos
                                                  : separator - start);
        const auto first =
            item.find_first_not_of(" \t\r\n");
        const auto last =
            item.find_last_not_of(" \t\r\n");
        if (first == std::string::npos) {
            item.clear();
        } else {
            item = item.substr(first, last - first + 1);
        }
        if (!item.empty()) {
            errno = 0;
            char *end = nullptr;
            const long long parsed = std::strtoll(item.c_str(), &end, 0);
            if (errno != 0 || end == nullptr || *end != '\0')
                throw std::runtime_error("配置项 " + key +
                                         " 包含非法整数: " + item);
            result.push_back(parsed);
        }
        if (separator == std::string::npos)
            break;
        start = separator + 1;
    }
    return result;
}

std::string renderTypedJson(const std::map<std::string, std::string> &values,
                            const std::string &digest,
                            const std::string &pdkOverride,
                            long long socXlen)
{
    const long long xlen = flatInt(values, "XLEN", 32);
    if (xlen != 32 && xlen != 64)
        throw std::runtime_error("XLEN 只允许 32/64");
    if (socXlen != 32 && socXlen != 64)
        throw std::runtime_error("SOC_XLEN 只允许 32/64");
    const bool hasS = flatBool(values, "HAS_S", true);
    const bool hasU = flatBool(values, "HAS_U", hasS);
    const long long addressWidth =
        flatInt(values, "ADDRESS_WIDTH", xlen == 64 ? 64 : 32);
    const long long defaultPteBytes = xlen == 64 ? 8 : 4;
    const long long mmuPteBytes =
        flatInt(values, "MMU_PTE_SIZE_BYTES", defaultPteBytes);
    const long long defaultPpnHigh = mmuPteBytes * 8 == 64 ? 53 : 31;

    TypedFields root;
    addField(root, "schema", TypedJson::integer(1));
    addField(root, "config_hash", TypedJson::string(digest));

    TypedFields isa;
    addField(isa, "xlen", TypedJson::integer(xlen));
    addField(isa, "soc_xlen", TypedJson::integer(socXlen));
    addField(isa, "soc_xlen_follow_cpu",
             TypedJson::boolean(flatBool(values, "SOC_XLEN_FOLLOW_CPU")));
    addField(isa, "m", TypedJson::boolean(flatBool(values, "RISCV_M")));
    addField(isa, "a", TypedJson::boolean(flatBool(values, "RISCV_A")));
    addField(isa, "c", TypedJson::boolean(flatBool(values, "RISCV_C")));
    addField(isa, "f", TypedJson::boolean(flatBool(values, "RISCV_F")));
    addField(isa, "d", TypedJson::boolean(flatBool(values, "RISCV_D")));
    addField(isa, "s", TypedJson::boolean(hasS));
    addField(isa, "u", TypedJson::boolean(hasU));
    addField(root, "isa", TypedJson::object(std::move(isa)));

    TypedFields fpu;
    addField(fpu, "impl",
             TypedJson::string(flatString(values, "FPU_IMPL", "none")));
    addField(fpu, "flen", TypedJson::integer(flatInt(values, "FPU_FLEN", 64)));
    addField(fpu, "pipeline",
             TypedJson::integer(flatInt(values, "FPU_PIPELINE", 0)));
    addField(fpu, "fma", TypedJson::boolean(flatBool(values, "FPU_FMA", true)));
    addField(fpu, "divsqrt",
             TypedJson::boolean(flatBool(values, "FPU_DIVSQRT", true)));
    addField(fpu, "rounding",
             TypedJson::boolean(flatBool(values, "FPU_ROUNDING", true)));
    addField(fpu, "flags",
             TypedJson::boolean(flatBool(values, "FPU_FLAGS", true)));
    TypedFields hardfloat;
    addField(hardfloat, "recode",
             TypedJson::boolean(
                 flatBool(values, "FPU_HARDFLOAT_RECODE", true)));
    addField(hardfloat, "tininess_before_rounding",
             TypedJson::boolean(flatBool(
                 values, "FPU_HARDFLOAT_TININESS_BEFORE_ROUNDING", false)));
    addField(fpu, "hardfloat", TypedJson::object(std::move(hardfloat)));
    TypedFields fudian;
    addField(fudian, "shared_divsqrt",
             TypedJson::boolean(
                 flatBool(values, "FPU_FUDIAN_SHARED_DIVSQRT", true)));
    addField(fpu, "fudian", TypedJson::object(std::move(fudian)));
    addField(root, "fpu", TypedJson::object(std::move(fpu)));

    TypedFields mdu;
    addField(mdu, "mul_impl",
             TypedJson::string(
                 flatString(values, "MUL_IMPL", "shift_add")));
    addField(mdu, "mul_encoding",
             TypedJson::string(flatString(values, "MUL_ENCODING", "plain")));
    addField(mdu, "div_impl",
             TypedJson::string(flatString(values, "DIV_IMPL", "restoring")));
    addField(mdu, "mul_radix",
             TypedJson::integer(flatInt(values, "MUL_RADIX", 4)));
    addField(mdu, "mul_width",
             TypedJson::integer(flatInt(values, "MUL_WIDTH", 32)));
    TypedFields compressor;
    addField(compressor, "inputs",
             TypedJson::integer(
                 flatInt(values, "MUL_COMPRESSOR_INPUTS", 3)));
    addField(compressor, "outputs",
             TypedJson::integer(
                 flatInt(values, "MUL_COMPRESSOR_OUTPUTS", 2)));
    {
        std::vector<TypedJson> offsets;
        for (const long long offset : parseIntList(
                 "MUL_COMPRESSOR_OUTPUT_OFFSETS",
                 flatString(values, "MUL_COMPRESSOR_OUTPUT_OFFSETS", "0,1")))
            offsets.push_back(TypedJson::integer(offset));
        addField(compressor, "output_offsets",
                 TypedJson::array(std::move(offsets)));
    }
    addField(compressor, "has_cin",
             TypedJson::boolean(
                 flatBool(values, "MUL_COMPRESSOR_HAS_CIN")));
    addField(compressor, "has_cout",
             TypedJson::boolean(
                 flatBool(values, "MUL_COMPRESSOR_HAS_COUT")));
    addField(compressor, "cout_offset",
             TypedJson::integer(
                 flatInt(values, "MUL_COMPRESSOR_COUT_OFFSET", 1)));
    addField(compressor, "allow_redundant",
             TypedJson::boolean(
                 flatBool(values, "MUL_COMPRESSOR_ALLOW_REDUNDANT")));
    addField(mdu, "mul_compressor",
             TypedJson::object(std::move(compressor)));
    addField(mdu, "div_radix",
             TypedJson::integer(flatInt(values, "DIV_RADIX", 2)));
    addField(mdu, "div_iter_bits",
             TypedJson::integer(flatInt(values, "DIV_ITER_BITS", 1)));
    addField(mdu, "div_early_out",
             TypedJson::boolean(flatBool(values, "DIV_EARLY_OUT")));
    addField(mdu, "mul_iter_bits",
             TypedJson::integer(flatInt(values, "MUL_ITER_BITS", 1)));
    addField(mdu, "mul_pipeline",
             TypedJson::integer(flatInt(values, "MUL_PIPELINE", 0)));
    addField(mdu, "mul_split",
             TypedJson::integer(flatInt(values, "MUL_SPLIT", 1)));
    addField(mdu, "mul_early_out",
             TypedJson::boolean(flatBool(values, "MUL_EARLY_OUT")));
    addField(root, "mdu", TypedJson::object(std::move(mdu)));

    const long long blockSizeLog2 =
        flatInt(values, "CACHE_BLOCK_SIZE_LOG2", 4);
    const long long indexBits = flatInt(values, "CACHE_INDEX_BITS", 5);
    TypedFields cache;
    addField(cache, "block_size_log2", TypedJson::integer(blockSizeLog2));
    addField(cache, "index_bits", TypedJson::integer(indexBits));
    addField(cache, "wbuf_depth",
             TypedJson::integer(flatInt(values, "WBUF_DEPTH", 4)));
    TypedFields icache;
    addField(icache, "enabled",
             TypedJson::boolean(flatBool(values, "ICACHE", true)));
    addField(icache, "block_size_log2",
             TypedJson::integer(flatInt(values, "ICACHE_BLOCK_SIZE_LOG2",
                                        blockSizeLog2)));
    addField(icache, "index_bits",
             TypedJson::integer(
                 flatInt(values, "ICACHE_INDEX_BITS", indexBits)));
    addField(icache, "storage",
             TypedJson::string(flatString(values, "ICACHE_STORAGE", "reg")));
    addField(icache, "coherence",
             TypedJson::string(
                 flatString(values, "ICACHE_COHERENCE", "fence_i")));
    addField(cache, "icache", TypedJson::object(std::move(icache)));
    TypedFields dcache;
    addField(dcache, "enabled",
             TypedJson::boolean(flatBool(values, "DCACHE", false)));
    addField(dcache, "block_size_log2",
             TypedJson::integer(flatInt(values, "DCACHE_BLOCK_SIZE_LOG2",
                                        blockSizeLog2)));
    addField(dcache, "index_bits",
             TypedJson::integer(
                 flatInt(values, "DCACHE_INDEX_BITS", indexBits)));
    addField(dcache, "storage",
             TypedJson::string(flatString(values, "DCACHE_STORAGE", "reg")));
    addField(cache, "dcache", TypedJson::object(std::move(dcache)));
    addField(cache, "dma_coherence",
             TypedJson::string(
                 flatString(values, "DMA_COHERENCE", "software_flush")));
    addField(root, "cache", TypedJson::object(std::move(cache)));

    TypedFields frontend;
    addField(frontend, "btb_bits",
             TypedJson::integer(flatInt(values, "BTB_BITS", 4)));
    addField(frontend, "btb_ways",
             TypedJson::integer(flatInt(values, "BTB_WAYS", 1)));
    addField(frontend, "jal_btb_bits",
             TypedJson::integer(flatInt(values, "JAL_BTB_BITS", 4)));
    addField(frontend, "jal_btb_ways",
             TypedJson::integer(flatInt(values, "JAL_BTB_WAYS", 1)));
    addField(frontend, "ras_bits",
             TypedJson::integer(flatInt(values, "RAS_BITS", 4)));
    addField(root, "frontend", TypedJson::object(std::move(frontend)));

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

    // Kconfig 的原始 TLB_* 选择项也保留在 typed JSON 中，便于后续工具
    // 区分"用户选择"与 Makefile 派生的 MMU_* 有效值。
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
    addField(debug, "perf_stats",
             TypedJson::boolean(flatBool(values, "PERF_STATS")));
    addField(debug, "perf_save",
             TypedJson::boolean(flatBool(values, "PERF_SAVE")));
    addField(debug, "watchpoint",
             TypedJson::boolean(flatBool(values, "WATCHPOINT")));
    addField(debug, "watchpoint_num",
             TypedJson::integer(flatInt(values, "WATCHPOINT_NUM", 32)));
    TypedFields perfGroupFields;
    for (const auto &group : perfGroups()) {
        std::string name = group.name;
        std::transform(name.begin(), name.end(), name.begin(),
                       [](unsigned char character) {
                           return static_cast<char>(std::tolower(character));
                       });
        addField(perfGroupFields, std::move(name),
                 TypedJson::boolean(flatBool(
                     values, "PERF_COUNTER_" + std::string{group.name}, true)));
    }
    addField(debug, "perf_groups",
             TypedJson::object(std::move(perfGroupFields)));
    TypedFields perfCounterFields;
    for (const auto &group : perfGroups()) {
        for (const char *counter : group.counters) {
            std::string name = counter;
            std::transform(name.begin(), name.end(), name.begin(),
                           [](unsigned char character) {
                               return static_cast<char>(
                                   std::tolower(character));
                           });
            addField(perfCounterFields, std::move(name),
                     TypedJson::boolean(flatBool(
                         values, "PERF_COUNTER_" + std::string{counter},
                         true)));
        }
    }
    addField(debug, "perf_counters",
             TypedJson::object(std::move(perfCounterFields)));
    addField(root, "debug", TypedJson::object(std::move(debug)));

    TypedFields platform;
    addField(platform, "ram_size",
             TypedJson::integer(
                 flatInt(values, "NPC_RAM_SIZE", 0x04000000LL)));
    addField(platform, "cache_padding",
             TypedJson::integer(flatInt(values, "CACHE_PADDING", 0)));
    addField(platform, "chip_link",
             TypedJson::boolean(flatBool(values, "CHIPLINK")));
    addField(platform, "address_width", TypedJson::integer(addressWidth));
    addField(platform, "soc_xlen", TypedJson::integer(socXlen));
    addField(root, "platform", TypedJson::object(std::move(platform)));

    // SoC 边界配置与 CPU 配置来自同一次 Kconfig/Makefile 合并结果。
    // ysyxSoC 的 Scala elaboration 只读取这一块，不再读取 CHIPLINK/
    // SOC_XLEN/APB_*/AXI_* 环境变量。
    TypedFields soc;
    addField(soc, "cpu_xlen", TypedJson::integer(xlen));
    addField(soc, "soc_xlen", TypedJson::integer(socXlen));
    addField(soc, "soc_xlen_follow_cpu",
             TypedJson::boolean(flatBool(values, "SOC_XLEN_FOLLOW_CPU")));
    addField(soc, "chiplink",
             TypedJson::boolean(flatBool(values, "CHIPLINK")));
    addField(soc, "sdram_use_axi",
             TypedJson::boolean(flatBool(values, "SDRAM_USE_AXI", true)));
    addField(soc, "internal_xlen", TypedJson::integer(32));
    addField(soc, "internal_id_bits", TypedJson::integer(4));

    const long long synthFreq = flatInt(values, "SYNTH_FREQ", 450);
    TypedFields apb;
    addField(apb, "cpu_freq_mhz", TypedJson::integer(synthFreq));
    addField(apb, "device_freq_mhz",
             TypedJson::integer(flatInt(values, "APB_DEVICE_FREQ", 100)));
    addField(apb, "delay_scale",
             TypedJson::integer(flatInt(values, "APB_DELAY_SCALE", 64)));
    addField(soc, "apb", TypedJson::object(std::move(apb)));

    TypedFields axi;
    addField(axi, "cpu_freq_mhz", TypedJson::integer(synthFreq));
    addField(axi, "device_freq_mhz",
             TypedJson::integer(flatInt(values, "AXI_DEVICE_FREQ", 100)));
    addField(axi, "delay_scale",
             TypedJson::integer(flatInt(values, "AXI_DELAY_SCALE", 64)));
    addField(soc, "axi", TypedJson::object(std::move(axi)));
    addField(root, "soc", TypedJson::object(std::move(soc)));

    TypedFields boot;
    addField(boot, "sbi_enable",
             TypedJson::boolean(flatBool(values, "SBI_ENABLE")));
    addField(boot, "sbi_impl",
             TypedJson::string(flatString(values, "SBI_IMPL", "none")));
    addField(boot, "sbi_platform",
             TypedJson::string(flatString(values, "SBI_PLATFORM", "npc")));
    addField(boot, "sbi_fw_base",
             TypedJson::string(
                 flatString(values, "SBI_FW_BASE", "0x80000000")));
    addField(boot, "sbi_fw_size",
             TypedJson::string(
                 flatString(values, "SBI_FW_SIZE", "0x00200000")));
    addField(boot, "sbi_next_addr",
             TypedJson::string(
                 flatString(values, "SBI_NEXT_ADDR", "0x80200000")));
    addField(boot, "sbi_build_jobs",
             TypedJson::integer(flatInt(values, "SBI_BUILD_JOBS", 0)));
    addField(boot, "sbi_fdt_enable",
             TypedJson::boolean(flatBool(values, "SBI_FDT_ENABLE")));
    addField(boot, "sbi_fdt_path",
             TypedJson::string(
                 flatString(values, "SBI_FDT_PATH", "dts/npc.dts")));
    addField(boot, "sbi_fdt_addr",
             TypedJson::string(
                 flatString(values, "SBI_FDT_ADDR", "0x80f00000")));
    addField(boot, "linux_boot_enable",
             TypedJson::boolean(flatBool(values, "LINUX_BOOT_ENABLE")));
    addField(boot, "linux_image_path",
             TypedJson::string(flatString(values, "LINUX_IMAGE_PATH")));
    addField(boot, "linux_initramfs_enable",
             TypedJson::boolean(flatBool(values, "LINUX_INITRAMFS_ENABLE")));
    addField(boot, "linux_initramfs_path",
             TypedJson::string(flatString(values, "LINUX_INITRAMFS_PATH")));
    addField(boot, "linux_initramfs_addr",
             TypedJson::string(
                 flatString(values, "LINUX_INITRAMFS_ADDR", "0x82000000")));
    addField(boot, "linux_bootargs",
             TypedJson::string(flatString(values, "LINUX_BOOTARGS")));
    addField(root, "boot", TypedJson::object(std::move(boot)));

    TypedFields build;
    addField(build, "pdk",
             TypedJson::string(pdkOverride.empty()
                                   ? flatString(values, "PDK", "icsprout55")
                                   : pdkOverride));
    TypedFields trace;
    addField(trace, "vcd", TypedJson::boolean(flatBool(values, "TRACE_VCD")));
    addField(trace, "fst", TypedJson::boolean(flatBool(values, "TRACE_FST")));
    addField(trace, "file",
             TypedJson::string(
                 flatString(values, "TRACE_FILE", "waveform.vcd")));
    addField(trace, "depth",
             TypedJson::integer(flatInt(values, "TRACE_DEPTH", 0)));
    addField(build, "trace", TypedJson::object(std::move(trace)));
    TypedFields log;
    addField(log, "level", TypedJson::integer(flatInt(values, "LOG_LEVEL", 3)));
    addField(log, "to_file",
             TypedJson::boolean(flatBool(values, "LOG_TO_FILE")));
    addField(log, "dir",
             TypedJson::string(flatString(values, "LOG_DIR", "log")));
    addField(log, "timestamp",
             TypedJson::boolean(flatBool(values, "LOG_TIMESTAMP")));
    addField(log, "source_location",
             TypedJson::boolean(flatBool(values, "LOG_SOURCE_LOCATION")));
    addField(build, "log", TypedJson::object(std::move(log)));
    addField(root, "build", TypedJson::object(std::move(build)));

    // 兼容视图：保留完整的有效扁平键值，供 MMUProfile.fromEnv 等仍按
    // 扁平符号读取的组件使用。typed 分组是这个 map 的结构化投影，两者由
    // 同一次 C++ 派生结果生成，不会形成第二个配置源。
    TypedFields flat;
    for (const auto &[key, value] : values)
        addField(flat, key, TypedJson::string(value));
    addField(root, "flat", TypedJson::object(std::move(flat)));

    std::ostringstream out;
    TypedJson::object(std::move(root)).write(out, 0);
    out << '\n';
    return out.str();
}

std::string perfName(const std::string &key)
{
    std::string result{"PerfCounter"};
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

std::string configDigest(const std::map<std::string, std::string> &values)
{
    // 64-bit FNV-1a over the canonical key/value set.  This is a build
    // identity, not a cryptographic hash; keeping it local avoids adding a
    // crypto dependency to the Kconfig/build tools.
    std::uint64_t hash = 1469598103934665603ULL;
    for (const auto &[key, value] : values) {
        for (const char character : key) {
            hash ^= static_cast<unsigned char>(character);
            hash *= 1099511628211ULL;
        }
        hash ^= '=';
        hash *= 1099511628211ULL;
        for (const char character : value) {
            hash ^= static_cast<unsigned char>(character);
            hash *= 1099511628211ULL;
        }
        hash ^= '\n';
        hash *= 1099511628211ULL;
    }
    static constexpr char hex[] = "0123456789abcdef";
    std::string result(16, '0');
    for (int index = 15; index >= 0; --index) {
        result[static_cast<std::size_t>(index)] = hex[hash & 0xfU];
        hash >>= 4U;
    }
    return result;
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
    const bool hasPerf = asBool(values, "PERF_STATS");

    const auto flag = [&](const char *key) {
        return asBool(values, key);
    };

    std::ostringstream out;
    out << "// Automatically generated by tools/npc_config_gen from Kconfig JSON.\n";
    out << "// Source: include/generated/npc_config.json\n";
    out << "// Config digest: " << digest << "\n";
    out << "// DO NOT EDIT.\n";
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
    out << "inline constexpr std::uint64_t SocMBase{0x30000000ULL};\n";
    out << "inline constexpr std::uint64_t SocMSize{0x10000000ULL};\n";
    out << "inline constexpr std::uint64_t SocResetPC{0x30000000ULL};\n";
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
        {"HasPerfStats", hasPerf},
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

    for (const auto &group : perfGroups()) {
        const bool groupOn = asBool(values, "PERF_COUNTER_" + std::string{group.name}, true);
        for (const char *counter : group.counters) {
            bool enabled = hasPerf && groupOn &&
                           asBool(values, "PERF_COUNTER_" + std::string{counter}, true);
            if (std::string_view{group.name} == "MDU")
                enabled = enabled && hasM;
            else if (std::string_view{group.name} == "ICACHE")
                enabled = enabled && hasICache;
            else if (std::string_view{group.name} == "DCACHE")
                enabled = enabled && hasDCache;
            out << "inline constexpr bool " << perfName(counter) << "{"
                << cppBool(enabled) << "};\n";
        }
    }

    out << "\ninline constexpr std::string_view ConfigHash{"
        << cppString(digest) << "};\n";
    out << "} // namespace npc\n";
    (void)overrides;
    return out.str();
}

std::string readFile(const std::string &path)
{
    std::ifstream input{path, std::ios::binary};
    if (!input)
        throw std::runtime_error("无法打开 " + path);
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

void ensureParentDirectory(const std::string &path)
{
    const std::filesystem::path parent = std::filesystem::path{path}.parent_path();
    if (parent.empty())
        return;
    std::error_code error;
    std::filesystem::create_directories(parent, error);
    if (error)
        throw std::runtime_error("无法创建目录 " + parent.string() + ": " +
                                 error.message());
}

void writeIfChanged(const std::string &path, const std::string &content)
{
    std::ifstream existing{path, std::ios::binary};
    if (existing) {
        std::ostringstream buffer;
        buffer << existing.rdbuf();
        if (buffer.str() == content)
            return;
    }
    ensureParentDirectory(path);
    const auto temporary = path + ".tmp";
    {
        std::ofstream output{temporary, std::ios::binary | std::ios::trunc};
        if (!output)
            throw std::runtime_error("无法写入 " + temporary);
        output << content;
        output.flush();
        if (!output)
            throw std::runtime_error("写入 " + temporary + " 失败");
    }
    if (std::rename(temporary.c_str(), path.c_str()) != 0) {
        std::remove(temporary.c_str());
        throw std::runtime_error("无法替换 " + path);
    }
}

} // namespace

int main(int argc, char **argv)
{
    try {
        std::string configJson;
        std::string output;
        std::string effectiveJson;
        std::string typedJson;
        std::string getKey;
        std::string perfBuildMetadata;
        std::string perfSynth;
        std::string pdk;
        std::string savable{"n"};
        long long socXlen = -1;
        std::vector<std::string> overrides;

        for (int index = 1; index < argc; ++index) {
            const std::string argument{argv[index]};
            const auto next = [&](const char *name) -> std::string {
                if (index + 1 >= argc)
                    throw std::runtime_error(std::string{"缺少参数 "} + name);
                return argv[++index];
            };
            if (argument == "--config-json")
                configJson = next("--config-json");
            else if (argument == "--output")
                output = next("--output");
            else if (argument == "--effective-json")
                effectiveJson = next("--effective-json");
            else if (argument == "--typed-json")
                typedJson = next("--typed-json");
            else if (argument == "--get")
                getKey = next("--get");
            else if (argument == "--soc-xlen")
                socXlen = std::stoll(next("--soc-xlen"));
            else if (argument == "--perf-build-metadata")
                perfBuildMetadata = next("--perf-build-metadata");
            else if (argument == "--perf-synth")
                perfSynth = next("--perf-synth");
            else if (argument == "--savable")
                savable = next("--savable");
            else if (argument == "--pdk")
                pdk = next("--pdk");
            else if (argument == "--set")
                overrides.push_back(next("--set"));
            else
                throw std::runtime_error("未知参数: " + argument);
        }
        if (configJson.empty())
            throw std::runtime_error("必须指定 --config-json");
        if (output.empty() && effectiveJson.empty() && typedJson.empty() &&
            getKey.empty())
            throw std::runtime_error(
                "必须指定 --output、--effective-json、--typed-json 或 --get "
                "之一");

        auto values = FlatJsonParser{readFile(configJson)}.parse();
        for (const auto &assignment : overrides) {
            const auto separator = assignment.find('=');
            if (separator == std::string::npos)
                throw std::runtime_error("--set 必须是 CONFIG_X=VALUE");
            std::string key = assignment.substr(0, separator);
            const std::string value = assignment.substr(separator + 1);
            if (key.starts_with("CONFIG_"))
                key.erase(0, 7);
            values[key] = value;
        }

        deriveEffectiveConfig(values);
        if (socXlen < 0) {
            socXlen = asInt(values, "SOC_XLEN_EFFECTIVE", 32);
        } else {
            if (socXlen != 32 && socXlen != 64)
                throw std::runtime_error("SOC_XLEN 只允许 32/64");
            values["SOC_XLEN_EFFECTIVE"] = std::to_string(socXlen);
        }
        if (socXlen != 32 && socXlen != 64)
            throw std::runtime_error("SOC_XLEN 只允许 32/64");

        const bool getMode = !getKey.empty();
        const auto digest = configDigest(values);
        if (!effectiveJson.empty()) {
            writeIfChanged(effectiveJson, renderEffectiveJson(values));
            if (!getMode) {
                std::cout << "[npc-config] wrote effective config "
                          << effectiveJson << "\n";
            }
        }
        if (!typedJson.empty()) {
            writeIfChanged(
                typedJson, renderTypedJson(values, digest, pdk, socXlen));
            if (!getMode) {
                std::cout << "[npc-config] wrote typed config " << typedJson
                          << "\n";
            }
        }
        if (getMode) {
            std::string key = getKey;
            if (key.starts_with("CONFIG_"))
                key.erase(0, 7);
            const auto found = values.find(key);
            // 查询模式供 shell 脚本使用：缺少的符号输出空行并正常退出，
            // 让调用者可以用默认值处理可选配置。
            std::cout << (found == values.end() ? "" : found->second) << "\n";
            return 0;
        }
        if (output.empty())
            return 0;

        const auto content = renderModule(
            values, socXlen, digest, perfBuildMetadata, perfSynth,
            savable == "y", pdk, overrides);
        writeIfChanged(output, content);
        std::cout << "[npc-config] generated " << output << " (xlen="
                  << asInt(values, "XLEN", 32) << ", soc_xlen=" << socXlen
                  << ")\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "错误：" << error.what() << "\n";
        return 2;
    }
}
