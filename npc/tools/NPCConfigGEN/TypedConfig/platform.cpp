module npc.configgen;
import std;
namespace npc::configgen {
void addPlatform(TypedFields &root, const Config &values,
                 long long addressWidth, long long socXlen)
{
    TypedFields platform;
    addField(platform, "ram_size",
             TypedJson::integer(
                 flatInt(values, "NPC_RAM_SIZE", 0x04000000LL)));
    addField(platform, "ram_base",
             TypedJson::integer(
                 flatInt(values, "NPC_RAM_BASE", 0x80000000LL)));
    addField(platform, "cache_padding",
             TypedJson::integer(flatInt(values, "CACHE_PADDING", 0)));
    addField(platform, "chip_link",
             TypedJson::boolean(flatBool(values, "CHIPLINK")));
    addField(platform, "address_width", TypedJson::integer(addressWidth));
    addField(platform, "soc_xlen", TypedJson::integer(socXlen));
    addField(root, "platform", TypedJson::object(std::move(platform)));
}
void addPma(TypedFields &root, const Config &values)
{
    TypedFields pma;
    const auto add = [&](const char *name, const char *key, long long fallback) {
        addField(pma, name,
                 TypedJson::integer(flatInt(values, key, fallback)));
    };
    add("soc_reset_addr", "PMA_SOC_RESET_ADDR", 0x30000000LL);
    add("clint_base", "PMA_CLINT_BASE", 0x02000000LL);
    add("clint_size", "PMA_CLINT_SIZE", 0x00010000LL);
    add("sram_base", "PMA_SRAM_BASE", 0x0f000000LL);
    add("sram_size", "PMA_SRAM_SIZE", 0x00008000LL);
    add("uart_base", "PMA_UART_BASE", 0x10000000LL);
    add("uart_size", "PMA_UART_SIZE", 0x00001000LL);
    add("spi_base", "PMA_SPI_BASE", 0x10001000LL);
    add("spi_size", "PMA_SPI_SIZE", 0x00001000LL);
    add("gpio_base", "PMA_GPIO_BASE", 0x10002000LL);
    add("gpio_size", "PMA_GPIO_SIZE", 0x00000010LL);
    add("keyboard_base", "PMA_KEYBOARD_BASE", 0x10011000LL);
    add("keyboard_size", "PMA_KEYBOARD_SIZE", 0x00000008LL);
    add("vga_base", "PMA_VGA_BASE", 0x21000000LL);
    add("vga_size", "PMA_VGA_SIZE", 0x00200000LL);
    add("mrom_base", "PMA_MROM_BASE", 0x30000000LL);
    add("mrom_size", "PMA_MROM_SIZE", 0x10000000LL);
    add("psram_base", "PMA_PSRAM_BASE", 0x80000000LL);
    add("psram_size", "PMA_PSRAM_SIZE", 0x00400000LL);
    add("sdram_base", "PMA_SDRAM_BASE", 0xa0000000LL);
    add("sdram_size", "PMA_SDRAM_SIZE", 0x02000000LL);
    add("chiplink_mmio_base", "PMA_CHIPLINK_MMIO_BASE", 0x40000000LL);
    add("chiplink_mmio_size", "PMA_CHIPLINK_MMIO_SIZE", 0x40000000LL);
    add("chiplink_mem_base", "PMA_CHIPLINK_MEM_BASE", 0xc0000000LL);
    add("chiplink_mem_size", "PMA_CHIPLINK_MEM_SIZE", 0x40000000LL);
    addField(root, "pma", TypedJson::object(std::move(pma)));
}
void addSoc(TypedFields &root, const Config &values, long long xlen,
            long long socXlen)
{
    TypedFields soc;
    addField(soc, "cpu_xlen", TypedJson::integer(xlen));
    addField(soc, "soc_xlen", TypedJson::integer(socXlen));
    addField(soc, "soc_xlen_follow_cpu",
             TypedJson::boolean(flatBool(values, "SOC_XLEN_FOLLOW_CPU")));
    addField(soc, "chiplink",
             TypedJson::boolean(flatBool(values, "CHIPLINK")));
    addField(soc, "sdram_use_axi",
             TypedJson::boolean(flatBool(values, "SDRAM_USE_AXI", true)));
    const bool internalFollow =
        flatBool(values, "INTERNAL_BUS_WIDTH_FOLLOW_CPU", true);
    const long long internalWidth =
        internalFollow ? xlen
                       : flatInt(values, "INTERNAL_BUS_WIDTH",
                                 xlen == 64 ? 64 : 32);
    addField(soc, "internal_bus_width_follow_cpu",
             TypedJson::boolean(internalFollow));
    addField(soc, "internal_xlen", TypedJson::integer(internalWidth));
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
}
void addBoot(TypedFields &root, const Config &values)
{
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
}
void addBuild(TypedFields &root, const Config &values,
              const std::string &pdkOverride)
{
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
}
void addFlat(TypedFields &root, const Config &values)
{
    TypedFields flat;
    for (const auto &[key, value] : values)
        addField(flat, key, TypedJson::string(value));
    addField(root, "flat", TypedJson::object(std::move(flat)));
}
}
