module npc.linux_boot_test;
import std;
namespace npc::linux_boot_test {
Env common_env(const fs::path &config, const fs::path &dtb_dir) {
    Env env;
    env["NPC_HOME"] = g_ctx.npc_home.string();
    env["WORKBENCH_HOME"] = g_ctx.workbench_home.string();
    env["SBI_CONFIG_JSON"] = config.string() + ".json";
    env["SBI_FDT_OUT_DIR"] = dtb_dir.string();
    env["SBI_FDT_PATH"] = "dts/npc.dts";
    env["SBI_FDT_ENABLE"] = "y";
    env["SBI_FDT_LINUX"] = "y";
    return env;
}
CommandResult run_dtb(const fs::path &config, const fs::path &dtb_dir,
                      bool expect_ok, Env extra) {
    fs::create_directories(dtb_dir);
    Env env = common_env(config, dtb_dir);
    for (const auto &[key, value] : extra) {
        env[key] = value;
    }
    const CommandResult result =
        run_command({g_ctx.build_dtb.string()}, env, g_ctx.npc_home);
    require_exit(result, expect_ok, "build_dtb.sh");
    return result;
}
std::optional<std::string> fdtget(const fs::path &dtb, const std::string &node,
                                  const std::string &property,
                                  const std::string &type) {
    const CommandResult result = run_command(
        {g_ctx.fdtget.string(), "-t", type, dtb.string(), node, property});
    if (result.rc != 0) {
        return std::nullopt;
    }
    return trim(result.out);
}
std::optional<std::string> fdtget_string(const fs::path &dtb,
                                         const std::string &node,
                                         const std::string &property) {
    return fdtget(dtb, node, property, "s");
}
std::optional<uint64_t> parse_hex_value(const std::string &text) {
    const char *first = text.data();
    const char *last = first + text.size();
    if (first != last && first[0] == '0' && first + 1 != last &&
        (first[1] == 'x' || first[1] == 'X')) {
        first += 2;
    }
    if (first == last) {
        return std::nullopt;
    }
    uint64_t value = 0;
    const auto result = std::from_chars(first, last, value, 16);
    if (result.ec != std::errc{} || result.ptr != last) {
        return std::nullopt;
    }
    return value;
}
std::optional<uint64_t> fdtget_int(const fs::path &dtb,
                                   const std::string &node,
                                   const std::string &property) {
    const auto value = fdtget(dtb, node, property, "x");
    if (!value.has_value()) {
        return std::nullopt;
    }
    std::istringstream input(*value);
    std::string token;
    std::string last;
    while (input >> token) {
        last = token;
    }
    return parse_hex_value(last);
}
std::vector<uint64_t> fdtget_cells(const fs::path &dtb, const std::string &node,
                                   const std::string &property) {
    const auto value = fdtget(dtb, node, property, "x");
    std::vector<uint64_t> cells;
    if (!value.has_value()) {
        return cells;
    }
    std::istringstream input(*value);
    std::string token;
    while (input >> token) {
        const auto cell = parse_hex_value(token);
        if (!cell.has_value()) {
            fail("fdtget returned a non-hex cell '" + token + "' for " + node +
                 ":" + property);
        }
        cells.push_back(*cell);
    }
    return cells;
}
std::string dtb_info(const fs::path &dtb) {
    const fs::path info = dtb.parent_path() / "npc.dtb.info";
    std::error_code error;
    if (fs::is_regular_file(info, error)) {
        return read_text(info);
    }
    return "<missing " + info.string() + ">";
}
std::string require_fdt_string(const fs::path &dtb, const std::string &node,
                               const std::string &property) {
    const auto value = fdtget_string(dtb, node, property);
    if (!value.has_value()) {
        fail("property " + node + ":" + property + " is missing from " +
             dtb.string() + "\nDTB info:\n" + dtb_info(dtb));
    }
    return *value;
}
uint64_t require_fdt_int(const fs::path &dtb, const std::string &node,
                         const std::string &property) {
    const auto value = fdtget_int(dtb, node, property);
    if (!value.has_value()) {
        fail("property " + node + ":" + property + " is missing from " +
             dtb.string() + "\nDTB info:\n" + dtb_info(dtb));
    }
    return *value;
}
void require_fdt_absent(const fs::path &dtb, const std::string &node,
                        const std::string &property) {
    const auto value = fdtget_string(dtb, node, property);
    if (value.has_value()) {
        fail("property " + node + ":" + property +
             " unexpectedly exists (value=\"" + *value + "\") in " +
             dtb.string() + "\nDTB info:\n" + dtb_info(dtb));
    }
}
void check_string(const std::string &actual, const std::string &expected,
                  const std::string &what) {
    if (actual != expected) {
        fail(what + ": expected \"" + expected + "\", got \"" + actual + "\"");
    }
}
void check_u64(uint64_t actual, uint64_t expected, const std::string &what) {
    if (actual != expected) {
        fail(what + ": expected " + hex_u64(expected) + " (" +
             decimal_u64(expected) + "), got " + hex_u64(actual) + " (" +
             decimal_u64(actual) + ")");
    }
}
fs::path write_fake_fw(const fs::path &out_dir, const fs::path &dtb,
                       uint64_t fdt_addr,
                       uint64_t timebase) {
    fs::create_directories(out_dir);
    const fs::path fw = out_dir / "fw.bin";
    const fs::path info = out_dir / "fw.info";
    const std::vector<uint8_t> fw_data(4096, 0x13);
    write_binary(fw, fw_data);
    std::error_code error;
    const fs::path absolute_dtb = fs::canonical(dtb, error);
    const fs::path dtb_path = error ? dtb : absolute_dtb;
    std::ostringstream text;
    text << "impl=opensbi\n";
    text << "fw_base=" << hex_u64(FW_BASE) << '\n';
    text << "fw_size=" << hex_u64(FW_SIZE) << '\n';
    text << "fdt_enable=y\n";
    text << "fdt_path=" << dtb_path.string() << '\n';
    text << "fdt_addr=" << hex_u64(fdt_addr) << '\n';
    text << "fdt_bytes=" << get_file_size(dtb_path) << '\n';
    text << "fdt_sha256=" << sha256_file(dtb_path) << '\n';
    text << "timebase_freq=" << timebase << '\n';
    text << "fw_bytes=" << fw_data.size() << '\n';
    text << "fw_sha256=" << sha256_file(fw) << '\n';
    write_text(info, text.str());
    return fw;
}
Env pack_env(const fs::path &config, const fs::path &fw_dir,
             const fs::path &dtb_dir, const fs::path &out_dir,
             const fs::path &image, bool with_initramfs,
             const fs::path &initramfs, uint64_t initramfs_addr,
             uint64_t fdt_addr, uint64_t next_addr) {
    Env env = common_env(config, dtb_dir);
    env["SBI_ENABLE"] = "y";
    env["SBI_IMPL"] = "opensbi";
    env["SBI_OUT_DIR"] = fw_dir.string();
    env["SBI_FDT_ADDR"] = hex_u64(fdt_addr);
    env["SBI_NEXT_ADDR"] = hex_u64(next_addr);
    env["SBI_TIMEBASE_FREQ"] = "450000000";
    env["LINUX_IMAGE_PATH"] = image.string();
    env["LINUX_INITRAMFS_ENABLE"] = with_initramfs ? "y" : "n";
    env["LINUX_INITRAMFS_PATH"] =
        with_initramfs ? initramfs.string() : std::string();
    env["LINUX_INITRAMFS_ADDR"] = hex_u64(initramfs_addr);
    env["LINUX_BOOTARGS"] = std::string(DEFAULT_BOOTARGS);
    env["LINUX_OUT_DIR"] = out_dir.string();
    return env;
}
PackSetup standard_pack_setup(const fs::path &work, const ImageSpec &image_spec,
                              bool with_initramfs,
                              uint64_t initramfs_size,
                              uint64_t initramfs_addr) {
    PackSetup setup;
    setup.config = work / "pack.config";
    setup.image = make_image(work / "Image", image_spec);
    Config config;
    config.linux = true;
    config.initramfs = with_initramfs;
    config.initramfs_addr = initramfs_addr;
    if (with_initramfs) {
        setup.initramfs = make_cpio(work / "initramfs.cpio", initramfs_size);
        config.initramfs_path = setup.initramfs.string();
    }
    write_config(setup.config, config);
    setup.dtb_dir = work / "pack-dtb";
    run_dtb(setup.config, setup.dtb_dir);
    setup.dtb = setup.dtb_dir / "npc.dtb";
    setup.fw_dir = work / "pack-fw";
    write_fake_fw(setup.fw_dir, setup.dtb, FDT_ADDR);
    setup.out_dir = work / "pack-out";
    setup.env = pack_env(setup.config, setup.fw_dir, setup.dtb_dir,
                         setup.out_dir, setup.image, with_initramfs,
                         setup.initramfs, initramfs_addr, FDT_ADDR, IMAGE_ADDR);
    return setup;
}
CommandResult run_pack(const Env &env, bool expect_ok) {
    const CommandResult result =
        run_command({g_ctx.pack_linux_image.string()}, env, g_ctx.npc_home);
    require_exit(result, expect_ok, "pack_linux_image.sh");
    return result;
}
void require_span_equal(const std::vector<uint8_t> &binary, uint64_t offset,
                        const std::vector<uint8_t> &expected,
                        const std::string &what) {
    require(offset + expected.size() <= binary.size(),
            what + ": binary is too short for offset " + hex_u64(offset) +
                " + " + std::to_string(expected.size()) + " bytes");
    const bool equal =
        std::equal(expected.begin(), expected.end(), binary.begin() + offset);
    if (!equal) {
        fail(what + ": packed bytes at offset " + hex_u64(offset) +
             " do not match the source file");
    }
}
}
