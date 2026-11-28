module;
#include "npc_config_defaults.h"
module npc.kconfig_contract;
import std;
namespace npc::kconfig_contract {
namespace defaults = config_defaults;
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
  static Config base_fpga_amd() {
    Config values;
    values["NPC_MODE_BOTH"] = Value::make_bool(true);
    values["FPGA_VENDOR"] = Value::make_string("amd");
    values["FPGA_FAMILY"] = Value::make_string("ultrascale_plus");
    values["FPGA_DEVICE"] = Value::make_string("xczu9eg-ffvb1156-2-e");
    values["FPGA_TOOLCHAIN"] = Value::make_string("vivado");
    values["FPGA_BITSTREAM_FORMAT"] = Value::make_string("bit");
    values["FPGA_MEMORY_MODEL"] = Value::make_string("ddr");
    values["FPGA_DDR_TYPE"] = Value::make_string("ddr4");
    values["FPGA_DDR_DATA_WIDTH"] = Value::make_int(64);
    values["FPGA_DDR_CONTROLLER"] = Value::make_string("ysyx_soc");
    values["FPGA_DDR_PHY"] = Value::make_string("vendor");
    values["FPGA_DDR_VOLTAGE"] = Value::make_string("1.2");
    values["FPGA_DDR_BANKS"] = Value::make_int(8);
    values["FPGA_PROGRAM_MODE"] = Value::make_string("jtag");
    values["FPGA_FLASH_OFFSET"] = Value::make_int(0);
    return values;
  }
  static Config base_fpga_intel() {
    Config values;
    values["NPC_MODE_BOTH"] = Value::make_bool(true);
    values["FPGA_VENDOR"] = Value::make_string("intel");
    values["FPGA_FAMILY"] = Value::make_string("arria_10");
    values["FPGA_DEVICE"] = Value::make_string("10AX115N2F45E1SG");
    values["FPGA_TOOLCHAIN"] = Value::make_string("quartus");
    values["FPGA_BITSTREAM_FORMAT"] = Value::make_string("sof");
    values["FPGA_MEMORY_MODEL"] = Value::make_string("bram_only");
    values["FPGA_PROGRAM_MODE"] = Value::make_string("jtag");
    values["FPGA_FLASH_OFFSET"] = Value::make_int(0);
    return values;
  }
  static Config base_soc() {
    Config values;
    values["NPC_BUILD_YSYXSOC"] = Value::make_bool(true);
    return values;
  }
  static std::map<std::string, long long> base_soc_address_map() {
    return {
        {"uartBase", 0x10000000LL},
        {"uartSize", 0x00001000LL},
        {"gpioBase", 0x10002000LL},
        {"gpioSize", 0x00000010LL},
        {"keyboardBase", 0x10011000LL},
        {"keyboardSize", 0x00000008LL},
        {"vgaBase", 0x21000000LL},
        {"vgaSize", 0x00200000LL},
        {"sramBase", 0x0f000000LL},
        {"sramSize", 0x00008000LL},
        {"chiplinkMemBase", 0xc0000000LL},
        {"chiplinkMemSize", 0x40000000LL},
        {"chiplinkMmioBase", 0x40000000LL},
        {"chiplinkMmioSize", 0x40000000LL},
    };
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
  void expect_soc_ok(const std::string& name, const Config& values,
                     const std::map<std::string, long long>& addresses) {
    try {
      const auto errors = validate_soc_rtl(values, addresses);
      if (errors.empty()) {
        ++passed;
        return;
      }
      ++failed;
      std::cerr << "[FAIL] self-test " << name
                << ": 期望SoC地址契约通过，但得到 " << errors.size()
                << " 个错误\n";
      for (const auto& error : errors) {
        std::cerr << "       " << error << '\n';
      }
    } catch (const std::exception& ex) {
      ++failed;
      std::cerr << "[FAIL] self-test " << name
                << ": SoC地址契约校验抛出异常: " << ex.what() << '\n';
    }
  }
  void expect_soc_error(const std::string& name, const Config& values,
                        const std::map<std::string, long long>& addresses,
                        const std::string& needle) {
    try {
      const auto errors = validate_soc_rtl(values, addresses);
      if (errors.empty()) {
        ++failed;
        std::cerr << "[FAIL] self-test " << name
                  << ": 期望SoC地址契约被拒绝，但校验通过\n";
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
                << ": SoC地址契约校验抛出异常: " << ex.what() << '\n';
    }
  }
  void expect_effective_ok(const std::string& name, const Config& requested,
                           const Config& effective) {
    try {
      const auto errors =
          check_effective_config(requested, effective, "self-test.typed.json flat");
      if (errors.empty()) {
        ++passed;
        return;
      }
      ++failed;
      std::cerr << "[FAIL] self-test " << name
                << ": 期望 effective 契约通过，但得到 " << errors.size()
                << " 个错误\n";
      for (const auto& error : errors) {
        std::cerr << "       " << error << '\n';
      }
    } catch (const std::exception& ex) {
      ++failed;
      std::cerr << "[FAIL] self-test " << name
                << ": effective 校验抛出异常: " << ex.what() << '\n';
    }
  }
  void expect_effective_error(const std::string& name, const Config& requested,
                              const Config& effective,
                              const std::string& needle) {
    try {
      const auto errors =
          check_effective_config(requested, effective, "self-test.typed.json flat");
      if (errors.empty()) {
        ++failed;
        std::cerr << "[FAIL] self-test " << name
                  << ": 期望 effective 契约被拒绝，但校验通过\n";
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
                << ": effective 校验抛出异常: " << ex.what() << '\n';
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
  void test_default_tables() {
    expect_true("default_npc_ram_base",
                defaults::NpcRamBase() == 0x80000000LL);
    expect_true("default_npc_ram_size",
                defaults::NpcRamSize() == 0x04000000LL);
    expect_true("default_sbi_fw_base_follows_ram",
                defaults::SbiFwBase(defaults::NpcRamBase()) ==
                    defaults::NpcRamBase());
    expect_true("default_sbi_next_addr_rv32",
                defaults::SbiNextAddr(32) == 0x80400000LL);
    expect_true("default_sbi_next_addr_rv64",
                defaults::SbiNextAddr(64) == 0x80200000LL);
    expect_true("default_sbi_fdt_addr",
                defaults::SbiFdtAddr() == 0x80f00000LL);
    expect_true("default_linux_initramfs_addr",
                defaults::LinuxInitramfsAddr() == 0x82000000LL);
    expect_true("default_pma_psram_act",
                defaults::PmaPsramBase(0, 0) == 0x80000000LL);
    expect_true("default_pma_psram_linux",
                defaults::PmaPsramBase(1, 0) == 0xb0000000LL);
    expect_true("default_pma_sdram_act_base",
                defaults::PmaSdramBase(0, 0) == 0xa0000000LL);
    expect_true("default_pma_sdram_act_size",
                defaults::PmaSdramSize(0, 0) == 0x02000000LL);
    expect_true("default_pma_sdram_linux_base",
                defaults::PmaSdramBase(1, 0) == 0x80000000LL);
    expect_true("default_pma_sdram_linux_size",
                defaults::PmaSdramSize(1, 0) == 0x08000000LL);
    expect_true("default_pma_reset_follows_mrom",
                defaults::PmaSocResetAddr(defaults::PmaMromBase()) ==
                    defaults::PmaMromBase());
  }
  int run() {
    test_default_tables();
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
    values = base_rv64();
    values["APB_XLEN_FOLLOW_SOC"] = Value::make_bool(false);
    values["APB_XLEN"] = Value::make_int(48);
    expect_error("apb_xlen_48_rejected", values, "APB_XLEN=48");
    values = base_rv64();
    values["UART_XLEN_FOLLOW_APB"] = Value::make_bool(false);
    values["UART_XLEN"] = Value::make_int(48);
    expect_error("uart_xlen_48_rejected", values, "UART_XLEN=48");
    values = base_rv64();
    values["UART_XLEN_FOLLOW_APB"] = Value::make_bool(true);
    values["UART_XLEN"] = Value::make_int(48);
    expect_ok("uart_xlen_follow_ignores_value", values);
    values = base_rv64();
    values["INTERNAL_BUS_WIDTH_FOLLOW_CPU"] = Value::make_bool(false);
    values["INTERNAL_BUS_WIDTH"] = Value::make_int(48);
    expect_error("internal_bus_width_48_rejected", values,
                 "INTERNAL_BUS_WIDTH=48");
    values = base_rv64();
    values["INTERNAL_BUS_WIDTH_FOLLOW_CPU"] = Value::make_bool(true);
    values["INTERNAL_BUS_WIDTH"] = Value::make_int(48);
    expect_ok("internal_bus_width_follow_ignores_value", values);
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
    values["MMU_PTE_READ_BIT"] = Value::make_int(64);
    expect_error("pte_field_out_of_range_rejected", values,
                 "PTE 标志位必须位于 0..63");
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
              {"FPU_IMPL", Value::make_string("hardfloat")},
              {"FPU_FLEN", Value::make_int(32)}};
    expect_error("fpu_rv64_f32_rejected", values, "FPU_FLEN 必须为 64");
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
    values["XLEN"] = Value::make_int(32);
    values.erase("SBI_NEXT_ADDR");
    expect_ok("sbi_rv32_default_next_addr_passes", values);
    values = base_sbi();
    values.erase("SBI_FW_BASE");
    values.erase("SBI_NEXT_ADDR");
    values.erase("SBI_FDT_ADDR");
    values["SBI_FDT_ENABLE"] = Value::make_bool(true);
    values["SBI_FDT_PATH"] = Value::make_string("dts/npc.dts");
    expect_ok("sbi_default_layout_from_shared_table_passes", values);
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
    values.erase("SBI_FDT_ADDR");
    values.erase("LINUX_INITRAMFS_ADDR");
    expect_ok("linux_default_fdt_and_initramfs_pass", values);
    values = base_soc();
    values["SOC_MEMORY_MAP_LINUX"] = Value::make_bool(true);
    expect_ok("linux_memory_map_pma_defaults_pass", values);
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
    values = base_fpga_amd();
    expect_ok("fpga_amd_valid", values);
    values = base_fpga_intel();
    expect_ok("fpga_intel_valid", values);
    values = base_fpga_amd();
    values["FPGA_TOOLCHAIN"] = Value::make_string("quartus");
    expect_error("fpga_amd_requires_vivado", values, "FPGA_TOOLCHAIN=vivado");
    values = base_fpga_amd();
    values["FPGA_FAMILY"] = Value::make_string("cyclone_10");
    expect_error("fpga_amd_family_rejected", values, "不是 AMD 系列");
    values = base_fpga_intel();
    values["FPGA_BITSTREAM_FORMAT"] = Value::make_string("bit");
    expect_error("fpga_intel_bitstream_rejected", values, "不是 Intel 格式");
    values = base_fpga_amd();
    values["FPGA_DDR_DATA_WIDTH"] = Value::make_int(10);
    expect_error("fpga_ddr_width_rejected", values, "2 的幂");
    values = base_fpga_amd();
    values["FPGA_ILA_ENABLE"] = Value::make_bool(true);
    values["FPGA_ILA_DEPTH"] = Value::make_int(1000);
    expect_error("fpga_ila_depth_rejected", values, "2 的幂");
    values = base_fpga_intel();
    values["FPGA_URAM_ENABLE"] = Value::make_bool(true);
    expect_error("fpga_uram_vendor_rejected", values, "URAM");
    values = base_fpga_amd();
    values["FPGA_SIGNALTAP_ENABLE"] = Value::make_bool(true);
    expect_error("fpga_signaltap_vendor_rejected", values, "只适用于 Intel");
    const auto socAddresses = base_soc_address_map();
    values = base_soc();
    expect_soc_ok("soc_pma_defaults_match_rtl", values, socAddresses);
    values = base_soc();
    values["PMA_UART_BASE"] = Value::make_int(0x10001000);
    expect_soc_error("soc_uart_base_mismatch_rejected", values, socAddresses,
                     "PMA_UART_BASE");
    values = base_soc();
    values["PMA_VGA_SIZE"] = Value::make_int(0x00100000);
    expect_soc_error("soc_vga_size_mismatch_rejected", values, socAddresses,
                     "PMA_VGA_SIZE");
    values = base_soc();
    values["PMA_SRAM_BASE"] = Value::make_int(0x0e000000);
    expect_soc_error("soc_sram_base_mismatch_rejected", values, socAddresses,
                     "PMA_SRAM_BASE");
    values = base_soc();
    values["PMA_CHIPLINK_MEM_BASE"] = Value::make_int(0x80000000);
    expect_soc_error("soc_chiplink_mem_base_mismatch_rejected", values,
                     socAddresses, "PMA_CHIPLINK_MEM_BASE");
    values = base_soc();
    values["CHIPLINK"] = Value::make_bool(false);
    values["PMA_CHIPLINK_MEM_BASE"] = Value::make_int(0x80000000);
    expect_soc_error("soc_chiplink_disabled_still_checked", values,
                     socAddresses, "PMA_CHIPLINK_MEM_BASE");
    values = base_soc();
    values["NPC_BUILD_YSYXSOC"] = Value::make_bool(false);
    values["PMA_UART_BASE"] = Value::make_int(0x10001000);
    expect_soc_ok("soc_contract_skips_non_soc_build", values, socAddresses);
    auto missingAddresses = socAddresses;
    missingAddresses.erase("gpioBase");
    values = base_soc();
    expect_soc_error("soc_missing_rtl_constant_rejected", values,
                     missingAddresses, "gpioBase");
    values = base_rv64();
    values["HAS_TLB"] = Value::make_bool(true);
    values["HAS_MMU"] = Value::make_bool(false);
    expect_error("has_tlb_without_mmu_rejected", values,
                 "HAS_TLB=y 需要 HAS_MMU=y");
    values = base_rv64();
    values["MUL_COMPRESSOR_INPUTS"] = Value::make_int(2);
    expect_error("mul_gpc_inputs_2_rejected", values,
                 "MUL_COMPRESSOR_INPUTS=2");
    values = base_rv64();
    values["MUL_COMPRESSOR_OUTPUTS"] = Value::make_int(65);
    expect_error("mul_gpc_outputs_65_rejected", values,
                 "MUL_COMPRESSOR_OUTPUTS=65");
    values = base_rv64();
    values["MUL_COMPRESSOR_OUTPUTS"] = Value::make_int(3);
    values["MUL_COMPRESSOR_OUTPUT_OFFSETS"] = Value::make_string("0,1");
    expect_error("mul_gpc_offset_count_rejected", values,
                 "输出权重数量必须等于 R");
    values = base_rv64();
    values["MUL_COMPRESSOR_OUTPUTS"] = Value::make_int(2);
    values["MUL_COMPRESSOR_OUTPUT_OFFSETS"] = Value::make_string("0,-1");
    expect_error("mul_gpc_negative_offset_rejected", values,
                 "输出权重偏移必须非负");
    values = base_rv64();
    values["MUL_COMPRESSOR_COUT_OFFSET"] = Value::make_int(-1);
    expect_error("mul_gpc_negative_cout_offset_rejected", values,
                 "Cout 权重偏移必须非负");
    values = base_rv64();
    values["MUL_COMPRESSOR_INPUTS"] = Value::make_int(3);
    values["MUL_COMPRESSOR_OUTPUTS"] = Value::make_int(2);
    values["MUL_COMPRESSOR_OUTPUT_OFFSETS"] = Value::make_string("0, 1");
    values["MUL_COMPRESSOR_COUT_OFFSET"] = Value::make_int(1);
    expect_ok("mul_gpc_valid_offsets_accepted", values);
    Config requested = base_rv64();
    requested["ITRACE"] = Value::make_bool(false);
    requested["ITRACE_WRITE_FILE"] = Value::make_bool(true);
    Config effective = requested;
    effective["ITRACE"] = Value::make_string("y");
    effective["ITRACE_WRITE_FILE"] = Value::make_string("y");
    effective["HAS_S"] = Value::make_string("1");
    effective["MMU_ENABLED"] = Value::make_string("1");
    effective["SOC_XLEN_EFFECTIVE"] = Value::make_string("64");
    expect_effective_ok("effective_derived_normalization_accepted", requested,
                        effective);
    effective = requested;
    effective["XLEN"] = Value::make_string("32");
    expect_effective_error("effective_unexpected_rewrite_rejected", requested,
                           effective, "XLEN: 请求值='64' 与生效值='32'");
    effective = requested;
    effective.erase("XLEN");
    expect_effective_error("effective_missing_symbol_rejected", requested,
                           effective, "XLEN: 请求值='64'");
    requested = base_custom_rv64();
    effective = base_custom_rv64();
    effective["MMU_PTE_READ_BIT"] = Value::make_int(2);
    effective["MMU_PTE_WRITE_BIT"] = Value::make_int(2);
    expect_effective_error("effective_pte_duplicate_rejected", requested,
                           effective, "PTE 标志位不能重复");
    effective = base_custom_rv64();
    effective["MMU_PTE_PPN_HIGH"] = Value::make_int(100);
    expect_effective_error("effective_pte_ppn_range_rejected", requested,
                           effective, "PTE PPN 位范围非法");
    effective = base_custom_rv64();
    effective["MMU_PTE_READ_BIT"] = Value::make_int(64);
    expect_effective_error("effective_pte_flag_range_rejected", requested,
                           effective, "PTE 标志位必须位于 0..63");
    requested = base_rv64();
    requested["MUL_COMPRESSOR_OUTPUTS"] = Value::make_int(2);
    requested["MUL_COMPRESSOR_OUTPUT_OFFSETS"] = Value::make_string("0,1");
    effective = requested;
    effective["MUL_COMPRESSOR_OUTPUTS"] = Value::make_string("3");
    expect_effective_error("effective_mul_gpc_count_rejected", requested,
                           effective, "MUL_COMPRESSOR_OUTPUTS");
    requested = base_rv64();
    requested["HAS_MMU"] = Value::make_bool(false);
    requested["HAS_TLB"] = Value::make_bool(true);
    effective = requested;
    expect_effective_error("effective_tlb_without_mmu_rejected", requested,
                           effective, "HAS_TLB=y 需要 HAS_MMU=y");
    const Config flat = parse_typed_json_flat_text(R"({
      "schema": 1,
      "nested": {"a": [1, 2, {"b": "c"}], "ok": true},
      "flat": {"XLEN": "64", "NAME": "hello, world", "ESC": "a\"b"}
    })");
    expect_true("typed_json_flat_key_count", flat.size() == 3,
                "实际解析 " + std::to_string(flat.size()) + " 个键");
    expect_true("typed_json_flat_xlen",
                as_int(flat, "XLEN", -1) == 64);
    expect_true("typed_json_flat_string",
                as_str(flat, "NAME", "") == "hello, world");
    expect_true("typed_json_flat_escape",
                as_str(flat, "ESC", "") == "a\"b");
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
