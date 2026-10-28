module npc.kconfig_contract;
import std;
constexpr long long kMaxMmuLevels = 8;
constexpr long long kPageSizeMin = 4;
constexpr long long kPageSizeMax = 1LL << 30;
constexpr long long kPageTableSizeMin = 8;
constexpr long long kSatpPpnBits = 44;
namespace npc::kconfig_contract {
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
std::vector<std::string> validate_config(const Config& values,
                                         const std::string& source) {
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

}
