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
    addField(platform, "cache_padding",
             TypedJson::integer(flatInt(values, "CACHE_PADDING", 0)));
    addField(platform, "chip_link",
             TypedJson::boolean(flatBool(values, "CHIPLINK")));
    addField(platform, "address_width", TypedJson::integer(addressWidth));
    addField(platform, "soc_xlen", TypedJson::integer(socXlen));
    addField(root, "platform", TypedJson::object(std::move(platform)));
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
    addField(soc, "internal_xlen", TypedJson::integer(32));
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
