// Audit compressed floating-point load/store encodings emitted by GCC.
//
// Reads `objdump -d` output and classifies 16-bit RVC instructions by
// quadrant/funct3.  This keeps the audit independent of objdump's mnemonic
// choice (RISC-V objdump prints both C.FLD and FLD as "fld").

#include <array>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <map>
#include <sstream>
#include <string>

namespace {

const char *classifyRv32(unsigned half)
{
    const unsigned quadrant = half & 0x3U;
    const unsigned funct3 = (half >> 13U) & 0x7U;
    if (quadrant == 0) {
        switch (funct3) {
        case 0b001: return "C.FLD";
        case 0b011: return "C.FLW";
        case 0b101: return "C.FSD";
        case 0b111: return "C.FSW";
        default: return nullptr;
        }
    }
    if (quadrant == 2) {
        switch (funct3) {
        case 0b001: return "C.FLDSP";
        case 0b011: return "C.FLWSP";
        case 0b101: return "C.FSDSP";
        case 0b111: return "C.FSWSP";
        default: return nullptr;
        }
    }
    return nullptr;
}

const char *classifyRv64(unsigned half)
{
    const unsigned quadrant = half & 0x3U;
    const unsigned funct3 = (half >> 13U) & 0x7U;
    if (quadrant == 0) {
        switch (funct3) {
        case 0b001: return "C.FLD";
        case 0b101: return "C.FSD";
        default: return nullptr;
        }
    }
    if (quadrant == 2) {
        switch (funct3) {
        case 0b001: return "C.FLDSP";
        case 0b101: return "C.FSDSP";
        default: return nullptr;
        }
    }
    return nullptr;
}

bool isHex4(const std::string &text)
{
    if (text.size() != 4)
        return false;
    for (const unsigned char character : text) {
        if (!std::isxdigit(character))
            return false;
    }
    return true;
}

} // namespace

int main(int argc, char **argv)
{
    if (argc != 3 || (std::string{argv[1]} != "32" && std::string{argv[1]} != "64")) {
        std::cerr << "用法: audit_cfp <32|64> <elf>\n";
        return 2;
    }
    const std::string xlen{argv[1]};
    const std::string elf{argv[2]};
    const char *objdumpEnv = std::getenv("OBJDUMP");
    const std::string objdump = objdumpEnv && *objdumpEnv ? objdumpEnv : "objdump";
    const std::string command =
        "'" + objdump + "' -d '" + elf + "' 2>/dev/null";
    FILE *pipe = popen(command.c_str(), "r");
    if (!pipe) {
        std::cerr << "[FAIL] 无法启动 objdump: " << objdump << '\n';
        return 1;
    }

    std::map<std::string, unsigned> counts;
    unsigned integerSameEncoding = 0;
    std::array<char, 4096> buffer{};
    std::string line;
    while (std::fgets(buffer.data(), static_cast<int>(buffer.size()), pipe)) {
        line = buffer.data();
        const auto colon = line.find(':');
        if (colon == std::string::npos)
            continue;
        std::istringstream fields{line.substr(colon + 1)};
        std::string raw;
        if (!(fields >> raw) || !isHex4(raw))
            continue;
        const unsigned half = static_cast<unsigned>(std::stoul(raw, nullptr, 16));
        if ((half & 0x3U) == 0x3U) // 32-bit instruction, not RVC
            continue;
        const char *name = xlen == "32" ? classifyRv32(half) : classifyRv64(half);
        if (xlen == "64") {
            const unsigned funct3 = (half >> 13U) & 0x7U;
            const unsigned quadrant = half & 0x3U;
            if ((quadrant == 0 || quadrant == 2) &&
                (funct3 == 0b011 || funct3 == 0b111))
                ++integerSameEncoding;
        }
        if (name)
            ++counts[name];
    }
    const int status = pclose(pipe);
    if (status != 0) {
        std::cerr << "[FAIL] objdump 退出状态非零: " << status << '\n';
        return 1;
    }

    const std::array<const char *, 8> required32{
        "C.FLD", "C.FSD", "C.FLW", "C.FSW",
        "C.FLDSP", "C.FSDSP", "C.FLWSP", "C.FSWSP"};
    const std::array<const char *, 4> required64{
        "C.FLD", "C.FSD", "C.FLDSP", "C.FSDSP"};
    bool missing = false;
    if (xlen == "32") {
        for (const char *name : required32) {
            if (!counts[name]) {
                missing = true;
                std::cerr << "[FAIL] 缺少压缩浮点访存编码: " << name << '\n';
            }
        }
    } else {
        for (const char *name : required64) {
            if (!counts[name]) {
                missing = true;
                std::cerr << "[FAIL] 缺少压缩浮点访存编码: " << name << '\n';
            }
        }
    }
    if (missing)
        return 1;
    if (xlen == "32") {
        std::cout << "[PASS] GCC 生成的真实压缩浮点访存编码: ";
        for (const char *name : required32)
            std::cout << name << '=' << counts[name] << ' ';
        std::cout << '\n';
    } else {
        std::cout << "[PASS] GCC 生成的真实压缩浮点访存编码: ";
        for (const char *name : required64)
            std::cout << name << '=' << counts[name] << ' ';
        std::cout << '\n';
        if (integerSameEncoding < 4) {
            std::cerr << "[FAIL] RV64 同编码位未覆盖 C.LD/C.SD/C.LDSP/C.SDSP"
                      << "（仅 " << integerSameEncoding << " 条）\n";
            return 1;
        }
        std::cout << "[PASS] RV64 同编码位整数 C.LD/C.SD 共 "
                  << integerSameEncoding << " 条\n";
    }
    return 0;
}
