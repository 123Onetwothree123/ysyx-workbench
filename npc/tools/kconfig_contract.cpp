// Kconfig 配置契约校验：把“能配置”收紧到 RTL 真正支持的范围。
//
// Kconfig 的 int/range 只能表达连续区间，无法直接写出
// XLEN ∈ {32,64}、SOC_XLEN ∈ {32,64} 或“页大小/PTE 大小必须是
// 2 的幂”。本工具在配置阶段补上这些约束，并给 make defconfig /
// menuconfig 调用；非标准但可表达的几何会放行，不可表达的几何明确报错。
//
// 用法：
//   tools/build/kconfig_contract --config .config
//   tools/build/kconfig_contract --defconfigs [--defconfigs-dir DIR]
//   tools/build/kconfig_contract --self-test
//
// 本工具只依赖 C++ 标准库，运行时不需要 Python。

#include <algorithm>
#include <cctype>
#include <charconv>
#include <climits>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace fs = std::filesystem;

namespace {

// 当前 RTL/大页实现的几何上限。Kconfig 不再用 range 锁死页大小和
// 级数；这里只拒绝明确不可能或当前 RTL 无法表达的几何。若 RTL 扩展
// 这些上限，必须同时更新本文件和自检。
constexpr long long kMaxMmuLevels = 8;
constexpr long long kPageSizeMin = 4;
// pageSizeBytes 是有符号 32 位 Int，最大的 2 的幂是 1GiB。具体组合
// 还受 pageBits + levels*indexBits <= 64 等几何约束限制。
constexpr long long kPageSizeMax = 1LL << 30;
constexpr long long kPageTableSizeMin = 8;  // 至少容纳 2 个 4B PTE
constexpr long long kSatpPpnBits = 44;

struct Value {
  enum class Kind { Boolean, Integer, String };

  Kind kind = Kind::String;
  bool boolean = false;
  long long integer = 0;
  std::string string;

  static Value make_bool(bool value) {
    Value v;
    v.kind = Kind::Boolean;
    v.boolean = value;
    return v;
  }

  static Value make_int(long long value) {
    Value v;
    v.kind = Kind::Integer;
    v.integer = value;
    return v;
  }

  static Value make_string(std::string value) {
    Value v;
    v.kind = Kind::String;
    v.string = std::move(value);
    return v;
  }
};

using Config = std::map<std::string, Value>;

std::string strip(std::string_view text) {
  std::size_t begin = 0;
  std::size_t end = text.size();
  while (begin < end && std::isspace(static_cast<unsigned char>(text[begin]))) {
    ++begin;
  }
  while (end > begin && std::isspace(static_cast<unsigned char>(text[end - 1]))) {
    --end;
  }
  return std::string(text.substr(begin, end - begin));
}

std::string lower_ascii(std::string text) {
  for (char& ch : text) {
    ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
  }
  return text;
}

std::string value_to_string(const Value& value) {
  switch (value.kind) {
    case Value::Kind::Boolean:
      return value.boolean ? "True" : "False";
    case Value::Kind::Integer:
      return std::to_string(value.integer);
    case Value::Kind::String:
      return value.string;
  }
  return {};
}

bool is_power_of_two(long long value) {
  if (value <= 0) {
    return false;
  }
  const auto unsigned_value = static_cast<unsigned long long>(value);
  return (unsigned_value & (unsigned_value - 1)) == 0;
}

long long log2_int(long long value) {
  long long result = 0;
  auto unsigned_value = static_cast<unsigned long long>(value);
  while (unsigned_value > 1) {
    unsigned_value >>= 1;
    ++result;
  }
  return result;
}

std::optional<long long> parse_integer(std::string_view input) {
  std::string text = strip(input);
  if (text.empty()) {
    return std::nullopt;
  }

  std::size_t index = 0;
  bool negative = false;
  if (text[index] == '+' || text[index] == '-') {
    negative = text[index] == '-';
    ++index;
  }
  if (index >= text.size()) {
    return std::nullopt;
  }

  int base = 10;
  if (text[index] == '0' && index + 1 < text.size()) {
    const char prefix = static_cast<char>(std::tolower(
        static_cast<unsigned char>(text[index + 1])));
    if (prefix == 'x') {
      base = 16;
      index += 2;
    } else if (prefix == 'o') {
      base = 8;
      index += 2;
    } else if (prefix == 'b') {
      base = 2;
      index += 2;
    }
  }
  if (index >= text.size()) {
    return std::nullopt;
  }

  std::string digits;
  digits.reserve(text.size() - index);
  bool previous_underscore = false;
  for (std::size_t i = index; i < text.size(); ++i) {
    const char ch = text[i];
    if (ch == '_') {
      if (digits.empty() || previous_underscore) {
        return std::nullopt;
      }
      previous_underscore = true;
      continue;
    }
    const auto uch = static_cast<unsigned char>(ch);
    int digit = -1;
    if (std::isdigit(uch)) {
      digit = ch - '0';
    } else if (std::isalpha(uch)) {
      digit = std::tolower(uch) - 'a' + 10;
    }
    if (digit < 0 || digit >= base) {
      return std::nullopt;
    }
    digits.push_back(ch);
    previous_underscore = false;
  }
  if (digits.empty() || previous_underscore) {
    return std::nullopt;
  }

  // Python 3 的 int(text, 0) 不接受带前导零的非零十进制字面量。
  if (base == 10 && digits.size() > 1 && digits.front() == '0' &&
      std::any_of(digits.begin(), digits.end(), [](char ch) { return ch != '0'; })) {
    return std::nullopt;
  }

  unsigned long long magnitude = 0;
  constexpr unsigned long long kLimit = 1ULL << 63;
  for (char ch : digits) {
    int digit = 0;
    if (ch >= '0' && ch <= '9') {
      digit = ch - '0';
    } else {
      digit = std::tolower(static_cast<unsigned char>(ch)) - 'a' + 10;
    }
    if (magnitude > (kLimit - static_cast<unsigned long long>(digit)) /
                          static_cast<unsigned long long>(base)) {
      return std::nullopt;
    }
    magnitude = magnitude * static_cast<unsigned long long>(base) +
                static_cast<unsigned long long>(digit);
  }

  if (negative) {
    if (magnitude == kLimit) {
      return LLONG_MIN;
    }
    if (magnitude > static_cast<unsigned long long>(LLONG_MAX)) {
      return std::nullopt;
    }
    return -static_cast<long long>(magnitude);
  }
  if (magnitude > static_cast<unsigned long long>(LLONG_MAX)) {
    return std::nullopt;
  }
  return static_cast<long long>(magnitude);
}

[[noreturn]] void throw_not_integer(const std::string& key, const Value& value) {
  std::string rendered = value_to_string(value);
  if (value.kind == Value::Kind::String) {
    rendered = "'" + rendered + "'";
  }
  throw std::runtime_error(key + "=" + rendered + " 不是整数");
}

long long as_int(const Config& values, const std::string& key, long long fallback) {
  const auto it = values.find(key);
  if (it == values.end()) {
    return fallback;
  }
  const Value& value = it->second;
  if (value.kind == Value::Kind::Boolean) {
    return value.boolean ? 1 : 0;
  }
  if (value.kind == Value::Kind::Integer) {
    return value.integer;
  }
  const auto parsed = parse_integer(value.string);
  if (!parsed) {
    throw_not_integer(key, value);
  }
  return *parsed;
}

bool as_bool(const Config& values, const std::string& key, bool fallback = false) {
  const auto it = values.find(key);
  if (it == values.end()) {
    return fallback;
  }
  if (it->second.kind == Value::Kind::Boolean) {
    return it->second.boolean;
  }
  const std::string text = lower_ascii(strip(value_to_string(it->second)));
  return text == "y" || text == "1" || text == "true";
}

std::string as_str(const Config& values, const std::string& key,
                   const std::string& fallback) {
  const auto it = values.find(key);
  if (it == values.end() || it->second.kind == Value::Kind::String) {
    return it == values.end() ? fallback : it->second.string;
  }
  return value_to_string(it->second);
}

using ChoiceList = std::initializer_list<std::pair<std::string_view, std::string_view>>;

std::string derived_choice_string(const Config& values, const std::string& key,
                                  ChoiceList choices,
                                  const std::string& fallback) {
  if (values.find(key) != values.end()) {
    return as_str(values, key, fallback);
  }
  for (const auto& [symbol, value] : choices) {
    if (as_bool(values, std::string(symbol), false)) {
      return std::string(value);
    }
  }
  return fallback;
}

bool standard_pte_layout(const Config& values, long long pte_bits) {
  const long long ppn_high = pte_bits == 32 ? 31 : 53;
  return as_int(values, "MMU_PTE_VALID_BIT", 0) == 0 &&
         as_int(values, "MMU_PTE_READ_BIT", 1) == 1 &&
         as_int(values, "MMU_PTE_WRITE_BIT", 2) == 2 &&
         as_int(values, "MMU_PTE_EXEC_BIT", 3) == 3 &&
         as_int(values, "MMU_PTE_USER_BIT", 4) == 4 &&
         as_int(values, "MMU_PTE_GLOBAL_BIT", 5) == 5 &&
         as_int(values, "MMU_PTE_A_BIT", 6) == 6 &&
         as_int(values, "MMU_PTE_D_BIT", 7) == 7 &&
         as_int(values, "MMU_PTE_PPN_LOW", 10) == 10 &&
         as_int(values, "MMU_PTE_PPN_HIGH", ppn_high) == ppn_high;
}

// 返回 Linux DTB 可声明的标准 mmu-type；非标准几何返回空串。
//
// 与 npc/scripts/build_dtb.sh 的推导保持一致：只有标准 Sv32
// （RV32，4KiB 页/4B PTE/2 级）和 Sv39/Sv48（RV64，4KiB 页/8B PTE/
// 3/4 级）才能写入 mmu-type。CUSTOM 或任意非标准几何不能伪装成标准
// 类型，Linux 打包必须在配置阶段就拒绝。
std::string linux_mmu_type(const Config& values, long long xlen) {
  if (!as_bool(values, "HAS_MMU", false)) {
    return {};
  }
  const std::string geometry = lower_ascii(strip(derived_choice_string(
      values, "MMU_GEOMETRY_MODE",
      {{"MMU_GEOMETRY_CUSTOM", "custom"},
       {"MMU_GEOMETRY_STANDARD", "standard"}},
      "standard")));
  if (geometry != "standard") {
    return {};
  }
  const long long levels = as_int(values, "MMU_LEVELS", xlen == 64 ? 3 : 2);
  const long long page_size = as_int(values, "MMU_PAGE_SIZE_BYTES", 4096);
  const long long table_size =
      as_int(values, "MMU_PAGE_TABLE_SIZE_BYTES", 4096);
  const std::string pte_format = lower_ascii(strip(derived_choice_string(
      values, "MMU_PTE_FORMAT",
      {{"MMU_PTE_CUSTOM", "custom"},
       {"MMU_PTE_RISCV64", "riscv64"},
       {"MMU_PTE_RISCV32", "riscv32"}},
      xlen == 64 ? "riscv64" : "riscv32")));

  if (xlen == 32) {
    const long long pte_size = as_int(values, "MMU_PTE_SIZE_BYTES", 4);
    if (levels == 2 && page_size == 4096 && table_size == 4096 &&
        pte_size == 4 && pte_format == "riscv32") {
      return "riscv,sv32";
    }
    return {};
  }

  const long long pte_size = as_int(values, "MMU_PTE_SIZE_BYTES", 8);
  if (page_size != 4096 || table_size != 4096 || pte_size != 8 ||
      pte_format != "riscv64") {
    return {};
  }
  if (levels == 3) {
    return "riscv,sv39";
  }
  if (levels == 4) {
    return "riscv,sv48";
  }
  return {};
}

std::string hex_no_prefix(long long value) {
  if (value < 0) {
    return "-" + hex_no_prefix(-value);
  }
  std::ostringstream out;
  out << std::hex << std::nouppercase
      << static_cast<unsigned long long>(value);
  return out.str();
}

std::string hex0x(long long value) { return "0x" + hex_no_prefix(value); }

// 返回配置契约错误列表；空列表表示可以进入 elaboration。
//
// 这里只检查配置阶段能确定的离散值/几何约束。RTL 内部的面积、时序
// 等实现限制仍由 elaboration 负责。
std::vector<std::string> validate_config(const Config& values,
                                         const std::string& source = ".config") {
  std::vector<std::string> errors;
  const auto err = [&errors, &source](const std::string& message) {
    errors.push_back(source + ": " + message);
  };

  const long long xlen = as_int(values, "XLEN", 32);
  if (xlen != 32 && xlen != 64) {
    err("XLEN=" + std::to_string(xlen) +
        " 非法：只允许 32 或 64。Kconfig 的 int range "
        "无法表达离散集合，33..63 由本校验拒绝");
  }

  // direct-NPC RAM 窗口：AXIRAM 深度、PMA、C++ CONFIG_MSIZE 和
  // DTB/打包脚本共用这一个值。只接受 2 的幂，避免 SyncReadMem 索引
  // 与 AXI 窗口出现未覆盖的高地址。
  const long long ram_size = as_int(values, "NPC_RAM_SIZE", 0x04000000);
  constexpr long long ram_base = 0x80000000;
  if (ram_size < 0x1000) {
    err("NPC_RAM_SIZE=" + hex0x(ram_size) + " 太小：至少 4 KiB");
  } else if (!is_power_of_two(ram_size)) {
    err("NPC_RAM_SIZE=" + hex0x(ram_size) + " 非法：必须是 2 的幂");
  } else if (ram_size > 0x80000000) {
    err("NPC_RAM_SIZE=" + hex0x(ram_size) + " 超过 32 位物理地址窗口");
  }
  const long long ram_end = ram_base + ram_size;

  if (!as_bool(values, "SOC_XLEN_FOLLOW_CPU", false)) {
    const long long soc_xlen = as_int(values, "SOC_XLEN", 32);
    if (soc_xlen != 32 && soc_xlen != 64) {
      err("SOC_XLEN=" + std::to_string(soc_xlen) +
          " 非法：只允许 32 或 64"
          "（SOC_XLEN_FOLLOW_CPU=n 时生效）");
    }
  }

  // ---- F/D 与 FPU 后端 -------------------------------------------------
  const bool use_f = as_bool(values, "RISCV_F", false);
  const bool use_d = as_bool(values, "RISCV_D", false);
  const std::string fpu_impl = lower_ascii(strip(derived_choice_string(
      values, "FPU_IMPL",
      {{"FPU_IMPL_HARDFLOAT", "hardfloat"},
       {"FPU_IMPL_FUDIAN", "fudian"},
       {"FPU_IMPL_EXTERNAL", "external"},
       {"FPU_IMPL_NONE", "none"}},
      "none")));
  if (fpu_impl != "none" && fpu_impl != "hardfloat" && fpu_impl != "fudian" &&
      fpu_impl != "external") {
    err("FPU_IMPL='" + fpu_impl + "' 非法：只允许 none/hardfloat/fudian/external");
  }
  // 字符串派生项和 choice 布尔项必须一致；否则 savedefconfig 或手工
  // 修改 .config 会让 Makefile 和 Kconfig 菜单看到不同后端。
  for (const auto& [symbol, choice] : ChoiceList{
           {"FPU_IMPL_HARDFLOAT", "hardfloat"},
           {"FPU_IMPL_FUDIAN", "fudian"},
           {"FPU_IMPL_EXTERNAL", "external"},
           {"FPU_IMPL_NONE", "none"}}) {
    if (as_bool(values, std::string(symbol), false) &&
        std::string(choice) != fpu_impl) {
      err(std::string(symbol) + "=y 与 FPU_IMPL='" + fpu_impl + "' 不一致；"
          "请重新运行 menuconfig/olddefconfig");
    }
  }
  if (use_d && !use_f) {
    err("RISCV_D=y 依赖 RISCV_F=y：D 扩展不能脱离 F 单独启用");
  }
  if (!use_f && fpu_impl != "none") {
    err("RISCV_F=n 时 FPU_IMPL 必须为 none，收到 '" + fpu_impl + "'；"
        "请同时启用 RISCV_F 或清除后端选择");
  }
  if (use_f && fpu_impl == "none") {
    err("RISCV_F=y 但 FPU_IMPL=none：必须选择 hardfloat/fudian 等后端");
  }
  if (fpu_impl == "external") {
    err("FPU_IMPL=external 只是预留接口，当前没有外部 FPU 实现；"
        "请选择 hardfloat/fudian，或关闭 RISCV_F");
  }

  const long long fpu_flen = as_int(values, "FPU_FLEN", 64);
  if (fpu_flen != 32 && fpu_flen != 64) {
    err("FPU_FLEN=" + std::to_string(fpu_flen) + " 非法：只允许 32 或 64");
  }
  if (use_d && fpu_flen != 64) {
    err("启用 RISCV_D 要求 FPU_FLEN=64，收到 FPU_FLEN=" +
        std::to_string(fpu_flen));
  }
  if (use_f) {
    const long long fpu_pipeline = as_int(values, "FPU_PIPELINE", 0);
    if (fpu_pipeline < 0 || fpu_pipeline > 8) {
      err("FPU_PIPELINE=" + std::to_string(fpu_pipeline) +
          " 非法：必须在 0..8 之间");
    }
    // FDIV/FSQRT、完整舍入和 fflags 是 F/D 的必备语义；允许关闭
    // 会让构建声称支持 F/D 却缺少指令或异常状态。
    if (!as_bool(values, "FPU_DIVSQRT", true)) {
      err("启用 RISCV_F/D 要求 FPU_DIVSQRT=y：FDIV/FSQRT 是必备指令");
    }
    if (!as_bool(values, "FPU_ROUNDING", true)) {
      err("启用 RISCV_F/D 要求 FPU_ROUNDING=y：必须支持完整舍入模式");
    }
    if (!as_bool(values, "FPU_FLAGS", true)) {
      err("启用 RISCV_F/D 要求 FPU_FLAGS=y：fflags 是必备状态");
    }
  }

  const bool has_s = as_bool(values, "HAS_S", false);
  const bool has_mmu = as_bool(values, "HAS_MMU", false);
  if (has_mmu && !has_s) {
    err("HAS_MMU=y 需要 HAS_S=y：MMU 依赖 S 模式 satp/页错误入口");
  }

  const long long levels = as_int(values, "MMU_LEVELS", xlen == 64 ? 3 : 2);
  if (levels < 1 || levels > kMaxMmuLevels) {
    err("MMU_LEVELS=" + std::to_string(levels) +
        " 非法：当前 RTL fillLevel 只支持 1..8 级");
  }

  const long long pte_size =
      as_int(values, "MMU_PTE_SIZE_BYTES", xlen == 64 ? 8 : 4);
  if (pte_size <= 0 || !is_power_of_two(pte_size)) {
    err("MMU_PTE_SIZE_BYTES=" + std::to_string(pte_size) +
        " 非法：必须是正的 2 的幂；"
        "当前 RTL 实际只实现 4B/8B，其他值由生成器/elaboration 明确拒绝");
  }

  const long long page_size = as_int(values, "MMU_PAGE_SIZE_BYTES", 4096);
  long long page_bits = 0;
  if (!is_power_of_two(page_size)) {
    err("MMU_PAGE_SIZE_BYTES=" + std::to_string(page_size) +
        " 非法：必须是 2 的幂，"
        "当前实现可表示 4B..1073741824B");
  } else if (page_size < kPageSizeMin || page_size > kPageSizeMax) {
    err("MMU_PAGE_SIZE_BYTES=" + std::to_string(page_size) +
        " 超出当前可实现范围 4..1073741824（pageSizeBytes 为 32 位 "
        "有符号 Int，最大 2 的幂为 1GiB）");
  } else {
    page_bits = log2_int(page_size);
  }

  const long long table_size =
      as_int(values, "MMU_PAGE_TABLE_SIZE_BYTES", 4096);
  if (!is_power_of_two(table_size)) {
    err("MMU_PAGE_TABLE_SIZE_BYTES=" + std::to_string(table_size) +
        " 非法：必须是 2 的幂");
  } else if (table_size < kPageTableSizeMin) {
    err("MMU_PAGE_TABLE_SIZE_BYTES=" + std::to_string(table_size) +
        " 非法：至少 8B（每级至少 2 个 PTE）");
  }
  if (pte_size > 0 && table_size % pte_size != 0) {
    err("MMU_PAGE_TABLE_SIZE_BYTES=" + std::to_string(table_size) +
        " 必须能被 MMU_PTE_SIZE_BYTES=" + std::to_string(pte_size) + " 整除");
  } else if (pte_size > 0) {
    const long long entries = table_size / pte_size;
    if (entries < 2 || !is_power_of_two(entries)) {
      err("每级页表项数=" + std::to_string(entries) +
          " 非法：必须是至少为 2 的 2 的幂 "
          "（table=" + std::to_string(table_size) +
          ", pte=" + std::to_string(pte_size) + "）");
    }
  }

  const std::string geometry = lower_ascii(derived_choice_string(
      values, "MMU_GEOMETRY_MODE",
      {{"MMU_GEOMETRY_CUSTOM", "custom"},
       {"MMU_GEOMETRY_STANDARD", "standard"}},
      "standard"));
  if (geometry != "standard" && geometry != "custom") {
    err("MMU_GEOMETRY_MODE='" + geometry + "' 非法：只允许 standard/custom");
  }

  const std::string pte_format = derived_choice_string(
      values, "MMU_PTE_FORMAT",
      {{"MMU_PTE_CUSTOM", "custom"},
       {"MMU_PTE_RISCV64", "riscv64"},
       {"MMU_PTE_RISCV32", "riscv32"}},
      xlen == 64 ? "riscv64" : "riscv32");
  if (pte_format != "riscv32" && pte_format != "riscv64" &&
      pte_format != "custom") {
    err("MMU_PTE_FORMAT='" + pte_format +
        "' 非法：只允许 riscv32/riscv64/custom");
  }

  if (has_mmu && xlen == 64 && geometry == "standard") {
    // RV64 STANDARD 必须完整匹配标准 Sv39 或 Sv48。
    if (levels != 3 && levels != 4) {
      err("RV64 STANDARD 的 MMU_LEVELS=" + std::to_string(levels) +
          " 非法：只允许 3(Sv39) 或 4(Sv48)；非标准级数请显式选择 "
          "MMU_GEOMETRY_CUSTOM");
    }
    if (pte_size != 8) {
      err("RV64 STANDARD 要求 MMU_PTE_SIZE_BYTES=8");
    }
    if (page_size != 4096) {
      err("RV64 STANDARD 要求 MMU_PAGE_SIZE_BYTES=4096");
    }
    if (table_size != 4096) {
      err("RV64 STANDARD 要求 MMU_PAGE_TABLE_SIZE_BYTES=4096");
    }
    if (pte_format != "riscv64") {
      err("RV64 STANDARD 要求 MMU_PTE_FORMAT=riscv64");
    }
    if (page_size == 4096 && table_size == 4096 && pte_size == 8) {
      const long long address_bits = 12 + levels * 9;
      if (address_bits != 39 && address_bits != 48) {
        err("RV64 STANDARD 几何算出 " + std::to_string(address_bits) +
            " 位 VA，只支持 39(Sv39)/48(Sv48)");
      }
    }
    if (pte_size == 8 && !standard_pte_layout(values, 64)) {
      err("RV64 STANDARD 要求标准 64-bit PTE 字段布局");
    }
  }

  if (has_mmu && geometry == "custom") {
    if (xlen != 64) {
      err("MMU_GEOMETRY_MODE=custom 只用于 RV64；RV32 自定义几何使用 STANDARD");
    }
    const long long satp_mode = as_int(values, "MMU_CUSTOM_SATP_MODE", 10);
    if (satp_mode < 10 || satp_mode > 15) {
      err("MMU_CUSTOM_SATP_MODE=" + std::to_string(satp_mode) +
          " 非法：必须使用保留的 satp MODE 10..15，不能复用标准 8/9");
    }
    if (pte_size > 0 && is_power_of_two(pte_size)) {
      const long long pte_bits = pte_size * 8;
      const long long ppn_low = as_int(values, "MMU_PTE_PPN_LOW", 10);
      const long long ppn_high =
          as_int(values, "MMU_PTE_PPN_HIGH", pte_size == 8 ? 53 : 31);
      const std::vector<long long> flags = {
          as_int(values, "MMU_PTE_VALID_BIT", 0),
          as_int(values, "MMU_PTE_READ_BIT", 1),
          as_int(values, "MMU_PTE_WRITE_BIT", 2),
          as_int(values, "MMU_PTE_EXEC_BIT", 3),
          as_int(values, "MMU_PTE_USER_BIT", 4),
          as_int(values, "MMU_PTE_GLOBAL_BIT", 5),
          as_int(values, "MMU_PTE_A_BIT", 6),
          as_int(values, "MMU_PTE_D_BIT", 7),
      };
      if (std::any_of(flags.begin(), flags.end(),
                      [pte_bits](long long bit) {
                        return bit < 0 || bit >= pte_bits;
                      })) {
        err("PTE 标志位必须位于 0.." + std::to_string(pte_bits - 1));
      }
      std::vector<long long> sorted_flags = flags;
      std::sort(sorted_flags.begin(), sorted_flags.end());
      if (std::adjacent_find(sorted_flags.begin(), sorted_flags.end()) !=
          sorted_flags.end()) {
        err("PTE 标志位不能重复");
      }
      if (!(8 <= ppn_low && ppn_low <= ppn_high && ppn_high < pte_bits)) {
        err("PTE PPN 位范围非法：[" + std::to_string(ppn_high) + ":" +
            std::to_string(ppn_low) + "]，要求 8 <= low <= high < " +
            std::to_string(pte_bits));
      }
      if (std::any_of(flags.begin(), flags.end(),
                      [ppn_low, ppn_high](long long bit) {
                        return ppn_low <= bit && bit <= ppn_high;
                      })) {
        err("PTE 标志位不能与 PPN 字段 [" + std::to_string(ppn_high) + ":" +
            std::to_string(ppn_low) + "] 重叠");
      }
      if (std::any_of(flags.begin(), flags.end(),
                      [ppn_high](long long bit) { return bit > ppn_high; })) {
        err("PTE 标志位必须位于 PPN 高位(" + std::to_string(ppn_high) +
            ")以下");
      }
      // 标准编码名必须与 PTE 位宽/字段布局完全一致；CUSTOM 不限制
      // 4/8 之外的值，交由生成器/elaboration 按 RTL 实际能力拒绝。
      if (pte_format == "riscv64" &&
          (pte_bits != 64 || !standard_pte_layout(values, 64))) {
        err("MMU_PTE_FORMAT=riscv64 要求 64-bit PTE 与标准字段布局");
      }
      if (pte_format == "riscv32" &&
          (pte_bits != 32 || !standard_pte_layout(values, 32))) {
        err("MMU_PTE_FORMAT=riscv32 要求 32-bit PTE 与标准字段布局");
      }
      const std::string ad_mode = derived_choice_string(
          values, "MMU_AD_MODE",
          {{"MMU_AD_HARDWARE", "hardware"},
           {"MMU_AD_IGNORE", "ignore"},
           {"MMU_AD_FAULT", "fault"}},
          "fault");
      if (ad_mode == "hardware" && pte_size > 4 &&
          (flags[6] >= 32 || flags[7] >= 32)) {
        err("MMU_AD_MODE=hardware 且 PTE=" + std::to_string(pte_size) +
            "B 时，A/D 位必须在低 32 位字内（<32）；当前 32 位页表写端口"
            "无法更新高字");
      }
      if (page_bits) {
        const long long ppn_bits = ppn_high - ppn_low + 1;
        // PTE/satp 的 PPN 字段可以比物理地址更宽；超出 64 位的
        // 高位不可用，RTL walk 会按保留位检查并要求为 0。这里只
        // 要求每个字段仍至少有 1 位可寻址的 PPN。
        const long long paddr_ppn_bits =
            std::min<long long>(ppn_bits, 64 - page_bits);
        const long long satp_ppn_bits =
            std::min<long long>(kSatpPpnBits, 64 - page_bits);
        if (paddr_ppn_bits < 1) {
          err("pageBits(" + std::to_string(page_bits) +
              ") 已占满 64 位物理地址，PTE PPN 位宽(" +
              std::to_string(ppn_bits) + ") 没有可用位");
        }
        if (satp_ppn_bits < 1) {
          err("pageBits(" + std::to_string(page_bits) +
              ") 已占满 64 位物理地址，satp.PPN(44 位) 没有可用位");
        }
        const long long index_bits =
            (pte_size > 0 && table_size % pte_size == 0 &&
             is_power_of_two(table_size / pte_size))
                ? log2_int(table_size / pte_size)
                : 0;
        const long long address_bits = page_bits + levels * index_bits;
        if (address_bits < 2 || address_bits > 64) {
          err("CUSTOM 几何地址位宽=" + std::to_string(address_bits) +
              " 非法：pageBits + levels * indexBits 必须在 2..64");
        }
        if (as_bool(values, "MMU_SUPERPAGE", false) &&
            ppn_bits < (levels - 1) * index_bits) {
          err("超级页对齐位(" + std::to_string((levels - 1) * index_bits) +
              ")超过 PTE PPN 位宽(" + std::to_string(ppn_bits) +
              ")，无法正确检查");
        }
      }
    }
  }

  // SBI 固件布局：Kconfig 的 hex 只表达数值，这里补齐对齐和区间约束。
  // 默认 SBI_ENABLE=n，不会影响现有 xv6/NPC 配置。
  if (as_bool(values, "SBI_ENABLE", false)) {
    const std::string sbi_impl = derived_choice_string(
        values, "SBI_IMPL",
        {{"SBI_IMPL_OPENSBI", "opensbi"},
         {"SBI_IMPL_RUSTSBI", "rustsbi"}},
        "none");
    if (sbi_impl == "none") {
      err("SBI_ENABLE=y 但未选择 SBI 实现（SBI_IMPL_OPENSBI/RUSTSBI）");
    }

    const long long base = as_int(values, "SBI_FW_BASE", 0x80000000);
    const long long size = as_int(values, "SBI_FW_SIZE", 0x00200000);
    const long long next_addr = as_int(values, "SBI_NEXT_ADDR", 0x80200000);
    if (base <= 0 || base % 4096 != 0) {
      err("SBI_FW_BASE=" + hex0x(base) + " 必须是正的 4 KiB 对齐地址");
    }
    if (size <= 0 || size % 4096 != 0) {
      err("SBI_FW_SIZE=" + hex0x(size) + " 必须是正的 4 KiB 倍数");
    }
    if (next_addr <= base) {
      err("SBI_NEXT_ADDR 必须大于 SBI_FW_BASE");
    } else if (base > 0 && size > 0 && base + size > next_addr) {
      err("SBI_FW_BASE+SBI_FW_SIZE=" + hex0x(base + size) + " 越过 "
          "SBI_NEXT_ADDR=" + hex0x(next_addr) + "，固件会覆盖 next-stage");
    } else {
      const long long jump_align =
          xlen == 64 ? 2 * 1024 * 1024 : 4 * 1024 * 1024;
      if (next_addr % jump_align != 0) {
        err("SBI_NEXT_ADDR=" + hex0x(next_addr) + " 必须按 " +
            std::to_string(jump_align / 1024 / 1024) + " MiB 对齐（RV" +
            std::to_string(xlen) + "）");
      }
    }

    if (as_bool(values, "SBI_FDT_ENABLE", false)) {
      // DTB 由 SBI 复制到 SBI_FDT_ADDR 后通过 a1 传给 next-stage。
      // 这里只做与固定 NPC RAM/固件布局有关的硬约束；DTB 实际大小
      // 在 scripts/build_dtb.sh 中按生成物再次校验。
      const long long fdt_addr = as_int(values, "SBI_FDT_ADDR", 0x80F00000);
      const std::string fdt_path =
          as_str(values, "SBI_FDT_PATH", "dts/npc.dts");
      if (fdt_path.empty()) {
        err("SBI_FDT_ENABLE=y 但 SBI_FDT_PATH 为空");
      }
      if (fdt_addr % 8 != 0) {
        err("SBI_FDT_ADDR=" + hex0x(fdt_addr) + " 必须按 8 字节对齐");
      }
      constexpr long long fdt_reserve = 0x10000;
      if (fdt_addr < ram_base || fdt_addr + fdt_reserve > ram_end) {
        err("SBI_FDT_ADDR=" + hex0x(fdt_addr) + " 超出 NPC RAM " +
            "[0x80000000, " + hex0x(ram_end) + ")，或末尾越过 RAM");
      }
      const long long fw_end = base + size;
      if (fdt_addr < fw_end && fdt_addr + fdt_reserve > base) {
        err("SBI_FDT_ADDR=" + hex0x(fdt_addr) + " 与 SBI 固件区 "
            "[0x80000000, " + hex0x(fw_end) + ") 重叠");
      }
      if (fdt_addr < next_addr) {
        if (fdt_addr + fdt_reserve > next_addr) {
          err("SBI_FDT_ADDR=" + hex0x(fdt_addr) +
              " 位于 next-stage 之前，"
              "但 64 KiB 保留区越过 SBI_NEXT_ADDR=" + hex0x(next_addr));
        }
      } else if (fdt_addr < next_addr + 0x1000) {
        err("SBI_FDT_ADDR=" + hex0x(fdt_addr) +
            " 覆盖 next-stage 入口页 " + hex0x(next_addr) + ".." +
            hex0x(next_addr + 0x1000));
      }
    }

    if (sbi_impl == "opensbi") {
      // OpenSBI 的 _start 需要 A 扩展；S 模式服务和 PMP 隔离分别
      // 依赖 HAS_S/HAS_PMP。缺失时在配置阶段就明确拒绝。
      if (!as_bool(values, "RISCV_A", false)) {
        err("SBI_IMPL_OPENSBI 需要 RISCV_A=y（_start 使用 amoswap.w）");
      }
      if (!has_s) {
        err("SBI_IMPL_OPENSBI 需要 HAS_S=y");
      }
      if (!as_bool(values, "HAS_PMP", false)) {
        err("SBI_IMPL_OPENSBI 需要 HAS_PMP=y（隔离 M/S 访问）");
      }
    }
  }

  // Linux 启动镜像：地址完全复用 SBI_NEXT_ADDR / SBI_FDT_ADDR，
  // 这里只检查配置阶段能确定的布局与标准 MMU 约束；Image/initramfs
  // 的实际大小与最终不重叠由 pack_linux_image.sh 再检查。
  if (as_bool(values, "LINUX_BOOT_ENABLE", false)) {
    if (!as_bool(values, "SBI_ENABLE", false)) {
      err("LINUX_BOOT_ENABLE=y 需要 SBI_ENABLE=y");
    }
    if (!as_bool(values, "SBI_FDT_ENABLE", false)) {
      err("LINUX_BOOT_ENABLE=y 需要 SBI_FDT_ENABLE=y（Linux 需要 a1=DTB）");
    }
    if (!has_s) {
      err("LINUX_BOOT_ENABLE=y 需要 HAS_S=y");
    }
    if (!has_mmu) {
      err("LINUX_BOOT_ENABLE=y 需要 HAS_MMU=y");
    }
    if (!as_bool(values, "RISCV_M", false)) {
      err("LINUX_BOOT_ENABLE=y 需要 RISCV_M=y（Linux 使用乘除法）");
    }
    if (!as_bool(values, "RISCV_A", false)) {
      err("LINUX_BOOT_ENABLE=y 需要 RISCV_A=y（Linux 原子操作）");
    }
    if (as_str(values, "LINUX_IMAGE_PATH", "").empty()) {
      err("LINUX_BOOT_ENABLE=y 但 LINUX_IMAGE_PATH 为空");
    }

    const long long next_addr = as_int(values, "SBI_NEXT_ADDR", 0x80200000);
    const long long fdt_addr = as_int(values, "SBI_FDT_ADDR", 0x83F00000);
    constexpr long long fdt_reserve = 0x10000;
    const long long fw_base = as_int(values, "SBI_FW_BASE", 0x80000000);
    const long long fw_size = as_int(values, "SBI_FW_SIZE", 0x00200000);
    const long long fw_end = fw_base + fw_size;
    const std::string mmu_type = linux_mmu_type(values, xlen);
    if (mmu_type.empty()) {
      err("LINUX_BOOT_ENABLE=y 要求标准 Sv32/Sv39/Sv48 MMU 几何；"
          "CUSTOM 或其他非标准几何不能伪装成标准 mmu-type");
    }
    if (as_bool(values, "LINUX_INITRAMFS_ENABLE", false)) {
      const std::string initramfs_path =
          as_str(values, "LINUX_INITRAMFS_PATH", "");
      if (initramfs_path.empty()) {
        err("LINUX_INITRAMFS_ENABLE=y 但 LINUX_INITRAMFS_PATH 为空");
      }
      const long long initrd_addr =
          as_int(values, "LINUX_INITRAMFS_ADDR", 0x82000000);
      if (initrd_addr % 4096 != 0) {
        err("LINUX_INITRAMFS_ADDR=" + hex0x(initrd_addr) + " 必须按 "
            "4 KiB 对齐");
      }
      if (initrd_addr < ram_base || initrd_addr >= ram_end) {
        err("LINUX_INITRAMFS_ADDR=" + hex0x(initrd_addr) + " 超出 NPC RAM " +
            "[0x80000000, " + hex0x(ram_end) + ")");
      }
      if (initrd_addr < next_addr + 0x1000) {
        err("LINUX_INITRAMFS_ADDR=" + hex0x(initrd_addr) + " 落在 "
            "Linux Image 入口 " + hex0x(next_addr) +
            " 附近；initramfs 必须位于 Image 之后");
      }
      if (initrd_addr < fw_end && initrd_addr + 0x1000 > fw_base) {
        err("LINUX_INITRAMFS_ADDR=" + hex0x(initrd_addr) + " 与 SBI 固件区 " +
            "[0x80000000, " + hex0x(fw_end) + ") 重叠");
      }
      if (fdt_addr <= initrd_addr && initrd_addr < fdt_addr + fdt_reserve) {
        err("LINUX_INITRAMFS_ADDR=" + hex0x(initrd_addr) + " 落在 DTB "
            "保留区 [" + hex0x(fdt_addr) + ", " + hex0x(fdt_addr + fdt_reserve) +
            ") 内");
      }
    }
  }

  // U-Boot EFI 路径：U-Boot 自己占 0x80200000 起的映像区，EFI 应用/
  // initramfs/内核源文件依次放在固定地址，DTB 放在 RAM 末尾前 1 MiB。
  // 实际大小由 scripts/build_uboot_npc.sh 检查；这里先拒绝明显越界、
  // 未对齐和相互重叠的配置。
  if (as_bool(values, "NPC_UBOOT_ENABLE", false)) {
    if (!as_bool(values, "SBI_ENABLE", false)) {
      err("NPC_UBOOT_ENABLE=y 需要 SBI_ENABLE=y");
    }
    if (!as_bool(values, "SBI_FDT_ENABLE", false)) {
      err("NPC_UBOOT_ENABLE=y 需要 SBI_FDT_ENABLE=y（U-Boot 需要 a1=DTB）");
    }
    const long long next_addr = as_int(values, "SBI_NEXT_ADDR", 0x80200000);
    const long long fdt_addr = as_int(values, "SBI_FDT_ADDR", 0x80F00000);
    constexpr long long fdt_reserve = 0x10000;
    const std::vector<std::pair<std::string, long long>> uboot_payloads = {
        {"NPC_UBOOT_EFI_APP_ADDR", 0x80600000},
        {"NPC_UBOOT_INITRAMFS_ADDR", 0x80800000},
        {"NPC_UBOOT_KERNEL_ADDR", 0x81000000},
    };
    long long previous_end = next_addr + 0x1000;
    for (const auto& [symbol, fallback] : uboot_payloads) {
      const long long address = as_int(values, symbol, fallback);
      if (address % 4096 != 0) {
        err(symbol + "=" + hex0x(address) + " 必须按 4 KiB 对齐");
      }
      if (address < next_addr + 0x1000) {
        err(symbol + "=" + hex0x(address) + " 覆盖 U-Boot 入口区 " +
            hex0x(next_addr) + ".." + hex0x(next_addr + 0x1000));
      }
      if (address < previous_end) {
        err(symbol + "=" + hex0x(address) + " 与前一个载荷区结束地址 " +
            hex0x(previous_end) + " 重叠或顺序错误");
      }
      if (address + 0x1000 > ram_end) {
        err(symbol + "=" + hex0x(address) + " 超出 NPC RAM 结束 " +
            hex0x(ram_end));
      }
      previous_end = address + 0x1000;
    }
    if (fdt_addr < ram_base || fdt_addr + fdt_reserve > ram_end) {
      err("SBI_FDT_ADDR=" + hex0x(fdt_addr) + " 的 64 KiB 保留区超出 "
          "NPC RAM [0x80000000, " + hex0x(ram_end) + ")");
    }
    if (fdt_addr < previous_end) {
      err("SBI_FDT_ADDR=" + hex0x(fdt_addr) + " 与 EFI 载荷区结束地址 " +
          hex0x(previous_end) + " 重叠或顺序错误");
    }
  }

  return errors;
}

Config parse_config(std::istream& input) {
  Config values;
  std::string raw;
  while (std::getline(input, raw)) {
    const std::string line = strip(raw);
    if (line.empty() || line.front() == '#') {
      continue;
    }
    if (line.rfind("CONFIG_", 0) != 0) {
      continue;
    }
    std::size_t index = 7;
    while (index < line.size()) {
      const auto ch = static_cast<unsigned char>(line[index]);
      if (!(std::isalnum(ch) || ch == '_')) {
        break;
      }
      ++index;
    }
    if (index == 7 || index >= line.size() || line[index] != '=') {
      continue;
    }
    const std::string key = line.substr(7, index - 7);
    const std::string text = strip(std::string_view(line).substr(index + 1));
    if (text.size() >= 2 && text.front() == '"' && text.back() == '"') {
      values[key] = Value::make_string(text.substr(1, text.size() - 2));
    } else if (text == "y") {
      values[key] = Value::make_bool(true);
    } else if (text == "n") {
      values[key] = Value::make_bool(false);
    } else if (const auto integer = parse_integer(text)) {
      values[key] = Value::make_int(*integer);
    } else {
      values[key] = Value::make_string(text);
    }
  }
  return values;
}

Config parse_config(const fs::path& path) {
  std::ifstream input(path, std::ios::binary);
  if (!input) {
    throw std::runtime_error("无法打开配置文件: " + path.string());
  }
  return parse_config(input);
}

std::vector<std::string> check_file(const fs::path& path) {
  return validate_config(parse_config(path), path.filename().string());
}

fs::path executable_path(const char* argv0) {
  std::error_code ec;
  const fs::path absolute = fs::absolute(fs::path(argv0), ec);
  if (ec) {
    return fs::path(argv0);
  }
  const fs::path canonical = fs::weakly_canonical(absolute, ec);
  return ec ? absolute : canonical;
}

fs::path default_configs_dir(const char* argv0) {
  std::error_code ec;
  const fs::path exe = executable_path(argv0);
  // 工具默认构建到 npc/tools/build/kconfig_contract。
  const fs::path from_exe =
      exe.parent_path().parent_path().parent_path() / "configs";
  if (fs::is_directory(from_exe, ec)) {
    return from_exe;
  }
  const fs::path from_cwd = fs::current_path(ec) / "configs";
  if (fs::is_directory(from_cwd, ec)) {
    return from_cwd;
  }
  return from_exe;
}

struct SelfTest {
  int passed = 0;
  int failed = 0;

  static Config base_rv64() {
    Config values;
    values["XLEN"] = Value::make_int(64);
    values["HAS_S"] = Value::make_bool(true);
    values["HAS_MMU"] = Value::make_bool(true);
    values["MMU_LEVELS"] = Value::make_int(3);
    values["MMU_PTE_SIZE_BYTES"] = Value::make_int(8);
    values["MMU_PAGE_SIZE_BYTES"] = Value::make_int(4096);
    values["MMU_PAGE_TABLE_SIZE_BYTES"] = Value::make_int(4096);
    values["MMU_GEOMETRY_MODE"] = Value::make_string("standard");
    values["MMU_PTE_FORMAT"] = Value::make_string("riscv64");
    values["MMU_AD_MODE"] = Value::make_string("fault");
    return values;
  }

  static Config base_custom_rv64() {
    Config values = base_rv64();
    values["MMU_LEVELS"] = Value::make_int(3);
    values["MMU_PAGE_SIZE_BYTES"] = Value::make_int(1024);
    values["MMU_PAGE_TABLE_SIZE_BYTES"] = Value::make_int(16384);
    values["MMU_GEOMETRY_MODE"] = Value::make_string("custom");
    values["MMU_CUSTOM_SATP_MODE"] = Value::make_int(10);
    values["MMU_PTE_FORMAT"] = Value::make_string("custom");
    values["MMU_SUPERPAGE"] = Value::make_bool(false);
    return values;
  }

  static Config base_sbi() {
    Config values = base_rv64();
    values["RISCV_A"] = Value::make_bool(true);
    values["HAS_PMP"] = Value::make_bool(true);
    values["SBI_ENABLE"] = Value::make_bool(true);
    values["SBI_IMPL_OPENSBI"] = Value::make_bool(true);
    values["SBI_PLATFORM"] = Value::make_string("npc");
    values["SBI_FW_BASE"] = Value::make_int(0x80000000);
    values["SBI_FW_SIZE"] = Value::make_int(0x00200000);
    values["SBI_NEXT_ADDR"] = Value::make_int(0x80200000);
    return values;
  }

  static Config base_linux() {
    Config values = base_sbi();
    values["RISCV_M"] = Value::make_bool(true);
    values["RISCV_A"] = Value::make_bool(true);
    values["SBI_FDT_ENABLE"] = Value::make_bool(true);
    values["SBI_FDT_PATH"] = Value::make_string("dts/npc.dts");
    values["SBI_FDT_ADDR"] = Value::make_int(0x83F00000);
    values["LINUX_BOOT_ENABLE"] = Value::make_bool(true);
    values["LINUX_IMAGE_PATH"] = Value::make_string("Image");
    values["LINUX_BOOTARGS"] =
        Value::make_string("console=ttyS0,115200 earlycon=sbi");
    values["LINUX_INITRAMFS_ENABLE"] = Value::make_bool(true);
    values["LINUX_INITRAMFS_PATH"] = Value::make_string("initramfs.cpio");
    values["LINUX_INITRAMFS_ADDR"] = Value::make_int(0x82000000);
    return values;
  }

  void expect_ok(const std::string& name, const Config& values) {
    try {
      const auto errors = validate_config(values);
      if (errors.empty()) {
        ++passed;
        return;
      }
      ++failed;
      std::cerr << "[FAIL] self-test " << name
                << ": 期望配置通过，但得到 " << errors.size() << " 个错误\n";
      for (const auto& error : errors) {
        std::cerr << "       " << error << '\n';
      }
    } catch (const std::exception& ex) {
      ++failed;
      std::cerr << "[FAIL] self-test " << name
                << ": 校验抛出异常: " << ex.what() << '\n';
    }
  }

  void expect_error(const std::string& name, const Config& values,
                    const std::string& needle) {
    try {
      const auto errors = validate_config(values);
      if (errors.empty()) {
        ++failed;
        std::cerr << "[FAIL] self-test " << name
                  << ": 期望配置被拒绝，但校验通过\n";
        return;
      }
      const bool found = std::any_of(
          errors.begin(), errors.end(), [&needle](const std::string& error) {
            return error.find(needle) != std::string::npos;
          });
      if (!found) {
        ++failed;
        std::cerr << "[FAIL] self-test " << name << ": 错误信息未包含 '"
                  << needle << "'\n";
        for (const auto& error : errors) {
          std::cerr << "       " << error << '\n';
        }
        return;
      }
      ++passed;
    } catch (const std::exception& ex) {
      ++failed;
      std::cerr << "[FAIL] self-test " << name
                << ": 校验抛出异常: " << ex.what() << '\n';
    }
  }

  void expect_true(const std::string& name, bool condition,
                   const std::string& detail = {}) {
    if (condition) {
      ++passed;
      return;
    }
    ++failed;
    std::cerr << "[FAIL] self-test " << name;
    if (!detail.empty()) {
      std::cerr << ": " << detail;
    }
    std::cerr << '\n';
  }

  int run() {
    test_parser();

    Config values;

    values = base_rv64();
    values["XLEN"] = Value::make_int(33);
    expect_error("xlen_33_rejected", values, "XLEN=33");

    values = base_rv64();
    values["SOC_XLEN_FOLLOW_CPU"] = Value::make_bool(false);
    values["SOC_XLEN"] = Value::make_int(48);
    expect_error("soc_xlen_48_rejected", values, "SOC_XLEN=48");

    values = base_rv64();
    values["SOC_XLEN_FOLLOW_CPU"] = Value::make_bool(true);
    values["SOC_XLEN"] = Value::make_int(48);
    expect_ok("soc_xlen_follow_ignores_value", values);

    values = base_custom_rv64();
    values["MMU_LEVELS"] = Value::make_int(5);
    values["MMU_PAGE_SIZE_BYTES"] = Value::make_int(4096);
    values["MMU_PAGE_TABLE_SIZE_BYTES"] = Value::make_int(4096);
    expect_ok("mmu_levels_5_accepted_with_4k_table", values);

    values = base_custom_rv64();
    values["MMU_LEVELS"] = Value::make_int(9);
    expect_error("mmu_levels_9_rejected", values, "1..8");

    values = base_custom_rv64();
    values["MMU_LEVELS"] = Value::make_int(0);
    expect_error("mmu_levels_0_rejected", values, "1..8");

    values = base_custom_rv64();
    values["MMU_LEVELS"] = Value::make_int(6);
    values["MMU_PAGE_SIZE_BYTES"] = Value::make_int(4096);
    values["MMU_PAGE_TABLE_SIZE_BYTES"] = Value::make_int(4096);
    expect_error("6level_4k_table_rejected", values, "地址位宽=66");

    values = base_custom_rv64();
    values["MMU_PTE_SIZE_BYTES"] = Value::make_int(6);
    expect_error("pte_size_6_rejected", values, "2 的幂");

    values = base_custom_rv64();
    values["MMU_PTE_SIZE_BYTES"] = Value::make_int(16);
    expect_ok("pte_size_16_is_geometry_checked", values);

    values = base_custom_rv64();
    values["MMU_PTE_READ_BIT"] = Value::make_int(2);
    values["MMU_PTE_WRITE_BIT"] = Value::make_int(2);
    expect_error("pte_field_overlap_rejected", values, "不能重复");

    values = base_custom_rv64();
    values.erase("MMU_GEOMETRY_MODE");
    values.erase("MMU_PTE_FORMAT");
    values.erase("MMU_AD_MODE");
    values["MMU_GEOMETRY_CUSTOM"] = Value::make_bool(true);
    values["MMU_PTE_CUSTOM"] = Value::make_bool(true);
    values["MMU_AD_FAULT"] = Value::make_bool(true);
    expect_ok("savedefconfig_sparse_choices_are_inferred", values);

    values = base_custom_rv64();
    values["MMU_PAGE_SIZE_BYTES"] = Value::make_int(3072);
    expect_error("page_size_not_power_of_two_rejected", values, "必须是 2 的幂");

    values = base_custom_rv64();
    values["MMU_LEVELS"] = Value::make_int(4);
    values["MMU_PAGE_SIZE_BYTES"] = Value::make_int(2 * 1024 * 1024);
    values["MMU_PAGE_TABLE_SIZE_BYTES"] = Value::make_int(4096);
    values["MMU_SUPERPAGE"] = Value::make_bool(true);
    expect_ok("page_size_2mib_accepted_for_large_geometry", values);

    values = base_custom_rv64();
    values["MMU_LEVELS"] = Value::make_int(5);
    values["MMU_PAGE_SIZE_BYTES"] = Value::make_int(2 * 1024 * 1024);
    values["MMU_PAGE_TABLE_SIZE_BYTES"] = Value::make_int(4096);
    expect_error("page_size_2mib_with_5level_rejected", values, "地址位宽=66");

    values = base_custom_rv64();
    values["MMU_LEVELS"] = Value::make_int(5);
    values["MMU_PAGE_SIZE_BYTES"] = Value::make_int(2 * 1024 * 1024);
    values["MMU_PAGE_TABLE_SIZE_BYTES"] = Value::make_int(2048);
    values["MMU_SUPERPAGE"] = Value::make_bool(true);
    expect_ok("page_size_2mib_5level_small_table_accepted", values);

    values = base_custom_rv64();
    values["MMU_LEVELS"] = Value::make_int(6);
    values["MMU_PAGE_SIZE_BYTES"] = Value::make_int(4 * 1024 * 1024);
    values["MMU_PAGE_TABLE_SIZE_BYTES"] = Value::make_int(512);
    values["MMU_SUPERPAGE"] = Value::make_bool(true);
    expect_ok("4mib_6level_small_table_accepted", values);

    values = base_custom_rv64();
    values["MMU_PAGE_SIZE_BYTES"] = Value::make_int(2LL * 1024 * 1024 * 1024);
    expect_error("page_size_beyond_int_power_of_two_rejected", values, "1GiB");

    values = base_rv64();
    values["XLEN"] = Value::make_int(32);
    values["MMU_LEVELS"] = Value::make_int(2);
    values["MMU_PTE_SIZE_BYTES"] = Value::make_int(4);
    values["MMU_PAGE_SIZE_BYTES"] = Value::make_int(1024);
    values["MMU_PAGE_TABLE_SIZE_BYTES"] = Value::make_int(8192);
    values["MMU_GEOMETRY_MODE"] = Value::make_string("custom");
    values["MMU_PTE_FORMAT"] = Value::make_string("riscv32");
    expect_error("custom_geometry_requires_rv64", values, "只用于 RV64");

    values = base_custom_rv64();
    values["MMU_CUSTOM_SATP_MODE"] = Value::make_int(8);
    expect_error("custom_satp_mode_8_rejected", values, "satp MODE 10..15");

    values = base_custom_rv64();
    values["MMU_AD_MODE"] = Value::make_string("hardware");
    values["MMU_PTE_A_BIT"] = Value::make_int(32);
    values["MMU_PTE_D_BIT"] = Value::make_int(33);
    expect_error("custom_ad_high_bits_rejected", values, "A/D 位");

    values = base_rv64();
    values["MMU_LEVELS"] = Value::make_int(2);
    expect_error("standard_rv64_levels_2_rejected", values, "Sv39");

    values = base_rv64();
    values["MMU_PTE_SIZE_BYTES"] = Value::make_int(16);
    expect_error("standard_rv64_rejects_nonstandard_pte_size", values,
                 "PTE_SIZE_BYTES=8");

    // ---- F/D 与 FPU 后端 ------------------------------------------------
    values = {{"XLEN", Value::make_int(32)}};
    expect_ok("fpu_defaults_keep_fd_off_and_backend_none", values);

    values = {{"XLEN", Value::make_int(32)},
              {"RISCV_F", Value::make_bool(true)},
              {"FPU_IMPL", Value::make_string("none")}};
    expect_error("fpu_f_without_backend_rejected", values, "FPU_IMPL=none");

    values = {{"XLEN", Value::make_int(32)},
              {"RISCV_D", Value::make_bool(true)},
              {"FPU_IMPL", Value::make_string("hardfloat")}};
    expect_error("fpu_d_without_f_rejected", values, "依赖 RISCV_F=y");

    values = {{"XLEN", Value::make_int(32)},
              {"RISCV_F", Value::make_bool(true)},
              {"RISCV_D", Value::make_bool(true)},
              {"FPU_IMPL", Value::make_string("hardfloat")},
              {"FPU_FLEN", Value::make_int(32)}};
    expect_error("fpu_d_with_flen32_rejected", values, "FPU_FLEN=64");

    values = {{"XLEN", Value::make_int(32)},
              {"RISCV_F", Value::make_bool(true)},
              {"FPU_IMPL", Value::make_string("hardfloat")},
              {"FPU_FLEN", Value::make_int(48)}};
    expect_error("fpu_flen_48_rejected", values, "FPU_FLEN=48");

    values = {{"XLEN", Value::make_int(32)},
              {"RISCV_F", Value::make_bool(true)},
              {"FPU_IMPL", Value::make_string("external")},
              {"FPU_FLEN", Value::make_int(64)}};
    expect_error("fpu_external_rejected", values, "external");

    values = {{"XLEN", Value::make_int(32)},
              {"RISCV_F", Value::make_bool(true)},
              {"FPU_IMPL", Value::make_string("hardfloat")},
              {"FPU_FLEN", Value::make_int(32)},
              {"FPU_PIPELINE", Value::make_int(2)}};
    expect_ok("fpu_hardfloat_f32_accepted", values);

    values = {{"XLEN", Value::make_int(64)},
              {"RISCV_F", Value::make_bool(true)},
              {"RISCV_D", Value::make_bool(true)},
              {"FPU_IMPL", Value::make_string("fudian")},
              {"FPU_FLEN", Value::make_int(64)}};
    expect_ok("fpu_fudian_fd64_accepted", values);

    values = {{"XLEN", Value::make_int(32)},
              {"RISCV_C", Value::make_bool(true)},
              {"RISCV_F", Value::make_bool(true)},
              {"RISCV_D", Value::make_bool(true)},
              {"FPU_IMPL", Value::make_string("hardfloat")},
              {"FPU_FLEN", Value::make_int(64)}};
    expect_ok("fpu_rv32d_hardfloat_accepted", values);

    values = {{"XLEN", Value::make_int(64)},
              {"RISCV_F", Value::make_bool(true)},
              {"RISCV_D", Value::make_bool(true)},
              {"FPU_IMPL_HARDFLOAT", Value::make_bool(true)},
              {"FPU_FLEN", Value::make_int(64)}};
    expect_ok("fpu_savedefconfig_choice_inferred", values);

    values = {{"XLEN", Value::make_int(32)},
              {"RISCV_F", Value::make_bool(true)},
              {"FPU_IMPL", Value::make_string("fudian")},
              {"FPU_FLEN", Value::make_int(64)},
              {"FPU_DIVSQRT", Value::make_bool(false)}};
    expect_error("fpu_divsqrt_off_rejected", values, "FPU_DIVSQRT=y");

    values = {{"XLEN", Value::make_int(32)},
              {"RISCV_F", Value::make_bool(true)},
              {"FPU_IMPL", Value::make_string("fudian")},
              {"FPU_FLEN", Value::make_int(64)},
              {"FPU_ROUNDING", Value::make_bool(false)}};
    expect_error("fpu_rounding_off_rejected", values, "FPU_ROUNDING=y");

    values = {{"XLEN", Value::make_int(32)},
              {"RISCV_F", Value::make_bool(true)},
              {"FPU_IMPL", Value::make_string("fudian")},
              {"FPU_FLEN", Value::make_int(64)},
              {"FPU_FLAGS", Value::make_bool(false)}};
    expect_error("fpu_flags_off_rejected", values, "FPU_FLAGS=y");

    values = {{"XLEN", Value::make_int(32)},
              {"FPU_IMPL", Value::make_string("fudian")},
              {"FPU_FLEN", Value::make_int(64)}};
    expect_error("fpu_backend_selected_without_f_rejected", values,
                 "FPU_IMPL 必须为 none");

    values = {{"XLEN", Value::make_int(32)},
              {"HAS_S", Value::make_bool(true)},
              {"HAS_MMU", Value::make_bool(true)},
              {"MMU_LEVELS", Value::make_int(2)},
              {"MMU_PTE_SIZE_BYTES", Value::make_int(4)},
              {"MMU_PAGE_SIZE_BYTES", Value::make_int(4096)},
              {"MMU_PAGE_TABLE_SIZE_BYTES", Value::make_int(4096)},
              {"MMU_GEOMETRY_MODE", Value::make_string("standard")},
              {"MMU_PTE_FORMAT", Value::make_string("riscv32")}};
    expect_ok("rv32_standard_still_passes", values);

    values = base_sbi();
    expect_ok("sbi_opensbi_default_layout_passes", values);

    values = base_sbi();
    values["SBI_NEXT_ADDR"] = Value::make_int(0x80100000);
    expect_error("sbi_next_addr_overlaps_firmware_rejected", values, "越过");

    values = base_sbi();
    values["SBI_NEXT_ADDR"] = Value::make_int(0x80300000);
    expect_error("sbi_next_addr_alignment_rejected", values, "2 MiB");

    values = base_sbi();
    values["RISCV_A"] = Value::make_bool(false);
    expect_error("sbi_opensbi_requires_a_extension", values, "RISCV_A=y");

    values = base_sbi();
    values["HAS_PMP"] = Value::make_bool(false);
    expect_error("sbi_opensbi_requires_pmp", values, "HAS_PMP=y");

    values = base_sbi();
    values["SBI_FDT_ENABLE"] = Value::make_bool(true);
    values["SBI_FDT_PATH"] = Value::make_string("dts/npc.dts");
    values["SBI_FDT_ADDR"] = Value::make_int(0x80F00000);
    expect_ok("sbi_fdt_default_layout_passes", values);

    values = base_sbi();
    values["SBI_FDT_ENABLE"] = Value::make_bool(true);
    values["SBI_FDT_ADDR"] = Value::make_int(0x80F00004);
    expect_error("sbi_fdt_unaligned_rejected", values, "8 字节对齐");

    values = base_sbi();
    values["SBI_FDT_ENABLE"] = Value::make_bool(true);
    values["SBI_FDT_ADDR"] = Value::make_int(0x80100000);
    expect_error("sbi_fdt_overlaps_firmware_rejected", values, "SBI 固件区");

    values = base_sbi();
    values["SBI_FDT_ENABLE"] = Value::make_bool(true);
    values["SBI_FDT_ADDR"] = Value::make_int(0x84000000);
    expect_error("sbi_fdt_outside_ram_rejected", values, "NPC RAM");

    values = base_sbi();
    values["SBI_FDT_ENABLE"] = Value::make_bool(true);
    values["SBI_NEXT_ADDR"] = Value::make_int(0x80400000);
    values["SBI_FDT_ADDR"] = Value::make_int(0x803F8000);
    expect_error("sbi_fdt_before_next_stage_must_fit", values, "SBI_NEXT_ADDR");

    values = base_sbi();
    values["SBI_FDT_ENABLE"] = Value::make_bool(false);
    values["SBI_FDT_ADDR"] = Value::make_int(1);
    expect_ok("sbi_fdt_disabled_ignores_addr", values);

    values = base_rv64();
    values["SBI_ENABLE"] = Value::make_bool(false);
    values["SBI_FW_BASE"] = Value::make_int(0);
    values["SBI_NEXT_ADDR"] = Value::make_int(0);
    expect_ok("sbi_disabled_ignores_layout", values);

    values = base_linux();
    expect_ok("linux_standard_layout_passes", values);

    values = base_linux();
    values["SBI_FDT_ENABLE"] = Value::make_bool(false);
    expect_error("linux_requires_fdt", values, "SBI_FDT_ENABLE=y");

    values = base_linux();
    values["MMU_GEOMETRY_MODE"] = Value::make_string("custom");
    values["MMU_LEVELS"] = Value::make_int(3);
    values["MMU_PAGE_SIZE_BYTES"] = Value::make_int(1024);
    values["MMU_PAGE_TABLE_SIZE_BYTES"] = Value::make_int(16384);
    values["MMU_PTE_FORMAT"] = Value::make_string("custom");
    expect_error("linux_custom_mmu_rejected", values, "标准 Sv32/Sv39/Sv48");

    values = base_linux();
    values["LINUX_INITRAMFS_ADDR"] = Value::make_int(0x83F00000);
    expect_error("linux_initramfs_overlaps_dtb_rejected", values, "DTB 保留区");

    values = base_linux();
    values["LINUX_INITRAMFS_ADDR"] = Value::make_int(0x80200000);
    expect_error("linux_initramfs_before_image_rejected", values, "initramfs");

    values = base_linux();
    values["LINUX_INITRAMFS_ADDR"] = Value::make_int(0x82000004);
    expect_error("linux_initramfs_unaligned_rejected", values, "4 KiB 对齐");

    values = base_linux();
    values["LINUX_IMAGE_PATH"] = Value::make_string("");
    expect_error("linux_empty_image_path_rejected", values, "LINUX_IMAGE_PATH");

    if (failed != 0) {
      std::cerr << "[FAIL] Kconfig 配置契约自检: " << failed << "/"
                << (passed + failed) << " 项失败\n";
      return 1;
    }
    std::cout << "[OK] Kconfig 配置契约自检通过: " << passed << " 项\n";
    return 0;
  }

  void test_parser() {
    std::istringstream input(
        "# comment\n"
        "\n"
        "CONFIG_XLEN=64\n"
        "CONFIG_NAME=\"hello world\"\n"
        "CONFIG_FLAG=y\n"
        "CONFIG_OFF=n\n"
        "CONFIG_HEX=0x1234\n"
        "CONFIG_NEG=-3\n"
        "CONFIG_TEXT=abc\n"
        "CONFIG_NUMSTR=\"64\"\n"
        "CONFIG_NOT_MATCHED = 1\n"
        "# CONFIG_SKIPPED is not set\n");
    const Config parsed = parse_config(input);

    expect_true("parser_key_count", parsed.size() == 8,
                "实际解析 " + std::to_string(parsed.size()) + " 个键");
    expect_true("parser_xlen_int",
                as_int(parsed, "XLEN", -1) == 64);
    expect_true("parser_quoted_string",
                as_str(parsed, "NAME", "") == "hello world");
    expect_true("parser_bool_y", as_bool(parsed, "FLAG", false));
    expect_true("parser_bool_n", !as_bool(parsed, "OFF", true));
    expect_true("parser_hex", as_int(parsed, "HEX", 0) == 0x1234);
    expect_true("parser_negative", as_int(parsed, "NEG", 0) == -3);
    expect_true("parser_plain_string", as_str(parsed, "TEXT", "") == "abc");
    expect_true("parser_quoted_integer", as_int(parsed, "NUMSTR", 0) == 64);
    expect_true("parser_requires_equals",
                parsed.find("NOT_MATCHED") == parsed.end());
    expect_true("parser_skips_not_set",
                parsed.find("SKIPPED") == parsed.end());

    Config defaults;
    expect_ok("empty_config_uses_defaults", defaults);
  }
};

void print_usage(std::ostream& out, const char* program) {
  out << "Kconfig 配置契约校验（C++）\n\n"
      << "用法:\n"
      << "  " << program << " --config PATH\n"
      << "  " << program << " --defconfigs [--defconfigs-dir DIR]\n"
      << "  " << program << " --self-test\n"
      << "  " << program << " --help\n\n"
      << "选项:\n"
      << "  --config PATH          校验一个 .config/defconfig\n"
      << "  --defconfigs           校验 DIR 下所有 *_defconfig（默认 npc/configs）\n"
      << "  --defconfigs-dir DIR   配合 --defconfigs 指定目录\n"
      << "  --self-test           运行 C++ 内置回归自检（不需要 Python）\n"
      << "  --quiet                成功时不打印逐文件信息\n"
      << "  -h, --help             显示本帮助\n";
}

int run_defconfigs(const fs::path& dir, bool quiet) {
  std::error_code ec;
  if (!fs::is_directory(dir, ec)) {
    std::cerr << "错误: 没有找到 " << dir.string() << "/*_defconfig\n";
    return 2;
  }

  std::vector<fs::path> files;
  for (const auto& entry : fs::directory_iterator(dir, ec)) {
    if (ec) {
      break;
    }
    if (!entry.is_regular_file()) {
      continue;
    }
    const std::string name = entry.path().filename().string();
    if (name.size() >= std::string("_defconfig").size() &&
        name.compare(name.size() - std::string("_defconfig").size(),
                     std::string("_defconfig").size(), "_defconfig") == 0) {
      files.push_back(entry.path());
    }
  }
  if (ec) {
    std::cerr << "错误: 无法读取目录 " << dir.string() << ": " << ec.message()
              << '\n';
    return 2;
  }
  std::sort(files.begin(), files.end(),
            [](const fs::path& lhs, const fs::path& rhs) {
              return lhs.filename().string() < rhs.filename().string();
            });
  if (files.empty()) {
    std::cerr << "错误: 没有找到 " << dir.string() << "/*_defconfig\n";
    return 2;
  }

  int failed = 0;
  for (const auto& path : files) {
    std::vector<std::string> errors;
    try {
      errors = check_file(path);
    } catch (const std::exception& ex) {
      std::cerr << "[FAIL] " << path.filename().string() << ": "
                << ex.what() << '\n';
      ++failed;
      continue;
    }
    if (!errors.empty()) {
      ++failed;
      for (const auto& message : errors) {
        std::cerr << "[FAIL] " << message << '\n';
      }
    } else if (!quiet) {
      std::cout << "[OK] " << path.filename().string() << '\n';
    }
  }
  if (failed != 0) {
    std::cerr << "[FAIL] " << failed << "/" << files.size()
              << " 个 defconfig 违反配置契约\n";
    return 1;
  }
  if (!quiet) {
    std::cout << "[OK] " << files.size() << " 个 defconfig 全部通过配置契约校验\n";
  }
  return 0;
}

}  // namespace

int main(int argc, char** argv) {
  bool config_mode = false;
  bool defconfigs_mode = false;
  bool self_test = false;
  bool quiet = false;
  std::optional<fs::path> config_path;
  std::optional<fs::path> defconfigs_dir;

  for (int i = 1; i < argc; ++i) {
    const std::string arg = argv[i];
    if (arg == "--help" || arg == "-h") {
      print_usage(std::cout, argv[0]);
      return 0;
    }
    if (arg == "--config") {
      if (i + 1 >= argc) {
        std::cerr << "错误: --config 需要一个路径\n";
        return 2;
      }
      config_mode = true;
      config_path = fs::path(argv[++i]);
      continue;
    }
    if (arg == "--defconfigs") {
      defconfigs_mode = true;
      continue;
    }
    if (arg == "--defconfigs-dir") {
      if (i + 1 >= argc) {
        std::cerr << "错误: --defconfigs-dir 需要一个目录\n";
        return 2;
      }
      defconfigs_dir = fs::path(argv[++i]);
      continue;
    }
    if (arg == "--self-test") {
      self_test = true;
      continue;
    }
    if (arg == "--quiet") {
      quiet = true;
      continue;
    }
    std::cerr << "错误: 未知参数 " << arg << '\n';
    print_usage(std::cerr, argv[0]);
    return 2;
  }

  const int mode_count = static_cast<int>(config_mode) +
                         static_cast<int>(defconfigs_mode) +
                         static_cast<int>(self_test);
  if (mode_count != 1) {
    std::cerr << "错误: 必须且只能指定 --config、--defconfigs 或 --self-test 之一\n";
    print_usage(std::cerr, argv[0]);
    return 2;
  }
  if (defconfigs_dir && !defconfigs_mode) {
    std::cerr << "错误: --defconfigs-dir 只能与 --defconfigs 一起使用\n";
    return 2;
  }

  try {
    if (config_mode) {
      std::error_code ec;
      if (!fs::is_regular_file(*config_path, ec)) {
        std::cerr << "错误: 配置文件不存在: " << config_path->string() << '\n';
        return 2;
      }
      const std::vector<std::string> errors = check_file(*config_path);
      if (!errors.empty()) {
        for (const auto& message : errors) {
          std::cerr << "[FAIL] " << message << '\n';
        }
        return 1;
      }
      if (!quiet) {
        std::cout << "[OK] Kconfig 配置契约校验通过: " << config_path->string()
                  << '\n';
      }
      return 0;
    }

    if (self_test) {
      SelfTest test;
      return test.run();
    }

    return run_defconfigs(defconfigs_dir ? *defconfigs_dir
                                         : default_configs_dir(argv[0]),
                          quiet);
  } catch (const std::exception& ex) {
    std::cerr << "错误: " << ex.what() << '\n';
    return 1;
  }
}
