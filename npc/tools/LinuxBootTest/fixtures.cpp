module npc.linux_boot_test;
import std;
namespace npc::linux_boot_test {
Magic default_magic() { return {'R', 'I', 'S', 'C', 'V', 0, 0, 0}; }
Magic magic_from_text(std::string_view text) {
    require(text.size() == 8, "test magic must contain exactly 8 bytes");
    Magic magic{};
    for (std::size_t i = 0; i < magic.size(); ++i) {
        magic[i] = static_cast<uint8_t>(text[i]);
    }
    return magic;
}
void emit_config_bool(std::ostringstream &out, const std::string &key,
                      bool value) {
    if (value) {
        out << "CONFIG_" << key << "=y\n";
    } else {
        out << "# CONFIG_" << key << " is not set\n";
    }
}
void emit_config_int(std::ostringstream &out, const std::string &key,
                     uint64_t value) {
    out << "CONFIG_" << key << '=';
    if (value >= 0x100) {
        out << "0x" << std::hex << value << std::dec;
    } else {
        out << value;
    }
    out << '\n';
}
void emit_config_string(std::ostringstream &out, const std::string &key,
                        const std::string &value) {
    out << "CONFIG_" << key << "=\"" << value << "\"\n";
}
std::string json_escape(const std::string &value) {
    std::string result;
    for (const char character : value) {
        switch (character) {
        case '"': result += "\\\""; break;
        case '\\': result += "\\\\"; break;
        case '\n': result += "\\n"; break;
        case '\r': result += "\\r"; break;
        case '\t': result += "\\t"; break;
        default: result.push_back(character); break;
        }
    }
    return result;
}
void emit_json_string(std::ostringstream &out, bool &first,
                      const std::string &key, const std::string &value) {
    if (!first)
        out << ",\n";
    first = false;
    out << "  \"" << key << "\": \"" << json_escape(value) << "\"";
}
fs::path write_config(const fs::path &path, const Config &config) {
    std::ostringstream out;
    emit_config_int(out, "XLEN", config.xlen);
    emit_config_bool(out, "RISCV_M", config.m);
    emit_config_bool(out, "RISCV_A", config.a);
    emit_config_bool(out, "RISCV_C", config.c);
    emit_config_bool(out, "RISCV_F", config.f);
    emit_config_bool(out, "RISCV_D", config.d);
    emit_config_bool(out, "HAS_S", config.has_s);
    emit_config_bool(out, "HAS_U", config.has_u);
    emit_config_bool(out, "HAS_MMU", config.has_mmu);
    emit_config_int(out, "MMU_LEVELS", config.levels);
    emit_config_string(out, "MMU_GEOMETRY_MODE", config.geometry);
    emit_config_int(out, "MMU_PAGE_SIZE_BYTES", config.page_size);
    emit_config_int(out, "MMU_PTE_SIZE_BYTES", config.pte_size);
    emit_config_int(out, "MMU_PAGE_TABLE_SIZE_BYTES", config.table_size);
    emit_config_string(out, "MMU_PTE_FORMAT", config.pte_format);
    emit_config_int(out, "SYNTH_FREQ", config.synth_freq);
    emit_config_int(out, "SBI_FW_BASE", config.fw_base);
    emit_config_int(out, "SBI_FW_SIZE", config.fw_size);
    emit_config_int(out, "SBI_NEXT_ADDR", config.next_addr);
    emit_config_int(out, "SBI_FDT_ADDR", config.fdt_addr);
    emit_config_bool(out, "SBI_FDT_ENABLE", config.fdt_enable);
    emit_config_string(out, "SBI_FDT_PATH", config.fdt_path);
    emit_config_int(out, "SBI_FDT_RESERVE_SIZE", config.fdt_reserve_size);
    emit_config_int(out, "NPC_RAM_BASE", config.ram_base);
    emit_config_int(out, "NPC_RAM_SIZE", config.ram_size);
    emit_config_bool(out, "LINUX_BOOT_ENABLE", config.linux);
    emit_config_string(out, "LINUX_IMAGE_PATH", config.image_path);
    emit_config_string(out, "LINUX_BOOTARGS", config.bootargs);
    emit_config_bool(out, "LINUX_INITRAMFS_ENABLE", config.initramfs);
    emit_config_string(out, "LINUX_INITRAMFS_PATH", config.initramfs_path);
    emit_config_int(out, "LINUX_INITRAMFS_ADDR", config.initramfs_addr);
    if (config.f) {
        emit_config_bool(out, "FPU_IMPL_HARDFLOAT", true);
        emit_config_int(out, "FPU_FLEN", 64);
    }
    write_text(path, out.str());
    std::ostringstream json;
    json << "{\n";
    bool first = true;
    emit_json_string(json, first, "XLEN", std::to_string(config.xlen));
    emit_json_string(json, first, "RISCV_M", config.m ? "y" : "n");
    emit_json_string(json, first, "RISCV_A", config.a ? "y" : "n");
    emit_json_string(json, first, "RISCV_C", config.c ? "y" : "n");
    emit_json_string(json, first, "RISCV_F", config.f ? "y" : "n");
    emit_json_string(json, first, "RISCV_D", config.d ? "y" : "n");
    emit_json_string(json, first, "HAS_S", config.has_s ? "y" : "n");
    emit_json_string(json, first, "HAS_U", config.has_u ? "y" : "n");
    emit_json_string(json, first, "HAS_MMU", config.has_mmu ? "y" : "n");
    emit_json_string(json, first, "MMU_LEVELS", std::to_string(config.levels));
    emit_json_string(json, first, "MMU_GEOMETRY_MODE", config.geometry);
    emit_json_string(json, first, "MMU_PAGE_SIZE_BYTES", std::to_string(config.page_size));
    emit_json_string(json, first, "MMU_PTE_SIZE_BYTES", std::to_string(config.pte_size));
    emit_json_string(json, first, "MMU_PAGE_TABLE_SIZE_BYTES", std::to_string(config.table_size));
    emit_json_string(json, first, "MMU_PTE_FORMAT", config.pte_format);
    emit_json_string(json, first, "SYNTH_FREQ", std::to_string(config.synth_freq));
    emit_json_string(json, first, "SBI_FW_BASE", std::to_string(config.fw_base));
    emit_json_string(json, first, "SBI_FW_SIZE", std::to_string(config.fw_size));
    emit_json_string(json, first, "SBI_NEXT_ADDR", std::to_string(config.next_addr));
    emit_json_string(json, first, "SBI_FDT_ADDR", std::to_string(config.fdt_addr));
    emit_json_string(json, first, "SBI_FDT_ENABLE", config.fdt_enable ? "y" : "n");
    emit_json_string(json, first, "SBI_FDT_PATH", config.fdt_path);
    emit_json_string(json, first, "SBI_FDT_RESERVE_SIZE", std::to_string(config.fdt_reserve_size));
    emit_json_string(json, first, "NPC_RAM_BASE", std::to_string(config.ram_base));
    emit_json_string(json, first, "NPC_RAM_SIZE", std::to_string(config.ram_size));
    emit_json_string(json, first, "LINUX_BOOT_ENABLE", config.linux ? "y" : "n");
    emit_json_string(json, first, "LINUX_IMAGE_PATH", config.image_path);
    emit_json_string(json, first, "LINUX_BOOTARGS", config.bootargs);
    emit_json_string(json, first, "LINUX_INITRAMFS_ENABLE", config.initramfs ? "y" : "n");
    emit_json_string(json, first, "LINUX_INITRAMFS_PATH", config.initramfs_path);
    emit_json_string(json, first, "LINUX_INITRAMFS_ADDR", std::to_string(config.initramfs_addr));
    if (config.f) {
        emit_json_string(json, first, "FPU_IMPL", "hardfloat");
        emit_json_string(json, first, "FPU_FLEN", "64");
    }
    json << "\n}\n";
    write_text(path.string() + ".json", json.str());
    return path;
}
void store_u32(std::vector<uint8_t> &data, std::size_t offset, uint32_t value) {
    for (std::size_t i = 0; i < 4; ++i) {
        data[offset + i] = static_cast<uint8_t>(value >> (8 * i));
    }
}
void store_u64(std::vector<uint8_t> &data, std::size_t offset, uint64_t value) {
    for (std::size_t i = 0; i < 8; ++i) {
        data[offset + i] = static_cast<uint8_t>(value >> (8 * i));
    }
}
fs::path make_image(const fs::path &path, const ImageSpec &spec) {
    require(spec.file_size >= 68,
            "dummy Image must be at least 68 bytes to hold the header");
    std::vector<uint8_t> data(spec.file_size, 0);
    const uint64_t image_size =
        spec.header_image_size.value_or(spec.file_size);
    store_u32(data, 0, 0x00000013);
    store_u32(data, 4, 0);
    store_u64(data, 8, spec.text_offset);
    store_u64(data, 16, image_size);
    store_u64(data, 24, 0);
    store_u32(data, 32, 2);
    store_u32(data, 36, 0);
    store_u64(data, 40, 0);
    std::memcpy(data.data() + 0x38, spec.magic.data(), spec.magic.size());
    const std::array<uint8_t, 4> magic2 = {'R', 'S', 'C', 0x05};
    std::memcpy(data.data() + 0x40, magic2.data(), magic2.size());
    write_binary(path, data);
    return path;
}
void append_cpio_field(std::string &out, uint32_t value) {
    char buffer[9] = {};
    std::snprintf(buffer, sizeof(buffer), "%08x", value);
    out += buffer;
}
void append_cpio_entry(std::string &out, uint32_t ino, uint32_t mode,
                       uint32_t nlink, const std::string &name,
                       const std::string &data) {
    std::string name_field = name;
    name_field.push_back('\0');
    out += "070701";
    append_cpio_field(out, ino);
    append_cpio_field(out, mode);
    append_cpio_field(out, 0);
    append_cpio_field(out, 0);
    append_cpio_field(out, nlink);
    append_cpio_field(out, 0);
    append_cpio_field(out, static_cast<uint32_t>(data.size()));
    append_cpio_field(out, 0);
    append_cpio_field(out, 0);
    append_cpio_field(out, 0);
    append_cpio_field(out, 0);
    append_cpio_field(out, static_cast<uint32_t>(name_field.size()));
    append_cpio_field(out, 0);
    out += name_field;
    while (out.size() % 4 != 0) {
        out.push_back('\0');
    }
    out += data;
    while (out.size() % 4 != 0) {
        out.push_back('\0');
    }
}
std::string build_cpio_archive() {
    std::string out;
    append_cpio_entry(out, 1, 0100755, 1, "init", "#!/bin/sh\n");
    append_cpio_entry(out, 0, 0, 1, "TRAILER!!!", "");
    return out;
}
fs::path make_cpio(const fs::path &path, uint64_t size) {
    const std::string archive = build_cpio_archive();
    require(size >= archive.size(),
            "requested cpio size " + std::to_string(size) +
                " is smaller than the minimum archive " +
                std::to_string(archive.size()));
    std::vector<uint8_t> data(static_cast<std::size_t>(size), 0);
    std::memcpy(data.data(), archive.data(), archive.size());
    write_binary(path, data);
    return path;
}
}
