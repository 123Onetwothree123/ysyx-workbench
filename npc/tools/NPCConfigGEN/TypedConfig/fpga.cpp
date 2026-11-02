module npc.configgen;
import std;
namespace npc::configgen {
void addFpga(TypedFields &root, const Config &values)
{
    TypedFields fpga;
    addField(fpga, "enabled",
             TypedJson::boolean(flatBool(values, "FPGA_ENABLE")));
    addField(fpga, "vendor",
             TypedJson::string(flatString(values, "FPGA_VENDOR", "amd")));
    addField(fpga, "family",
             TypedJson::string(flatString(values, "FPGA_FAMILY", "unknown")));
    addField(fpga, "device",
             TypedJson::string(flatString(values, "FPGA_DEVICE")));
    addField(fpga, "package",
             TypedJson::string(flatString(values, "FPGA_PACKAGE")));
    addField(fpga, "speed_grade",
             TypedJson::string(flatString(values, "FPGA_SPEED_GRADE")));
    addField(fpga, "board",
             TypedJson::string(flatString(values, "FPGA_BOARD", "custom")));
    addField(fpga, "toolchain",
             TypedJson::string(flatString(values, "FPGA_TOOLCHAIN", "vivado")));
    addField(fpga, "toolchain_version",
             TypedJson::string(
                 flatString(values, "FPGA_TOOLCHAIN_VERSION")));
    addField(fpga, "toolchain_path",
             TypedJson::string(flatString(values, "FPGA_TOOLCHAIN_PATH")));
    addField(fpga, "project_name",
             TypedJson::string(
                 flatString(values, "FPGA_PROJECT_NAME", "npc_fpga")));
    addField(fpga, "build_dir",
             TypedJson::string(
                 flatString(values, "FPGA_BUILD_DIR", "build/fpga")));
    addField(fpga, "timing_constraints",
             TypedJson::string(
                 flatString(values, "FPGA_TIMING_CONSTRAINTS")));
    addField(fpga, "pin_constraints",
             TypedJson::string(
                 flatString(values, "FPGA_PIN_CONSTRAINTS")));
    addField(fpga, "tcl_hook",
             TypedJson::string(flatString(values, "FPGA_TCL_HOOK")));
    addField(fpga, "jobs",
             TypedJson::integer(flatInt(values, "FPGA_JOBS", 0)));
    addField(fpga, "non_project_mode",
             TypedJson::boolean(
                 flatBool(values, "FPGA_NON_PROJECT_MODE", true)));
    addField(fpga, "synth_strategy",
             TypedJson::string(
                 flatString(values, "FPGA_SYNTH_STRATEGY", "default")));
    addField(fpga, "impl_strategy",
             TypedJson::string(
                 flatString(values, "FPGA_IMPL_STRATEGY", "default")));
    addField(fpga, "incremental",
             TypedJson::boolean(flatBool(values, "FPGA_INCREMENTAL")));
    addField(fpga, "phys_opt",
             TypedJson::boolean(flatBool(values, "FPGA_PHYS_OPT")));
    addField(fpga, "report_timing",
             TypedJson::boolean(
                 flatBool(values, "FPGA_REPORT_TIMING", true)));
    addField(fpga, "report_utilization",
             TypedJson::boolean(
                 flatBool(values, "FPGA_REPORT_UTILIZATION", true)));
    addField(fpga, "bitstream",
             TypedJson::boolean(flatBool(values, "FPGA_BITSTREAM", true)));
    addField(fpga, "bitstream_format",
             TypedJson::string(
                 flatString(values, "FPGA_BITSTREAM_FORMAT", "bit")));
    addField(fpga, "program_mode",
             TypedJson::string(
                 flatString(values, "FPGA_PROGRAM_MODE", "jtag")));
    addField(fpga, "programmer",
             TypedJson::string(
                 flatString(values, "FPGA_PROGRAMMER", "auto")));
    addField(fpga, "flash_offset",
             TypedJson::string(
                 flatString(values, "FPGA_FLASH_OFFSET", "0x00000000")));

    TypedFields clock;
    addField(clock, "input_mhz",
             TypedJson::integer(flatInt(values, "FPGA_INPUT_CLOCK_MHZ", 100)));
    addField(clock, "system_mhz",
             TypedJson::integer(
                 flatInt(values, "FPGA_SYSTEM_CLOCK_MHZ", 50)));
    addField(clock, "source",
             TypedJson::string(flatString(values, "FPGA_CLOCK_SOURCE", "mmcm")));
    addField(clock, "buffer",
             TypedJson::boolean(flatBool(values, "FPGA_CLOCK_BUFFER", true)));
    addField(clock, "reset_polarity",
             TypedJson::string(
                 flatString(values, "FPGA_RESET_POLARITY", "active_low")));
    addField(clock, "reset_sync",
             TypedJson::boolean(flatBool(values, "FPGA_RESET_SYNC", true)));
    addField(clock, "uncertainty_ns",
             TypedJson::string(
                 flatString(values, "FPGA_CLOCK_UNCERTAINTY_NS", "0.100")));
    addField(clock, "timing_margin_ns",
             TypedJson::string(
                 flatString(values, "FPGA_TIMING_MARGIN_NS", "0.000")));
    addField(fpga, "clock", TypedJson::object(std::move(clock)));

    TypedFields memory;
    addField(memory, "model",
             TypedJson::string(
                 flatString(values, "FPGA_MEMORY_MODEL", "ddr")));
    addField(memory, "ddr_type",
             TypedJson::string(flatString(values, "FPGA_DDR_TYPE", "ddr4")));
    addField(memory, "ddr_size_mb",
             TypedJson::integer(
                 flatInt(values, "FPGA_DDR_SIZE_MB", 1024)));
    addField(memory, "ddr_data_width",
             TypedJson::integer(
                 flatInt(values, "FPGA_DDR_DATA_WIDTH", 64)));
    addField(memory, "ddr_freq_mhz",
             TypedJson::integer(
                 flatInt(values, "FPGA_DDR_FREQ_MHZ", 800)));
    addField(memory, "ddr_controller",
             TypedJson::string(
                 flatString(values, "FPGA_DDR_CONTROLLER", "ysyx_soc")));
    addField(memory, "bram_budget_kb",
             TypedJson::integer(
                 flatInt(values, "FPGA_BRAM_BUDGET_KB", 4096)));
    addField(memory, "uram",
             TypedJson::boolean(flatBool(values, "FPGA_URAM_ENABLE")));
    addField(memory, "bootrom_size_kb",
             TypedJson::integer(
                 flatInt(values, "FPGA_BOOTROM_SIZE_KB", 128)));
    addField(memory, "scratchpad_size_kb",
             TypedJson::integer(
                 flatInt(values, "FPGA_SCRATCHPAD_SIZE_KB", 256)));
    addField(memory, "init_file",
             TypedJson::string(
                 flatString(values, "FPGA_MEMORY_INIT_FILE")));
    addField(memory, "init_format",
             TypedJson::string(
                 flatString(values, "FPGA_MEMORY_INIT_FORMAT", "hex")));
    addField(fpga, "memory", TypedJson::object(std::move(memory)));

    TypedFields peripherals;
    addField(peripherals, "uart",
             TypedJson::boolean(flatBool(values, "FPGA_UART_ENABLE", true)));
    addField(peripherals, "uart_count",
             TypedJson::integer(flatInt(values, "FPGA_UART_COUNT", 1)));
    addField(peripherals, "uart_baud",
             TypedJson::integer(
                 flatInt(values, "FPGA_UART_BAUD", 115200)));
    addField(peripherals, "spi_flash",
             TypedJson::boolean(
                 flatBool(values, "FPGA_SPI_FLASH_ENABLE", true)));
    addField(peripherals, "spi_flash_size_mb",
             TypedJson::integer(
                 flatInt(values, "FPGA_SPI_FLASH_SIZE_MB", 16)));
    addField(peripherals, "spi_flash_mode",
             TypedJson::string(
                 flatString(values, "FPGA_SPI_FLASH_MODE", "qspi")));
    addField(peripherals, "sd",
             TypedJson::boolean(flatBool(values, "FPGA_SD_ENABLE")));
    addField(peripherals, "eth",
             TypedJson::boolean(flatBool(values, "FPGA_ETH_ENABLE")));
    addField(peripherals, "eth_phy",
             TypedJson::string(
                 flatString(values, "FPGA_ETH_PHY", "rgmii")));
    addField(peripherals, "gpio",
             TypedJson::boolean(flatBool(values, "FPGA_GPIO_ENABLE", true)));
    addField(peripherals, "gpio_width",
             TypedJson::integer(flatInt(values, "FPGA_GPIO_WIDTH", 16)));
    addField(peripherals, "vga",
             TypedJson::boolean(flatBool(values, "FPGA_VGA_ENABLE")));
    addField(peripherals, "hdmi",
             TypedJson::boolean(flatBool(values, "FPGA_HDMI_ENABLE")));
    addField(peripherals, "ps2",
             TypedJson::boolean(flatBool(values, "FPGA_PS2_ENABLE")));
    addField(peripherals, "dma",
             TypedJson::boolean(flatBool(values, "FPGA_DMA_ENABLE", true)));
    addField(peripherals, "jtag",
             TypedJson::boolean(flatBool(values, "FPGA_JTAG_ENABLE", true)));
    addField(fpga, "peripherals", TypedJson::object(std::move(peripherals)));

    TypedFields debug;
    addField(debug, "ila",
             TypedJson::boolean(flatBool(values, "FPGA_ILA_ENABLE")));
    addField(debug, "ila_depth",
             TypedJson::integer(flatInt(values, "FPGA_ILA_DEPTH", 1024)));
    addField(debug, "ila_clock",
             TypedJson::string(
                 flatString(values, "FPGA_ILA_CLOCK", "system")));
    addField(debug, "vio",
             TypedJson::boolean(flatBool(values, "FPGA_VIO_ENABLE")));
    addField(debug, "signaltap",
             TypedJson::boolean(
                 flatBool(values, "FPGA_SIGNALTAP_ENABLE")));
    addField(debug, "trace",
             TypedJson::boolean(flatBool(values, "FPGA_TRACE_ENABLE")));
    addField(debug, "probe_width",
             TypedJson::integer(flatInt(values, "FPGA_PROBE_WIDTH", 32)));
    addField(debug, "debug_uart",
             TypedJson::boolean(
                 flatBool(values, "FPGA_DEBUG_UART", true)));
    addField(fpga, "debug", TypedJson::object(std::move(debug)));

    TypedFields resources;
    addField(resources, "lut_budget",
             TypedJson::integer(flatInt(values, "FPGA_LUT_BUDGET", 0)));
    addField(resources, "ff_budget",
             TypedJson::integer(flatInt(values, "FPGA_FF_BUDGET", 0)));
    addField(resources, "bram_tile_budget",
             TypedJson::integer(
                 flatInt(values, "FPGA_BRAM_TILE_BUDGET", 0)));
    addField(resources, "dsp_budget",
             TypedJson::integer(flatInt(values, "FPGA_DSP_BUDGET", 0)));
    addField(resources, "max_fanout",
             TypedJson::integer(flatInt(values, "FPGA_MAX_FANOUT", 0)));
    addField(resources, "resource_report",
             TypedJson::boolean(
                 flatBool(values, "FPGA_RESOURCE_REPORT", true)));
    addField(resources, "timing_summary",
             TypedJson::boolean(
                 flatBool(values, "FPGA_TIMING_SUMMARY", true)));
    addField(fpga, "resources", TypedJson::object(std::move(resources)));

    addField(root, "fpga", TypedJson::object(std::move(fpga)));
}
}
