module;
#include <elf.h>
module npc.trace.ftrace;
/**
 * @file ftrace.cpp
 * @brief NPC的函数调用跟踪实现。
 * @details
 * 这个文件负责从已提交指令中识别RISC-V call/ret，维护当前调用栈，
 * 记录历史事件，并把事件按缩进格式打印出来。
 */
namespace
{
    constexpr auto OpcodeMask{std::uint32_t{0x7fu}};
    constexpr auto OpcodeJal{std::uint32_t{0x6fu}};
    constexpr auto OpcodeJalr{std::uint32_t{0x67u}};
    constexpr auto Funct3Mask{std::uint32_t{0x7u}};
    constexpr auto InstructionBytes{std::uint64_t{4u}};
    constexpr auto UnknownFunction{std::string_view{"???"}};
    /**
     * @brief 取出一个整数中指定范围的位。
     * @param Value std::uint32_t，原始整数。
     * @param High unsigned，最高位编号。
     * @param Low unsigned，最低位编号。
     * @return std::uint32_t，位范围`[High, Low]`对应的无符号值。
     */
    [[nodiscard]] constexpr std::uint32_t Bits(std::uint32_t Value, unsigned High, unsigned Low) noexcept
    {
        const auto Width{High - Low + 1u};
        const auto Mask{(1u << Width) - 1u};
        return (Value >> Low) & Mask;
    }
    /**
     * @brief 取出RISC-V指令的opcode字段。
     * @param Instruction std::uint32_t，原始指令。
     * @return std::uint32_t，opcode字段。
     */
    [[nodiscard]] constexpr std::uint32_t Opcode(std::uint32_t Instruction) noexcept
    {
        return Instruction & OpcodeMask;
    }
    /**
     * @brief 取出RISC-V指令的rd字段。
     * @param Instruction std::uint32_t，原始指令。
     * @return std::uint32_t，rd寄存器编号。
     */
    [[nodiscard]] constexpr std::uint32_t Rd(std::uint32_t Instruction) noexcept
    {
        return Bits(Instruction, 11, 7);
    }
    /**
     * @brief 取出RISC-V指令的funct3字段。
     * @param Instruction std::uint32_t，原始指令。
     * @return std::uint32_t，funct3字段。
     */
    [[nodiscard]] constexpr std::uint32_t Funct3(std::uint32_t Instruction) noexcept
    {
        return Bits(Instruction, 14, 12) & Funct3Mask;
    }
    /**
     * @brief 取出RISC-V指令的rs1字段。
     * @param Instruction std::uint32_t，原始指令。
     * @return std::uint32_t，rs1寄存器编号。
     */
    [[nodiscard]] constexpr std::uint32_t Rs1(std::uint32_t Instruction) noexcept
    {
        return Bits(Instruction, 19, 15);
    }
    /**
     * @brief 判断寄存器是否是链接寄存器。
     * @param Reg std::uint32_t，寄存器编号。
     * @return bool，若是x1/ra或x5/t0则返回true。
     */
    [[nodiscard]] constexpr bool IsLinkRegister(std::uint32_t Reg) noexcept
    {
        return Reg == 1u || Reg == 5u;
    }
    /**
     * @brief 判断一条指令是否带有RAS pop hint。
     * @param Instruction std::uint32_t，原始指令。
     * @return bool，若JALR的rs1是x1/x5，且rd不是同一个链接寄存器，则返回true。
     * @details RISC-V的RAS hint只由rd/rs1决定，不要求立即数为0。
     *          rd和rs1分别为x1/x5且不同时，该JALR同时还带有push hint。
     */
    [[nodiscard]] constexpr bool IsReturnInstruction(std::uint32_t Instruction) noexcept
    {
        const auto Destination{Rd(Instruction)};
        const auto Source{Rs1(Instruction)};
        return Opcode(Instruction) == OpcodeJalr &&
               Funct3(Instruction) == 0u &&
               IsLinkRegister(Source) &&
               (!IsLinkRegister(Destination) || Destination != Source);
    }
    /**
     * @brief 判断一条指令是否是函数调用。
     * @param Instruction std::uint32_t，原始指令。
     * @return bool，若指令是写链接寄存器的jal/jalr，则返回true。
     * @details 协程JALR可以同时是return和call，因此这里不再排除pop hint。
     */
    [[nodiscard]] constexpr bool IsCallInstruction(std::uint32_t Instruction) noexcept
    {
        if (Opcode(Instruction) == OpcodeJal)
        {
            return IsLinkRegister(Rd(Instruction));
        }
        return Opcode(Instruction) == OpcodeJalr &&
               Funct3(Instruction) == 0u &&
               IsLinkRegister(Rd(Instruction));
    }
    /**
     * @brief 根据事件类型和深度计算打印缩进。
     * @param Event const FtraceEvent&，待打印的ftrace事件。
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
     * @param Name std::string_view，原始函数名。
     * @return std::string_view，若函数名为空则返回占位字符串，否则返回原函数名。
     */
    [[nodiscard]] std::string_view PrintableName(std::string_view Name) noexcept
    {
        return Name.empty() ? UnknownFunction : Name;
    }
} // namespace
/**
 * @brief 全局ftrace状态。
 */
Ftrace GlobalFtrace;
Ftrace::Ftrace() = default;
Ftrace::~Ftrace() = default;
/**
 * @brief 加载ELF文件并准备函数符号表。
 * @param ElfFile std::filesystem::path，ELF文件路径。
 * @param RequireExecutable bool，运行时Ftrace为true；纯ELF检查为false。
 * @return std::expected<void, std::string>，成功返回空结果，失败返回错误字符串。
 */
std::expected<void, std::string> Ftrace::LoadElf(
    std::filesystem::path ElfFile,
    bool RequireExecutable)
{
    auto Reader{Readelf::load(std::move(ElfFile))};
    if (!Reader)
    {
        return std::unexpected{Reader.error()};
    }
    // Readelf is intentionally a general ELF inspection utility and may load
    // relocatable/shared objects.  Ftrace, however, compares symbol values
    // directly with the retired runtime PC and has neither section placement
    // nor a dynamic load bias with which to relocate ET_REL/ET_DYN symbols.
    // Accepting those files produces plausible-looking but systematically
    // wrong function names, so require a fixed-address executable here.
    if (RequireExecutable && Reader->header().e_type != ET_EXEC)
    {
        return std::unexpected{std::format(
            "Ftrace需要固定地址的ET_EXEC文件，当前ELF类型为{}",
            static_cast<unsigned>(Reader->header().e_type))};
    }
    Reset();
    Elf.emplace(std::move(*Reader));
    return {};
}
/**
 * @brief 清空ELF符号和已有ftrace状态。
 */
void Ftrace::ClearElf()
{
    Reset();
    Elf.reset();
}
/**
 * @brief 清空调用栈和历史事件。
 * @details
 * 该函数保留已经加载的ELF符号，只清理运行时产生的ftrace状态。
 */
void Ftrace::Reset()
{
    CallStack.clear();
    EventHistory.clear();
}
/**
 * @brief 开启或关闭ftrace。
 * @param ShouldEnable bool，为true时开启ftrace，为false时关闭ftrace。
 */
void Ftrace::Enable(bool ShouldEnable) noexcept
{
    Enabled = ShouldEnable;
}
/**
 * @brief 关闭ftrace。
 */
void Ftrace::Disable() noexcept
{
    Enabled = false;
}
/**
 * @brief 返回当前ftrace是否开启。
 * @return bool，当前ftrace开关状态。
 */
bool Ftrace::IsEnabled() const noexcept
{
    return Enabled;
}
/**
 * @brief 设置是否记录完整历史事件。
 * @param ShouldRecord bool，为true时记录历史事件，为false时只打印不保存。
 */
void Ftrace::SetRecordHistory(bool ShouldRecord) noexcept
{
    ShouldRecordHistory = ShouldRecord;
}
/**
 * @brief 返回当前是否记录完整历史事件。
 * @return bool，当前历史记录开关状态。
 */
bool Ftrace::RecordHistory() const noexcept
{
    return ShouldRecordHistory;
}
/**
 * @brief 分析一条已提交指令，识别call或ret事件。
 * @param PC std::uint64_t，当前指令的PC。
 * @param Instruction std::uint32_t，当前指令编码。
 * @param NextPC std::uint64_t，当前指令执行后的下一条PC。
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
    }
    if (IsCallInstruction(Instruction))
    {
        OnCall(PC, NextPC);
    }
}
/**
 * @brief 处理一次函数调用事件并更新栈和历史。
 * @param CallPC std::uint64_t，调用指令所在的PC。
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
 * @param CurrentPC std::uint64_t，当前ret指令所在的PC。
 * @param TargetPC std::uint64_t，ret跳转到的目标PC。
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
 * @return std::size_t，当前调用栈大小。
 */
std::size_t Ftrace::Depth() const noexcept
{
    return CallStack.size();
}
/**
 * @brief 获取已记录的历史事件数量。
 * @return std::size_t，历史事件数量。
 */
std::size_t Ftrace::HistorySize() const noexcept
{
    return EventHistory.size();
}
/**
 * @brief 获取ELF中已加载的函数符号数量。
 * @return std::size_t，函数符号数量，未加载ELF时返回 0。
 */
std::size_t Ftrace::FunctionCount() const noexcept
{
    return Elf ? Elf->functions().size() : 0u;
}
/**
 * @brief 获取当前调用栈顶帧。
 * @return const FtraceFrame*，若调用栈非空则返回栈顶帧指针，否则返回nullptr。
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
 * @return std::span<const FtraceEvent>，历史事件的只读视图。
 */
std::span<const FtraceEvent> Ftrace::History() const noexcept
{
    return EventHistory;
}
/**
 * @brief 获取当前ELF读取器。
 * @return const Readelf*，若已经加载ELF则返回读取器指针，否则返回nullptr。
 */
const Readelf *Ftrace::ElfReader() const noexcept
{
    return Elf ? &*Elf : nullptr;
}
/**
 * @brief 打印当前调用栈。
 * @details
 * 按从栈底到栈顶的顺序打印每一层函数调用。
 */
void Ftrace::PrintCurrentStack() const
{
    std::println("ftrace: call stack depth = {0}", CallStack.size());
    for (std::size_t Index{0}; Index < CallStack.size(); ++Index)
    {
        const auto &Frame{CallStack[Index]};
        std::println("  #{0} {1} @ 0x{2:08x} (call 0x{3:08x}, ret 0x{4:08x})",
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
    std::println("ftrace: history size = {0}", EventHistory.size());
    for (const auto &Event : EventHistory)
    {
        PrintEventLine(Event);
    }
}
/**
 * @brief 打印ftrace当前状态摘要。
 */
void Ftrace::PrintStatus() const
{
    const auto ElfName{Elf ? Elf->path().string() : std::string{"<none>"}};
    std::println("ftrace: enabled={0}, record_history={1}, depth={2}, history={3}, elf={4}, functions={5}",
                 Enabled,
                 ShouldRecordHistory,
                 CallStack.size(),
                 EventHistory.size(),
                 ElfName,
                 FunctionCount());
}
/**
 * @brief 根据地址解析函数名。
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
 * @brief 记录并打印一次ftrace事件。
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
 * @brief 按当前深度打印一条ftrace事件。
 * @param Event const FtraceEvent&，待打印的事件。
 */
void Ftrace::PrintEventLine(const FtraceEvent &Event) const
{
    const auto Indent{std::string(IndentFor(Event) * 2u, ' ')};
    if (Event.GetType() == FtraceEventType::Call)
    {
        std::println("0x{0:08x}: {1}call [{2}@0x{3:08x}]",
                     Event.GetCurrentPC(),
                     Indent,
                     PrintableName(Event.GetFunctionName()),
                     Event.GetTargetPC());
        return;
    }
    std::println("0x{0:08x}: {1}ret  [{2}]",
                 Event.GetCurrentPC(),
                 Indent,
                 PrintableName(Event.GetFunctionName()));
}
/**
 * @brief 初始化全局ftrace。
 * @param ElfFile const std::filesystem::path&，ELF文件路径。
 * @param ShouldEnable bool，初始化完成后是否开启ftrace。
 * @return std::expected<void, std::string>，成功返回空结果，失败返回错误字符串。
 */
std::expected<void, std::string> InitializeFtrace(const std::filesystem::path &ElfFile, bool ShouldEnable)
{
    // Without CONFIG_FTRACE the same --elf input is still useful to SDB's
    // generic readelf command, which legitimately accepts ET_REL/ET_DYN.
    // Only runtime function tracing requires absolute ET_EXEC symbol values.
    auto Result{GlobalFtrace.LoadElf(ElfFile, ShouldEnable)};
    if (!Result)
    {
        return Result;
    }
    GlobalFtrace.Enable(ShouldEnable);
    return {};
}
