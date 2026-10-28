module npc.linux_boot_test;
import std;
namespace npc::linux_boot_test {
void test_isa_matrix(const fs::path &work) {
    struct Case {
        std::string name;
        Config config;
        std::string isa;
        std::string extensions;
        std::string mmu;
    };
    std::vector<Case> cases;
    {
        Config config;
        config.xlen = 32;
        config.levels = 2;
        config.pte_size = 4;
        config.pte_format = "riscv32";
        config.linux = false;
        cases.push_back({"rv32ima", config, "rv32ima_zicsr_zifencei",
                         "i m a zicsr zifencei", "riscv,sv32"});
    }
    {
        Config config;
        config.linux = false;
        cases.push_back({"rv64ima", config, "rv64ima_zicsr_zifencei",
                         "i m a zicsr zifencei", "riscv,sv39"});
    }
    {
        Config config;
        config.c = true;
        config.f = true;
        config.d = true;
        config.linux = false;
        cases.push_back({"rv64imafdc", config, "rv64imafdc_zicsr_zifencei",
                         "i m a f d c zicsr zifencei", "riscv,sv39"});
    }
    {
        Config config;
        config.f = true;
        config.d = true;
        config.linux = false;
        cases.push_back({"rv64imafd-no-c", config,
                         "rv64imafd_zicsr_zifencei",
                         "i m a f d zicsr zifencei", "riscv,sv39"});
    }
    {
        Config config;
        config.c = true;
        config.f = true;
        config.linux = false;
        cases.push_back({"rv64imafc-no-d", config,
                         "rv64imafc_zicsr_zifencei",
                         "i m a f c zicsr zifencei", "riscv,sv39"});
    }
    {
        Config config;
        config.c = true;
        config.linux = false;
        cases.push_back({"rv64imac-no-f", config,
                         "rv64imac_zicsr_zifencei",
                         "i m a c zicsr zifencei", "riscv,sv39"});
    }
    {
        Config config;
        config.c = true;
        config.f = true;
        config.d = true;
        config.levels = 4;
        config.linux = false;
        cases.push_back({"rv64-sv48", config, "rv64imafdc_zicsr_zifencei",
                         "i m a f d c zicsr zifencei", "riscv,sv48"});
    }
    for (const Case &test_case : cases) {
        const fs::path config_path =
            work / (test_case.name + ".config");
        const fs::path dtb_dir = work / (test_case.name + "-dtb");
        write_config(config_path, test_case.config);
        run_dtb(config_path, dtb_dir, true, {{"SBI_FDT_LINUX", "n"}});
        const fs::path dtb = dtb_dir / "npc.dtb";
        check_string(require_fdt_string(dtb, "/cpus/cpu@0", "riscv,isa"),
                     test_case.isa, test_case.name + " riscv,isa");
        check_string(require_fdt_string(dtb, "/cpus/cpu@0", "riscv,isa-base"),
                     "rv" + std::to_string(test_case.config.xlen) + "i",
                     test_case.name + " riscv,isa-base");
        check_string(
            require_fdt_string(dtb, "/cpus/cpu@0", "riscv,isa-extensions"),
            test_case.extensions, test_case.name + " riscv,isa-extensions");
        check_string(require_fdt_string(dtb, "/cpus/cpu@0", "mmu-type"),
                     test_case.mmu, test_case.name + " mmu-type");
        check_u64(require_fdt_int(dtb, "/cpus", "timebase-frequency"),
                  450'000'000, test_case.name + " timebase-frequency");
        check_string(require_fdt_string(dtb, "/chosen", "bootargs"),
                     std::string(DEFAULT_BOOTARGS),
                     test_case.name + " bootargs");
    }
}
void test_timebase_follows_synth_freq(const fs::path &work) {
    for (const uint64_t mhz : {100ULL, 450ULL, 800ULL}) {
        const fs::path config_path =
            work / ("tb-" + std::to_string(mhz) + ".config");
        const fs::path dtb_dir =
            work / ("tb-" + std::to_string(mhz) + "-dtb");
        Config config;
        config.linux = false;
        config.synth_freq = mhz;
        write_config(config_path, config);
        run_dtb(config_path, dtb_dir, true, {{"SBI_FDT_LINUX", "n"}});
        check_u64(require_fdt_int(dtb_dir / "npc.dtb", "/cpus",
                                  "timebase-frequency"),
                  mhz * 1'000'000,
                  "timebase for " + std::to_string(mhz) + " MHz");
    }
}
void test_initramfs_properties(const fs::path &work) {
    const fs::path initramfs = make_cpio(work / "initramfs.cpio", 4096);
    const fs::path config_path = work / "initrd.config";
    Config config;
    config.linux = true;
    config.initramfs = true;
    config.initramfs_path = initramfs.string();
    config.initramfs_addr = 0x82100000;
    write_config(config_path, config);
    const fs::path dtb_dir = work / "initrd-dtb";
    run_dtb(config_path, dtb_dir);
    const fs::path dtb = dtb_dir / "npc.dtb";
    check_u64(require_fdt_int(dtb, "/chosen", "linux,initrd-start"),
              0x82100000, "linux,initrd-start");
    check_u64(require_fdt_int(dtb, "/chosen", "linux,initrd-end"),
              0x82101000, "linux,initrd-end");
}
void test_initramfs_disabled_has_no_properties(const fs::path &work) {
    const fs::path config_path = work / "noinitrd.config";
    Config config;
    config.linux = true;
    config.initramfs = false;
    write_config(config_path, config);
    const fs::path dtb_dir = work / "noinitrd-dtb";
    run_dtb(config_path, dtb_dir);
    const fs::path dtb = dtb_dir / "npc.dtb";
    require_fdt_absent(dtb, "/chosen", "linux,initrd-start");
    require_fdt_absent(dtb, "/chosen", "linux,initrd-end");
}
void test_dtb_and_initramfs_addresses_follow_config(const fs::path &work) {
    const fs::path initramfs = make_cpio(work / "addr.cpio", 4096);
    const fs::path config_path = work / "addr.config";
    Config config;
    config.linux = true;
    config.initramfs = true;
    config.initramfs_path = initramfs.string();
    config.initramfs_addr = 0x82300000;
    config.fdt_addr = 0x83E00000;
    write_config(config_path, config);
    const fs::path dtb_dir = work / "addr-dtb";
    run_dtb(config_path, dtb_dir);
    const fs::path dtb = dtb_dir / "npc.dtb";
    check_u64(require_fdt_int(dtb, "/chosen", "linux,initrd-start"),
              0x82300000, "linux,initrd-start");
    const std::vector<uint64_t> cells =
        fdtget_cells(dtb, "/reserved-memory/fdt@83e00000", "reg");
    require(cells.size() >= 4,
            "reserved-memory fdt@83e00000:reg should contain 4 cells\nDTB "
            "info:\n" +
                dtb_info(dtb));
    check_u64(cells[1], 0x83E00000, "reserved-memory fdt address");
    check_u64(cells[3], 0x10000, "reserved-memory fdt size");
    const std::string info = read_text(dtb_dir / "npc.dtb.info");
    require(info.find("fdt_addr=0x83e00000") != std::string::npos,
            "npc.dtb.info does not record fdt_addr=0x83e00000:\n" + info);
}
void test_custom_mmu_linux_rejected(const fs::path &work) {
    const fs::path config_path = work / "custom.config";
    Config config;
    config.linux = true;
    config.geometry = "custom";
    config.page_size = 1024;
    config.table_size = 16384;
    config.levels = 3;
    write_config(config_path, config);
    const CommandResult result =
        run_dtb(config_path, work / "custom-dtb", false,
                {{"SBI_FDT_LINUX", "y"}});
    require_contains(result.err, "不是标准 Sv32/Sv39/Sv48",
                     "CUSTOM MMU + Linux rejection message", result);
}
void test_custom_mmu_non_linux_omits_mmu_type(const fs::path &work) {
    const fs::path config_path = work / "custom-nolinux.config";
    Config config;
    config.linux = false;
    config.geometry = "custom";
    config.page_size = 1024;
    config.table_size = 16384;
    config.levels = 3;
    write_config(config_path, config);
    const fs::path dtb_dir = work / "custom-nolinux-dtb";
    run_dtb(config_path, dtb_dir, true, {{"SBI_FDT_LINUX", "n"}});
    require_fdt_absent(dtb_dir / "npc.dtb", "/cpus/cpu@0", "mmu-type");
}
}
