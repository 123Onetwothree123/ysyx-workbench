module npc.linux_boot_test;
import std;
namespace npc::linux_boot_test {
void test_pack_positive_and_sha256(const fs::path &work) {
    PackSetup setup =
        standard_pack_setup(work, ImageSpec{}, true, 4096, INITRAMFS_ADDR);
    run_pack(setup.env);
    const fs::path binary = setup.out_dir / "linux-image.bin";
    require(fs::is_regular_file(binary),
            "packed binary was not created: " + binary.string());
    const std::vector<uint8_t> packed = read_binary(binary);
    const uint64_t dtb_size = get_file_size(setup.dtb);
    check_u64(packed.size(), FDT_ADDR - RAM_BASE + dtb_size,
              "packed binary size");
    require_span_equal(packed, IMAGE_ADDR - RAM_BASE,
                       read_binary(setup.image), "Linux Image span");
    require_span_equal(packed, INITRAMFS_ADDR - RAM_BASE,
                       read_binary(setup.initramfs), "initramfs span");
    require_span_equal(packed, FDT_ADDR - RAM_BASE, read_binary(setup.dtb),
                       "DTB span");
    const std::string info = read_text(setup.out_dir / "linux-image.bin.info");
    for (const std::string needle :
         {"image_addr=0x80200000", "initramfs_addr=0x82000000",
          "dtb_addr=0x83f00000", "binary_sha256=", "timebase_freq=450000000"}) {
        require(info.find(needle) != std::string::npos,
                "linux-image.bin.info does not contain \"" + needle +
                    "\":\n" + info);
    }
    const std::string binary_sha = sha256_file(binary);
    require(info.find("binary_sha256=" + binary_sha) != std::string::npos,
            "linux-image.bin.info does not record the binary sha256\n" +
                info);
    const std::string sha_file =
        read_text(setup.out_dir / "linux-image.bin.sha256");
    require(sha_file.find(binary_sha) != std::string::npos,
            "linux-image.bin.sha256 does not contain the binary sha256:\n" +
                sha_file);
    const CommandResult check = run_command(
        {g_ctx.sha256sum.string(), "-c", "linux-image.bin.sha256"}, {},
        setup.out_dir);
    require_exit(check, true, "sha256sum -c linux-image.bin.sha256");
    require_contains(check.out, "OK", "sha256sum -c output", check);
}
void test_pack_without_initramfs(const fs::path &work) {
    PackSetup setup =
        standard_pack_setup(work, ImageSpec{}, false, 4096, INITRAMFS_ADDR);
    run_pack(setup.env);
    const fs::path binary = setup.out_dir / "linux-image.bin";
    require(fs::is_regular_file(binary),
            "packed binary was not created: " + binary.string());
    require_fdt_absent(setup.dtb, "/chosen", "linux,initrd-start");
    const std::string info = read_text(setup.out_dir / "linux-image.bin.info");
    require(info.find("initramfs_enable=n") != std::string::npos,
            "linux-image.bin.info should record initramfs_enable=n:\n" + info);
}
void test_pack_image_bad_magic(const fs::path &work) {
    ImageSpec spec;
    spec.magic = magic_from_text("NOTRISCV");
    PackSetup setup = standard_pack_setup(work, spec);
    const CommandResult result = run_pack(setup.env, false);
    require_contains(result.err, "magic", "bad Image magic rejection", result);
}
void test_pack_image_text_offset_mismatch(const fs::path &work) {
    ImageSpec spec;
    spec.text_offset = 0x400000;
    PackSetup setup = standard_pack_setup(work, spec);
    const CommandResult result = run_pack(setup.env, false);
    require_contains(result.err, "text_offset",
                     "Image text_offset mismatch rejection", result);
}
void test_pack_image_overlaps_initramfs(const fs::path &work) {
    ImageSpec spec;
    spec.header_image_size = (INITRAMFS_ADDR - IMAGE_ADDR) + 1;
    PackSetup setup = standard_pack_setup(work, spec);
    const CommandResult result = run_pack(setup.env, false);
    require_contains(result.err, "重叠", "Image/initramfs overlap rejection",
                     result);
}
void test_pack_initramfs_overlaps_dtb(const fs::path &work) {
    const fs::path initramfs =
        make_cpio(work / "overlap.cpio",
                  (FDT_ADDR - INITRAMFS_ADDR) + 0x1000);
    const fs::path config_path = work / "overlap.config";
    Config config;
    config.linux = true;
    config.initramfs = true;
    config.initramfs_path = initramfs.string();
    write_config(config_path, config);
    const CommandResult result =
        run_dtb(config_path, work / "overlap-dtb", false);
    require_contains(result.err, "重叠", "initramfs/DTB overlap rejection",
                     result);
}
void test_pack_initramfs_out_of_ram(const fs::path &work) {
    const fs::path initramfs =
        make_cpio(work / "oor.cpio",
                  (RAM_BASE + RAM_SIZE - 0x83E00000) + 0x1000);
    const fs::path config_path = work / "oor.config";
    Config config;
    config.linux = true;
    config.initramfs = true;
    config.initramfs_path = initramfs.string();
    config.initramfs_addr = 0x83E00000;
    write_config(config_path, config);
    const CommandResult result =
        run_dtb(config_path, work / "oor-dtb", false);
    require_contains(result.err, "超出 NPC RAM",
                     "initramfs out-of-RAM rejection", result);
}
void test_pack_initramfs_unaligned(const fs::path &work) {
    const fs::path initramfs = make_cpio(work / "unaligned.cpio", 4096);
    const fs::path config_path = work / "unaligned.config";
    Config config;
    config.linux = true;
    config.initramfs = true;
    config.initramfs_path = initramfs.string();
    config.initramfs_addr = 0x82000004;
    write_config(config_path, config);
    const CommandResult result =
        run_dtb(config_path, work / "unaligned-dtb", false);
    require_contains(result.err, "4 KiB 对齐",
                     "initramfs alignment rejection", result);
}
void test_pack_missing_image(const fs::path &work) {
    PackSetup setup = standard_pack_setup(work, ImageSpec{});
    fs::remove(setup.image);
    const CommandResult result = run_pack(setup.env, false);
    require_contains(result.err, "Linux Image 不存在",
                     "missing Linux Image rejection", result);
}
void test_pack_fdt_address_out_of_ram(const fs::path &work) {
    const fs::path config_path = work / "bad-fdt.config";
    Config config;
    config.linux = true;
    config.fdt_addr = 0x83FF8000;
    write_config(config_path, config);
    const CommandResult result =
        run_dtb(config_path, work / "bad-fdt-dtb", false,
                {{"SBI_FDT_ADDR", "0x83ff8000"}});
    require_contains(result.err, "超出 NPC RAM",
                     "DTB out-of-RAM rejection", result);
}
}
