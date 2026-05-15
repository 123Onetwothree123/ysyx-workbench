#include "ftrace.hpp"

/**
 * @file ftrace.cpp
 * @brief NPC 的函数调用跟踪实现。
 *
 * @details
 * 这个文件负责从已提交指令中识别 RISC-V call/ret，维护当前调用栈，
 * 记录历史事件，并把事件按缩进格式打印出来。
 */

#include <print>
#include <string>
#include <utility>
namespace
{
    constexpr std::uint32_t OpcodeMask{0x7fu};
    constexpr std::uint32_t OpcodeJal{0x6fu};
    constexpr std::uint32_t OpcodeJalr{0x67u};
    constexpr std::uint32_t Funct3Mask{0x7u};
    constexpr std::uint32_t ReturnRegister{1u};
    constexpr std::uint64_t InstructionBytes{4u};
    constexpr std::string_view UnknownFunction{"???"};
    /**
     * @brief 取出一个整数中指定范围的位。
     *
     * @param Value std::uint32_t，原始整数。
     * @param High unsigned，最高位编号。
     * @param Low unsigned，最低位编号。
     * @return std::uint32_t，位范围 `[High, Low]` 对应的无符号值。
     */
    [[nodiscard]] constexpr std::uint32_t Bits(std::uint32_t Value, unsigned High, unsigned Low) noexcept
    {
        const auto Width{High - Low + 1u};
        const auto Mask{(1u << Width) - 1u};
        return (Value >> Low) & Mask;
    }
    /**
     * @brief 对指定位宽的立即数做符号扩展。
     *
     * @param Value std::uint32_t，原始立即数。
     * @param BitCount unsigned，立即数的有效位数。
     * @return std::int32_t，符号扩展后的有符号整数。
     */
    [[nodiscard]] constexpr std::int32_t SignExtend(std::uint32_t Value, unsigned BitCount) noexcept
    {
        const auto SignBit{1u << (BitCount - 1u)};
        return static_cast<std::int32_t>((Value ^ SignBit) - SignBit);
    }
    /**
     * @brief 取出 RISC-V 指令的 opcode 字段。
     *
     * @param Instruction std::uint32_t，原始指令。
     * @return std::uint32_t，opcode 字段。
     */
    [[nodiscard]] constexpr std::uint32_t Opcode(std::uint32_t Instruction) noexcept
    {
        return Instruction & OpcodeMask;
    }
    /**
     * @brief 取出 RISC-V 指令的 rd 字段。
     *
     * @param Instruction std::uint32_t，原始指令。
     * @return std::uint32_t，rd 寄存器编号。
     */
    [[nodiscard]] constexpr std::uint32_t Rd(std::uint32_t Instruction) noexcept
    {
        return Bits(Instruction, 11, 7);
    }
    /**
     * @brief 取出 RISC-V 指令的 funct3 字段。
     *
     * @param Instruction std::uint32_t，原始指令。
     * @return std::uint32_t，funct3 字段。
     */
    [[nodiscard]] constexpr std::uint32_t Funct3(std::uint32_t Instruction) noexcept
    {
        return Bits(Instruction, 14, 12) & Funct3Mask;
    }
    /**
     * @brief 取出 RISC-V 指令的 rs1 字段。
     *
     * @param Instruction std::uint32_t，原始指令。
     * @return std::uint32_t，rs1 寄存器编号。
     */
    [[nodiscard]] constexpr std::uint32_t Rs1(std::uint32_t Instruction) noexcept
    {
        return Bits(Instruction, 19, 15);
    }
    /**
     * @brief 取出 I 型指令立即数并做符号扩展。
     *
     * @param Instruction std::uint32_t，原始指令。
     * @return std::int32_t，符号扩展后的 I 型立即数。
     */
    [[nodiscard]] constexpr std::int32_t IImm(std::uint32_t Instruction) noexcept
    {
        return SignExtend(Bits(Instruction, 31, 20), 12);
    }
    /**
     * @brief 判断寄存器是否是链接寄存器。
     *
     * @param Reg std::uint32_t，寄存器编号。
     * @return bool，若是 x1/ra 或 x5/t0 则返回 true。
     */
    [[nodiscard]] constexpr bool IsLinkRegister(std::uint32_t Reg) noexcept
    {
        return Reg == 1u || Reg == 5u;
    }
    /**
     * @brief 判断一条指令是否是 ret。
     *
     * @param Instruction std::uint32_t，原始指令。
     * @return bool，若指令形如 `jalr x0, x1, 0` 则返回 true。
     */
    [[nodiscard]] constexpr bool IsReturnInstruction(std::uint32_t Instruction) noexcept
    {
        return Opcode(Instruction) == OpcodeJalr &&
               Funct3(Instruction) == 0u &&
               Rd(Instruction) == 0u &&
               Rs1(Instruction) == ReturnRegister &&
               IImm(Instruction) == 0;
    }
    /**
     * @brief 判断一条指令是否是函数调用。
     *
     * @param Instruction std::uint32_t，原始指令。
     * @return bool，若指令是写链接寄存器的 jal/jalr，且不是 ret，则返回 true。
     */
    [[nodiscard]] constexpr bool IsCallInstruction(std::uint32_t Instruction) noexcept
    {
        if (Opcode(Instruction) == OpcodeJal)
        {
            return IsLinkRegister(Rd(Instruction));
        }

        return Opcode(Instruction) == OpcodeJalr &&
               Funct3(Instruction) == 0u &&
               IsLinkRegister(Rd(Instruction)) &&
               !IsReturnInstruction(Instruction);
    }
    /**
     * @brief 根据事件类型和深度计算打印缩进。
     *
     * @param Event const FtraceEvent&，待打印的 ftrace 事件。
     * @return std::size_t，缩进层级，不是空格数量。
     */
    [[nodiscard]] constexpr std::size_t IndentFor(const FtraceEvent &Event) noexcept
    {
        if (Event.GetType() == FtraceEventType::Call)
        {
            return Event.GetDepth() == 0 ? 0 : Event.GetDepth() - 1;
        }
        return Event.GetDepth();
    }
    /**
     * @brief 返回适合打印的函数名。
     *
     * @param Name std::string_view，原始函数名。
     * @return std::string_view，若函数名为空则返回占位字符串，否则返回原函数名。
     */
    [[nodiscard]] std::string_view PrintableName(std::string_view Name) noexcept
    {
        return Name.empty() ? UnknownFunction : Name;
    }
} // namespace
/**
 * @brief 全局 ftrace 状态。
 */
Ftrace GlobalFtrace;
Ftrace::Ftrace() = default;
Ftrace::~Ftrace() = default;
/**
 * @brief 加载 ELF 文件并准备函数符号表。
 *
 * @param ElfFile std::filesystem::path，ELF 文件路径。
 * @return std::expected<void, std::string>，成功返回空结果，失败返回错误字符串。
 */
std::expected<void, std::string> Ftrace::LoadElf(std::filesystem::path ElfFile)
{
    auto Reader{Readelf::load(std::move(ElfFile))};
    if (!Reader)
    {
        return std::unexpected{Reader.error()};
    }

    Reset();
    Elf.emplace(std::move(*Reader));
    return {};
}
/**
 * @brief 清空 ELF 符号和已有 ftrace 状态。
 */
void Ftrace::ClearElf()
{
    Reset();
    Elf.reset();
}
/**
 * @brief 清空调用栈和历史事件。
 *
 * @details
 * 该函数保留已经加载的 ELF 符号，只清理运行时产生的 ftrace 状态。
 */
void Ftrace::Reset()
{
    CallStack.clear();
    EventHistory.clear();
}
/**
 * @brief 开启或关闭 ftrace。
 *
 * @param ShouldEnable bool，为 true 时开启 ftrace，为 false 时关闭 ftrace。
 */
void Ftrace::Enable(bool ShouldEnable) noexcept
{
    Enabled = ShouldEnable;
}
/**
 * @brief 关闭 ftrace。
 */
void Ftrace::Disable() noexcept
{
    Enabled = false;
}
/**
 * @brief 返回当前 ftrace 是否开启。
 *
 * @return bool，当前 ftrace 开关状态。
 */
bool Ftrace::IsEnabled() const noexcept
{
    return Enabled;
}
/**
 * @brief 设置是否记录完整历史事件。
 *
 * @param ShouldRecord bool，为 true 时记录历史事件，为 false 时只打印不保存。
 */
void Ftrace::SetRecordHistory(bool ShouldRecord) noexcept
{
    ShouldRecordHistory = ShouldRecord;
}
/**
 * @brief 返回当前是否记录完整历史事件。
 *
 * @return bool，当前历史记录开关状态。
 */
bool Ftrace::RecordHistory() const noexcept
{
    return ShouldRecordHistory;
}
/**
 * @brief 分析一条已提交指令，识别 call 或 ret 事件。
 *
 * @param PC std::uint64_t，当前指令的 PC。
 * @param Instruction std::uint32_t，当前指令编码。
 * @param NextPC std::uint64_t，当前指令执行后的下一条 PC。
 */
void Ftrace::OnInstruction(std::uint64_t PC, std::uint32_t Instruction, std::uint64_t NextPC)
{
    if (!Enabled)
    {
        return;
    }
    if (IsReturnInstruction(Instruction))
    {
        OnReturn(PC, NextPC);
        return;
    }
    if (IsCallInstruction(Instruction))
    {
        OnCall(PC, NextPC);
    }
}
/**
 * @brief 处理一次函数调用事件并更新栈和历史。
 *
 * @param CallPC std::uint64_t，调用指令所在的 PC。
 * @param FunctionAddress std::uint64_t，被调用函数的入口地址。
 */
void Ftrace::OnCall(std::uint64_t CallPC, std::uint64_t FunctionAddress)
{
    if (!Enabled)
    {
        return;
    }
    const auto FunctionName{ResolveFunctionName(FunctionAddress)};
    CallStack.emplace_back(CallPC, CallPC + InstructionBytes, FunctionAddress, FunctionName);

    PushEvent(FtraceEvent{
        FtraceEventType::Call,
        CallPC,
        FunctionAddress,
        FunctionName,
        CallStack.size(),
    });
}
/**
 * @brief 处理一次函数返回事件并更新栈和历史。
 *
 * @param CurrentPC std::uint64_t，当前 ret 指令所在的 PC。
 * @param TargetPC std::uint64_t，ret 跳转到的目标 PC。
 */
void Ftrace::OnReturn(std::uint64_t CurrentPC, std::uint64_t TargetPC)
{
    if (!Enabled)
    {
        return;
    }
    auto FunctionName{std::string_view{}};
    if (!CallStack.empty())
    {
        FunctionName = CallStack.back().GetFunctionName();
        CallStack.pop_back();
    }
    else
    {
        FunctionName = ResolveFunctionName(CurrentPC);
    }
    PushEvent(FtraceEvent{
        FtraceEventType::Return,
        CurrentPC,
        TargetPC,
        FunctionName,
        CallStack.size(),
    });
}
/**
 * @brief 获取当前调用深度。
 *
 * @return std::size_t，当前调用栈大小。
 */
std::size_t Ftrace::Depth() const noexcept
{
    return CallStack.size();
}
/**
 * @brief 获取已记录的历史事件数量。
 *
 * @return std::size_t，历史事件数量。
 */
std::size_t Ftrace::HistorySize() const noexcept
{
    return EventHistory.size();
}
/**
 * @brief 获取 ELF 中已加载的函数符号数量。
 *
 * @return std::size_t，函数符号数量，未加载 ELF 时返回 0。
 */
std::size_t Ftrace::FunctionCount() const noexcept
{
    return Elf ? Elf->functions().size() : 0u;
}
/**
 * @brief 获取当前调用栈顶帧。
 *
 * @return const FtraceFrame*，若调用栈非空则返回栈顶帧指针，否则返回 nullptr。
 */
const FtraceFrame *Ftrace::TopFrame() const noexcept
{
    if (CallStack.empty())
    {
        return nullptr;
    }
    return &CallStack.back();
}
/**
 * @brief 获取已记录的历史事件序列。
 *
 * @return std::span<const FtraceEvent>，历史事件的只读视图。
 */
std::span<const FtraceEvent> Ftrace::History() const noexcept
{
    return EventHistory;
}
/**
 * @brief 获取当前 ELF 读取器。
 *
 * @return const Readelf*，若已经加载 ELF 则返回读取器指针，否则返回 nullptr。
 */
const Readelf *Ftrace::ElfReader() const noexcept
{
    return Elf ? &*Elf : nullptr;
}
/**
 * @brief 打印当前调用栈。
 *
 * @details
 * 按从栈底到栈顶的顺序打印每一层函数调用。
 */
void Ftrace::PrintCurrentStack() const
{
    std::println("ftrace: call stack depth = {}", CallStack.size());
    for (std::size_t Index{0}; Index < CallStack.size(); ++Index)
    {
        const auto &Frame{CallStack[Index]};
        std::println("  #{} {} @ 0x{:08x} (call 0x{:08x}, ret 0x{:08x})",
                     Index,
                     PrintableName(Frame.GetFunctionName()),
                     Frame.GetFunctionAddress(),
                     Frame.GetCallPC(),
                     Frame.GetReturnPC());
    }
}
/**
 * @brief 打印已记录的历史事件。
 */
void Ftrace::PrintHistory() const
{
    std::println("ftrace: history size = {}", EventHistory.size());
    for (const auto &Event : EventHistory)
    {
        PrintEventLine(Event);
    }
}
/**
 * @brief 打印 ftrace 当前状态摘要。
 */
void Ftrace::PrintStatus() const
{
    const auto ElfName{Elf ? Elf->path().string() : std::string{"<none>"}};
    std::println("ftrace: enabled={}, record_history={}, depth={}, history={}, elf={}, functions={}",
                 Enabled,
                 ShouldRecordHistory,
                 CallStack.size(),
                 EventHistory.size(),
                 ElfName,
                 FunctionCount());
}
/**
 * @brief 根据地址解析函数名。
 *
 * @param Address std::uint64_t，待解析的虚拟地址。
 * @return std::string_view，找到函数时返回函数名，否则返回空视图。
 */
std::string_view Ftrace::ResolveFunctionName(std::uint64_t Address) const noexcept
{
    if (!Elf)
    {
        return {};
    }

    const auto Name{Elf->find_function_name(static_cast<std::size_t>(Address))};
    return Name.value_or(std::string_view{});
}
/**
 * @brief 记录并打印一次 ftrace 事件。
 *
 * @param Event FtraceEvent，待记录的事件。
 */
void Ftrace::PushEvent(FtraceEvent Event)
{
    PrintEventLine(Event);
    if (ShouldRecordHistory)
    {
        EventHistory.push_back(Event);
    }
}
/**
 * @brief 按当前深度打印一条 ftrace 事件。
 *
 * @param Event const FtraceEvent&，待打印的事件。
 */
void Ftrace::PrintEventLine(const FtraceEvent &Event) const
{
    const auto Indent{std::string(IndentFor(Event) * 2u, ' ')};
    if (Event.GetType() == FtraceEventType::Call)
    {
        std::println("0x{:08x}: {}call [{}@0x{:08x}]",
                     Event.GetCurrentPC(),
                     Indent,
                     PrintableName(Event.GetFunctionName()),
                     Event.GetTargetPC());
        return;
    }
    std::println("0x{:08x}: {}ret  [{}]",
                 Event.GetCurrentPC(),
                 Indent,
                 PrintableName(Event.GetFunctionName()));
}
/**
 * @brief 初始化全局 ftrace。
 *
 * @param ElfFile const std::filesystem::path&，ELF 文件路径。
 * @param ShouldEnable bool，初始化完成后是否开启 ftrace。
 * @return std::expected<void, std::string>，成功返回空结果，失败返回错误字符串。
 */
std::expected<void, std::string> InitializeFtrace(const std::filesystem::path &ElfFile, bool ShouldEnable)
{
    auto Result{GlobalFtrace.LoadElf(ElfFile)};
    if (!Result)
    {
        return Result;
    }
    GlobalFtrace.Enable(ShouldEnable);
    return {};
}
/**
 * @brief Verilog DPI-C 入口，记录一条已提交指令。
 *
 * @param PC std::uint64_t，当前指令的 PC。
 * @param Instruction std::uint32_t，当前指令编码。
 * @param NextPC std::uint64_t，当前指令执行后的下一条 PC。
 */
extern "C" void ftrace_record(std::uint64_t PC, std::uint32_t Instruction, std::uint64_t NextPC)
{
#ifdef CONFIG_FTRACE
    GlobalFtrace.OnInstruction(PC, Instruction, NextPC);
#else
    (void)PC;
    (void)Instruction;
    (void)NextPC;
#endif
}
