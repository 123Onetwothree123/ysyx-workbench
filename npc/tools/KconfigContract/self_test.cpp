module npc.kconfig_contract;
import std;
namespace npc::kconfig_contract {
namespace {
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

}
int run_self_test() {
  SelfTest test;
  return test.run();
}
}
