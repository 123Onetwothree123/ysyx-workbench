#include <verilated.h>
#include <VRV32E32Reg.h>
#include <print>
#include <iostream>
#include <cstdint>
#include <expected>
#include <format>
#include <memory>
#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include "difftest.hpp"
#include "memory.hpp"
#include "NPC_SDB.hpp"
#include "SDBDPI.hpp"
#include "trace.hpp"

bool npc_halted = false;
static uint32_t halt_pc{0};  // 记录停止的时候的PC
static uint32_t halt_ret{0}; // 返回码，0是good，1是bad

void reset_dut(std::unique_ptr<VRV32E32Reg> &top); // 复位Device Under Test被测设计的

namespace
{
    struct CliOptions
    {
        std::optional<std::filesystem::path> image_file{};
        std::optional<std::filesystem::path> elf_file{};
        std::optional<std::filesystem::path> diff_ref_so{};
        bool ftrace_enabled{true};
        bool batch_mode{false};
    };

    std::expected<CliOptions, std::string> parse_cli(int argc, char const *argv[])
    {
        CliOptions options;
        std::optional<std::filesystem::path> image_file;

        for (int i = 1; i < argc; ++i)
        {
            const std::string_view arg{argv[i]};
            if (arg == "--elf" || arg == "-e")
            {
                if (i + 1 >= argc)
                {
                    return std::unexpected{"--elf 需要跟一个ELF文件路径"};
                }
                options.elf_file = std::filesystem::path{argv[++i]};
            }
            else if (arg.starts_with("--elf="))
            {
                options.elf_file = std::filesystem::path{std::string{arg.substr(6)}};
            }
            else if (arg == "--ftrace")
            {
                options.ftrace_enabled = true;
            }
            else if (arg == "--no-ftrace")
            {
                options.ftrace_enabled = false;
            }
            else if (arg == "--batch" || arg == "-b")
            {
                options.batch_mode = true;
            }
            else if (arg == "--diff")
            {
                if (i + 1 >= argc)
                {
                    return std::unexpected{"--diff 需要跟一个NEMU动态库路径"};
                }
                options.diff_ref_so = std::filesystem::path{argv[++i]};
            }
            else if (arg.starts_with("--diff="))
            {
                options.diff_ref_so = std::filesystem::path{std::string{arg.substr(7)}};
            }
            else if (arg.starts_with("-"))
            {
                return std::unexpected{std::format("未知参数: {}", arg)};
            }
            else if (!image_file)
            {
                image_file = std::filesystem::path{std::string{arg}};
            }
            else
            {
                return std::unexpected{std::format("多余的镜像文件参数: {}", arg)};
            }
        }
        options.image_file = std::move(image_file);
        return options;
    }
    std::optional<std::filesystem::path> infer_elf_path(const std::filesystem::path &image_file)
    {
        auto candidate = image_file;
        candidate.replace_extension(".elf");
        if (candidate != image_file && std::filesystem::exists(candidate))
        {
            return candidate;
        }
        return std::nullopt;
    }
    std::expected<void, std::string> init_trace_from_cli(const CliOptions &options)
    {
        auto elf_file = options.elf_file;
#ifdef CONFIG_FTRACE
        if (!elf_file && options.ftrace_enabled)
        {
            if (options.image_file)
            {
                elf_file = infer_elf_path(*options.image_file);
            }
        }
#endif
        if (elf_file)
        {
            auto result = InitializeFtrace(*elf_file, options.ftrace_enabled);
            if (!result)
            {
                return result;
            }
            std::println("ELF加载了: {}, functions = {}", elf_file->string(), GlobalFtrace.FunctionCount());
        }
#ifdef CONFIG_FTRACE
        else if (options.ftrace_enabled && options.image_file)
        {
            return std::unexpected{"CONFIG_FTRACE=y 需要 --elf FILE，或者镜像旁边存在同名 .elf"};
        }
#endif
        return {};
    }
} // namespace

extern "C" void npc_ebreak(int pc, int code)
{
    npc_halted = true;
    halt_pc = static_cast<uint32_t>(pc);
    halt_ret = static_cast<uint32_t>(code);
}
void reset_dut(std::unique_ptr<VRV32E32Reg> &top)
{
    top->sdb_debug_clk = 0;
    top->sdb_pc_write_en = 0;
    top->sdb_pc_write_data = 0;
    top->sdb_gpr_write_en = 0;
    top->sdb_gpr_write_addr = 0;
    top->sdb_gpr_write_data = 0;
    top->clk = 0;
    top->rst = 1;
    top->eval();
    top->clk = 1;
    top->eval();
    top->clk = 0;
    top->rst = 0;
    top->eval();
}
int main(int argc, char const *argv[])
{
    auto options{parse_cli(argc, argv)};
    if (!options)
    {
        std::println(std::cerr, "{}", options.error());
        return 1;
    }
    Verilated::commandArgs(argc, argv);
    std::size_t image_size{0};
    if (options->image_file)
    {
        const auto result{load_file(*options->image_file)};
        if (!result)
        {
            std::println(std::cerr, "{}", result.error());
            return 1;
        }
        image_size = result.value();
        std::println("文件加载了: {}, size = {} bytes", options->image_file->string(), image_size);
    }
    else
    {
        image_size = load_builtin_image();
        std::println("没有指定镜像，使用内置镜像，size = {} bytes", image_size);
    }
    auto trace_init{init_trace_from_cli(*options)};
    if (!trace_init)
    {
        std::println(std::cerr, "{}", trace_init.error());
        return 1;
    }
    init_disasm();
    auto top{std::make_unique<VRV32E32Reg>()}; // 管不了了复制修改以前代码，直接创建顶层的对象然后实例
    size_t cycles{0};                          // 统计总周期数的
    reset_dut(top);
    SDBDPISetTopScope(top->name(), top->modelName());
    auto difftest_init{DifftestInitialize(options->diff_ref_so, image_size)};
    if (!difftest_init)
    {
        std::println(std::cerr, "{}", difftest_init.error());
        return 1;
    }
    sdb_main_loop(top, cycles, options->batch_mode);
    top->final();
    if (npc_halted)
    {
        if (halt_ret == 0)
        {
            std::println("HIT GOOD TRAP at pc = 0x{:08x}, cycles = {}", halt_pc, cycles);
            return 0;
        }
        PrintIringbuf(halt_pc);
        std::println(std::cerr, "HIT BAD TRAP at pc = 0x{:08x}, code = {}, cycles = {}", halt_pc, halt_ret, cycles);
        return 1;
    }
    return 0;
}