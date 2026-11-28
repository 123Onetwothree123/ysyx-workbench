module;
#include "npc_config_defaults.h"
module npc.configgen;
import std;
namespace npc::configgen {
namespace defaults = config_defaults;
namespace {
std::string hexString(long long value)
{
    std::ostringstream out;
    out << "0x" << std::hex << value;
    return out.str();
}
}
void addPlatform(TypedFields &root, const Config &values,
                 long long addressWidth, long long SOCXlen)
{
    TypedFields platform;
    addField(platform, "ram_size",
             TypedJson::integer(
                 flatInt(values, "NPC_RAM_SIZE", defaults::NpcRamSize())));
    addField(platform, "ram_base",
             TypedJson::integer(
                 flatInt(values, "NPC_RAM_BASE", defaults::NpcRamBase())));
    addField(platform, "icache_drain_timeout",
             TypedJson::integer(
                 flatInt(values, "ICACHE_DRAIN_TIMEOUT", 1023)));
    addField(platform, "dcache_drain_timeout",
             TypedJson::integer(
                 flatInt(values, "DCACHE_DRAIN_TIMEOUT", 255)));
    addField(platform, "lsu_drain_timeout",
             TypedJson::integer(
                 flatInt(values, "LSU_DRAIN_TIMEOUT", 255)));
    addField(platform, "arbiter_grant_timeout",
             TypedJson::integer(
                 flatInt(values, "ARBITER_GRANT_TIMEOUT", 1023)));
    addField(platform, "axi_id_width", TypedJson::integer(4));
    addField(platform, "axi_arb_cpu_resp_queue_depth",
             TypedJson::integer(flatInt(
                 values, "AXI_ARB_CPU_RESP_QUEUE_DEPTH", 16)));
    addField(platform, "axi_arb_dma_resp_queue_depth",
             TypedJson::integer(flatInt(
                 values, "AXI_ARB_DMA_RESP_QUEUE_DEPTH", 256)));
    addField(platform, "axi_xbar_resp_queue_depth",
             TypedJson::integer(flatInt(
                 values, "AXI_XBAR_RESP_QUEUE_DEPTH", 4)));
    // PTW请求队列深度跟随MMU_PTW_BUS_OUTSTANDING，0表示跟随walk数
    const long long ptwOutstanding =
        flatInt(values, "MMU_PTW_OUTSTANDING", 2);
    const long long ptwBusOutstanding =
        flatInt(values, "MMU_PTW_BUS_OUTSTANDING", 1);
    const long long ptwBusDepth =
        ptwBusOutstanding > 0 ? ptwBusOutstanding : ptwOutstanding;
    addField(platform, "ptw_bus_depth", TypedJson::integer(ptwBusDepth));
    addField(platform, "uart_fifo_depth",
             TypedJson::integer(flatInt(values, "UART_FIFO_DEPTH", 64)));
    addField(platform, "uart_addr_width",
             TypedJson::integer(flatInt(values, "UART_ADDR_WIDTH", 5)));
    addField(platform, "uart_tx_hold_cycles",
             TypedJson::integer(
                 flatInt(values, "UART_TX_HOLD_CYCLES", 32)));
    addField(platform, "uart_tx_shift_cycles",
             TypedJson::integer(
                 flatInt(values, "UART_TX_SHIFT_CYCLES", 64)));
    addField(platform, "cache_padding",
             TypedJson::integer(flatInt(values, "CACHE_PADDING", 0)));
    addField(platform, "chip_link",
             TypedJson::boolean(flatBool(values, "CHIPLINK")));
    addField(platform, "address_width", TypedJson::integer(addressWidth));
    addField(platform, "soc_xlen", TypedJson::integer(SOCXlen));
    addField(root, "platform", TypedJson::object(std::move(platform)));
}
void addPma(TypedFields &root, const Config &values)
{
    TypedFields PMA;
    const bool linuxMap = flatBool(values, "SOC_MEMORY_MAP_LINUX");
    const bool fpgaOnly = flatBool(values, "NPC_MODE_FPGA_ONLY");
    const auto add = [&](const char *name, const char *key, long long fallback) {
        addField(PMA, name,
                 TypedJson::integer(flatInt(values, key, fallback)));
    };
    add("soc_reset_addr", "PMA_SOC_RESET_ADDR",
        defaults::PmaSocResetAddr(
            flatInt(values, "PMA_MROM_BASE", defaults::PmaMromBase())));
    add("clint_base", "PMA_CLINT_BASE", defaults::PmaClintBase());
    add("clint_size", "PMA_CLINT_SIZE", defaults::PmaClintSize());
    add("sram_base", "PMA_SRAM_BASE", defaults::PmaSramBase());
    add("sram_size", "PMA_SRAM_SIZE", defaults::PmaSramSize());
    add("uart_base", "PMA_UART_BASE", defaults::PmaUartBase());
    add("uart_size", "PMA_UART_SIZE", defaults::PmaUartSize());
    add("spi_base", "PMA_SPI_BASE", defaults::PmaSpiBase());
    add("spi_size", "PMA_SPI_SIZE", defaults::PmaSpiSize());
    add("gpio_base", "PMA_GPIO_BASE", defaults::PmaGpioBase());
    add("gpio_size", "PMA_GPIO_SIZE", defaults::PmaGpioSize());
    add("keyboard_base", "PMA_KEYBOARD_BASE", defaults::PmaKeyboardBase());
    add("keyboard_size", "PMA_KEYBOARD_SIZE", defaults::PmaKeyboardSize());
    add("vga_base", "PMA_VGA_BASE", defaults::PmaVgaBase());
    add("vga_size", "PMA_VGA_SIZE", defaults::PmaVgaSize());
    add("mrom_base", "PMA_MROM_BASE", defaults::PmaMromBase());
    add("mrom_size", "PMA_MROM_SIZE", defaults::PmaMromSize());
    add("flash_base", "PMA_FLASH_BASE", defaults::PmaFlashBase());
    add("flash_size", "PMA_FLASH_SIZE", defaults::PmaFlashSize());
    add("psram_base", "PMA_PSRAM_BASE",
        defaults::PmaPsramBase(linuxMap, fpgaOnly));
    add("psram_size", "PMA_PSRAM_SIZE", defaults::PmaPsramSize());
    add("sdram_base", "PMA_SDRAM_BASE",
        defaults::PmaSdramBase(linuxMap, fpgaOnly));
    add("sdram_size", "PMA_SDRAM_SIZE",
        defaults::PmaSdramSize(linuxMap, fpgaOnly));
    add("chiplink_mmio_base", "PMA_CHIPLINK_MMIO_BASE",
        defaults::PmaChiplinkMmioBase());
    add("chiplink_mmio_size", "PMA_CHIPLINK_MMIO_SIZE",
        defaults::PmaChiplinkMmioSize());
    add("chiplink_mem_base", "PMA_CHIPLINK_MEM_BASE",
        defaults::PmaChiplinkMemBase());
    add("chiplink_mem_size", "PMA_CHIPLINK_MEM_SIZE",
        defaults::PmaChiplinkMemSize());
    addField(root, "PMA", TypedJson::object(std::move(PMA)));
}
void addSoc(TypedFields &root, const Config &values, long long xlen,
            long long SOCXlen)
{
    TypedFields SOC;
    addField(SOC, "cpu_xlen", TypedJson::integer(xlen));
    addField(SOC, "soc_xlen", TypedJson::integer(SOCXlen));
    addField(SOC, "soc_xlen_follow_cpu",
             TypedJson::boolean(flatBool(values, "SOC_XLEN_FOLLOW_CPU")));
    const long long APBXlen =
        flatInt(values, "APB_XLEN_EFFECTIVE", SOCXlen);
    addField(SOC, "apb_xlen", TypedJson::integer(APBXlen));
    addField(SOC, "apb_xlen_follow_soc",
             TypedJson::boolean(flatBool(values, "APB_XLEN_FOLLOW_SOC", true)));
    struct PeripheralXlen {
        const char *name;
        const char *key;
    };
    static constexpr PeripheralXlen peripherals[] = {
        {"uart", "UART_XLEN"},
        {"gpio", "GPIO_XLEN"},
        {"keyboard", "KEYBOARD_XLEN"},
        {"vga", "VGA_XLEN"},
        {"spi", "SPI_XLEN"},
        {"psram", "PSRAM_XLEN"},
        {"sdram", "SDRAM_XLEN"},
        {"mrom", "MROM_XLEN"},
        {"sram", "SRAM_XLEN"},
    };
    TypedFields peripheral;
    for (const auto &item : peripherals) {
        addField(peripheral, std::string{item.name} + "_xlen",
                 TypedJson::integer(flatInt(
                     values, std::string{item.key} + "_EFFECTIVE", APBXlen)));
        addField(peripheral, std::string{item.name} + "_xlen_follow_apb",
                 TypedJson::boolean(flatBool(
                     values, std::string{item.key} + "_FOLLOW_APB", true)));
    }
    addField(SOC, "peripheral", TypedJson::object(std::move(peripheral)));
    addField(SOC, "chiplink",
             TypedJson::boolean(flatBool(values, "CHIPLINK")));
    addField(SOC, "sdram_use_axi",
             TypedJson::boolean(flatBool(values, "SDRAM_USE_AXI", true)));
    // 与 PMA_PSRAM_*/PMA_SDRAM_* 一起决定 full-SoC 的物理内存布局。
    addField(SOC, "memory_map_linux",
             TypedJson::boolean(flatBool(values, "SOC_MEMORY_MAP_LINUX")));
    const bool internalFollow =
        flatBool(values, "INTERNAL_BUS_WIDTH_FOLLOW_CPU", true);
    const long long internalWidth =
        internalFollow ? xlen
                       : flatInt(values, "INTERNAL_BUS_WIDTH",
                                 xlen == 64 ? 64 : 32);
    addField(SOC, "internal_bus_width_follow_cpu",
             TypedJson::boolean(internalFollow));
    addField(SOC, "internal_xlen", TypedJson::integer(internalWidth));
    addField(SOC, "internal_id_bits", TypedJson::integer(4));
    const long long synthFreq = flatInt(values, "SYNTH_FREQ", 450);
    TypedFields apb;
    addField(apb, "cpu_freq_mhz", TypedJson::integer(synthFreq));
    addField(apb, "device_freq_mhz",
             TypedJson::integer(flatInt(values, "APB_DEVICE_FREQ", 100)));
    addField(apb, "delay_scale",
             TypedJson::integer(flatInt(values, "APB_DELAY_SCALE", 64)));
    addField(SOC, "apb", TypedJson::object(std::move(apb)));
    TypedFields AXI;
    addField(AXI, "cpu_freq_mhz", TypedJson::integer(synthFreq));
    addField(AXI, "device_freq_mhz",
             TypedJson::integer(flatInt(values, "AXI_DEVICE_FREQ", 100)));
    addField(AXI, "delay_scale",
             TypedJson::integer(flatInt(values, "AXI_DELAY_SCALE", 64)));
    addField(SOC, "AXI", TypedJson::object(std::move(AXI)));
    addField(root, "SOC", TypedJson::object(std::move(SOC)));
}
void addBoot(TypedFields &root, const Config &values)
{
    TypedFields boot;
    const long long xlen = flatInt(values, "XLEN", 32);
    addField(boot, "sbi_enable",
             TypedJson::boolean(flatBool(values, "SBI_ENABLE")));
    addField(boot, "sbi_impl",
             TypedJson::string(flatString(values, "SBI_IMPL", "none")));
    addField(boot, "sbi_platform",
             TypedJson::string(flatString(values, "SBI_PLATFORM", "npc")));
    addField(boot, "sbi_fw_base",
             TypedJson::string(flatString(
                 values, "SBI_FW_BASE",
                 hexString(defaults::SbiFwBase(
                     flatInt(values, "NPC_RAM_BASE", defaults::NpcRamBase()))))));
    addField(boot, "sbi_fw_size",
             TypedJson::string(
                 flatString(values, "SBI_FW_SIZE",
                            hexString(defaults::SbiFwSize()))));
    addField(boot, "sbi_next_addr",
             TypedJson::string(
                 flatString(values, "SBI_NEXT_ADDR",
                            hexString(defaults::SbiNextAddr(xlen)))));
    addField(boot, "sbi_build_jobs",
             TypedJson::integer(flatInt(values, "SBI_BUILD_JOBS", 0)));
    addField(boot, "sbi_fdt_enable",
             TypedJson::boolean(flatBool(values, "SBI_FDT_ENABLE")));
    addField(boot, "sbi_fdt_path",
             TypedJson::string(
                 flatString(values, "SBI_FDT_PATH", "dts/npc.dts")));
    addField(boot, "sbi_fdt_addr",
             TypedJson::string(
                 flatString(values, "SBI_FDT_ADDR",
                            hexString(defaults::SbiFdtAddr()))));
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
                 flatString(values, "LINUX_INITRAMFS_ADDR",
                            hexString(defaults::LinuxInitramfsAddr()))));
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
