module;
module npc.sdb.command.infoCommand;
import npc.Output;
import npc.config;
import npc.DUT;
import npc.unicode;
import npc.sdb.NPCEvaluationContext;
import npc.sdb.TablePrinter;
import npc.sdb.command.WatchpointPool;
import npc.SimulationContext;

static constexpr std::string_view get_reg_abi_name(std::size_t idx) noexcept
{
    constexpr std::array abi{
        "zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
        "s0",   "s1", "a0", "a1", "a2", "a3", "a4", "a5",
        "a6",   "a7", "s2", "s3", "s4", "s5", "s6", "s7",
        "s8",   "s9", "s10", "s11", "t3", "t4", "t5", "t6",
    };
    if (idx < abi.size())
    {
        return abi[idx];
    }
    return {};
}
enum class RegisterClass
{
    ProgramCounter,
    Zero,
    ReturnAddress,
    StackPointer,
    GlobalPointer,
    ThreadPointer,
    Argument,
    Saved,
    Temporary,
    General,
};

static constexpr RegisterClass ClassifyRegister(std::string_view ArchName,
                                                std::string_view ABIName) noexcept
{
    if (ArchName == "pc")
    {
        return RegisterClass::ProgramCounter;
    }
    // 原描述只看 arch_name=="x0"，原颜色还接受 abi_name=="zero"。GPR 行的
    // arch_name 与 abi_name 来自同一下标，二者等价；这里合并为 Zero，
    // 使描述与颜色共用一次分类，输出不变。
    if (ArchName == "x0" || ABIName == "zero")
    {
        return RegisterClass::Zero;
    }
    if (ABIName == "ra")
    {
        return RegisterClass::ReturnAddress;
    }
    if (ABIName == "sp")
    {
        return RegisterClass::StackPointer;
    }
    if (ABIName == "gp")
    {
        return RegisterClass::GlobalPointer;
    }
    if (ABIName == "tp")
    {
        return RegisterClass::ThreadPointer;
    }
    if (!ABIName.empty() && ABIName[0] == 'a')
    {
        return RegisterClass::Argument;
    }
    if (!ABIName.empty() && ABIName[0] == 's')
    {
        return RegisterClass::Saved;
    }
    if (!ABIName.empty() && ABIName[0] == 't')
    {
        return RegisterClass::Temporary;
    }
    return RegisterClass::General;
}

struct RegisterStyle
{
    std::string_view description;
    std::string_view color;
};

static constexpr RegisterStyle GetRegisterStyle(RegisterClass kind) noexcept
{
    // 数组顺序必须与 RegisterClass 的枚举值一一对应。
    constexpr std::array<RegisterStyle, 10> styles{{
        {"程序计数器", ANSI::FG_YELLOW},
        {"零寄存器", ANSI::FG_WHITE},
        {"返回地址", ANSI::FG_CYAN},
        {"栈指针", ANSI::FG_YELLOW},
        {"全局指针", ANSI::FG_BLUE},
        {"线程指针", ANSI::FG_MAGENTA},
        {"参数寄存器", ANSI::FG_GREEN},
        {"保存寄存器", ANSI::FG_CYAN},
        {"临时寄存器", ANSI::FG_MAGENTA},
        {"通用寄存器", ANSI::FG_WHITE},
    }};
    return styles[static_cast<std::size_t>(kind)];
}

struct Header
{
    std::string_view text;
    std::string_view color;
    bool center;
};

// 表头文本同时用于列宽初值和打印，颜色与居中方式随条目一起给出。
static constexpr std::array<Header, 5> RegisterHeaders{{
    {"编号", ANSI::FG_CYAN, true},
    {"寄存器", ANSI::FG_BLUE, false},
    {"十进制", ANSI::FG_WHITE, true},
    {"十六进制", ANSI::FG_GREEN, true},
    {"说明", ANSI::FG_YELLOW, false},
}};
static void PrintGPR(DUT &dut, Output &out)
{
    struct RegRow
    {
        std::string arch_name;
        std::string_view abi_name;
        std::uint64_t value;
        std::string_view desc;
    };
    constexpr auto nr_rows{std::size_t{33}};
    RegRow rows[nr_rows];
    for (std::size_t i{0}; i < nr_rows; i++)
    {
        if (i == 0)
        {
            rows[i].arch_name = "pc";
            rows[i].abi_name = "pc";
            auto result{dut.ReadPC()};
            rows[i].value = result ? *result : 0;
        }
        else
        {
            auto reg_idx{i - 1};
            rows[i].arch_name = std::format("x{}", reg_idx);
            rows[i].abi_name = get_reg_abi_name(reg_idx);
            auto result{dut.ReadGPR(static_cast<std::uint32_t>(reg_idx))};
            rows[i].value = result ? *result : 0;
        }
        rows[i].desc =
            GetRegisterStyle(ClassifyRegister(rows[i].arch_name, rows[i].abi_name)).description;
    }
    std::vector<int> ColumnWidths;
    ColumnWidths.reserve(RegisterHeaders.size());
    for (const auto &header : RegisterHeaders)
    {
        ColumnWidths.push_back(display_width(header.text));
    }
    // 下标与 RegisterHeaders 的列顺序一致。
    for (std::size_t i{0}; i < nr_rows; i++)
    {
        auto w{display_width(rows[i].arch_name)};
        if (w > ColumnWidths[0])
        {
            ColumnWidths[0] = w;
        }
        w = display_width(rows[i].abi_name);
        if (w > ColumnWidths[1])
        {
            ColumnWidths[1] = w;
        }
        w = display_width(std::to_string(rows[i].value));
        if (w > ColumnWidths[2])
        {
            ColumnWidths[2] = w;
        }
        w = display_width(std::format("0x{:0{}x}", rows[i].value,
                                      npc::XHexDigits));
        if (w > ColumnWidths[3])
        {
            ColumnWidths[3] = w;
        }
        w = display_width(rows[i].desc);
        if (w > ColumnWidths[4])
        {
            ColumnWidths[4] = w;
        }
    }
    out.Info("寄存器状态：");
    print_border(ColumnWidths);
    std::print("{}|{}", ANSI::FG_BLUE, ANSI::NONE);
    for (std::size_t i{0}; i < RegisterHeaders.size(); i++)
    {
        print_cell_colored(
            std::format("{}{}{}", RegisterHeaders[i].color, RegisterHeaders[i].text, ANSI::NONE),
            ColumnWidths[i], RegisterHeaders[i].center);
    }
    std::print("\n");
    print_border(ColumnWidths);
    for (std::size_t i{0}; i < nr_rows; i++)
    {
        const auto RowRegisterStyle{
            GetRegisterStyle(ClassifyRegister(rows[i].arch_name, rows[i].abi_name))};
        const auto &RowColor{RowRegisterStyle.color};
        auto id_str{std::format("{}{}{}", RowColor, rows[i].arch_name, ANSI::NONE)};
        auto name_str{std::format("{}{}{}", RowColor, rows[i].abi_name, ANSI::NONE)};
        auto dec_str{std::format("{}{}{}", ANSI::FG_WHITE, rows[i].value, ANSI::NONE)};
        auto hex_str{std::format("{}0x{:0{}x}{}", RowColor, rows[i].value,
                                 npc::XHexDigits, ANSI::NONE)};
        auto desc_str{std::format("{}{}{}", RowColor, rows[i].desc, ANSI::NONE)};
        std::print("{}|{}", ANSI::FG_BLUE, ANSI::NONE);
        print_cell_colored(id_str, ColumnWidths[0], RegisterHeaders[0].center);
        print_cell_colored(name_str, ColumnWidths[1], RegisterHeaders[1].center);
        print_cell_colored(dec_str, ColumnWidths[2], RegisterHeaders[2].center);
        print_cell_colored(hex_str, ColumnWidths[3], RegisterHeaders[3].center);
        print_cell_colored(desc_str, ColumnWidths[4], RegisterHeaders[4].center);
        std::print("\n");
        print_border(ColumnWidths);
    }
}

std::string_view infoCommand::name() const noexcept
{
    return "info";
}
SDBCommandUsageList infoCommand::usage() const noexcept
{
    if constexpr (npc::HasWatchpoint)
    {
        static const SDBCommandUsage entries[]{
            {"r", "打印通用寄存器"},
            {"w", "打印监视点状态"}};
        return entries;
    }
    else
    {
        static const SDBCommandUsage entries[]{
            {"r", "打印通用寄存器"}};
        return entries;
    }
}
SDBCommandResult infoCommand::execute(SDBCommandContext &context, std::string_view args)
{
    auto &out{context.GetSimulation().Out()};
    if (args == "r")
    {
        PrintGPR(context.GetDUT(), out);
        return SDBCommandResult::Continue;
    }
    if constexpr (npc::HasWatchpoint)
    {
        if (args == "w")
        {
            NPCEvaluationContext EvalContext{context.GetDUT()};
            context.GetSimulation().Watchpoints().PrintAllWatchpoints(EvalContext);
            return SDBCommandResult::Continue;
        }
        out.Info("用法：info r 或 info w");
    }
    else
    {
        out.Info("用法：info r（监视点未启用）");
    }
    return SDBCommandResult::Continue;
}
