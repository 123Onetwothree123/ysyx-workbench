module npc.sdb.command.xCommand;
import npc.sdb.SDBCommandUtils;
import npc.expressions.expressions;
import npc.sdb.NPCEvaluationContext;
import npc.DUT;

std::string_view xCommand::name() const noexcept
{
    return "x";
}
SDBCommandUsageList xCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"N EXPR", "从表达式地址开始看N个4字节数据，默认就是这个"},
        {"Nb EXPR", "从表达式地址开始看N个1字节数据"},
        {"Nh EXPR", "从表达式地址开始看N个2字节数据"},
        {"Nw EXPR", "从表达式地址开始看N个4字节数据"},
    };
    return entries;
}
SDBCommandResult xCommand::execute(SDBCommandContext &context, std::string_view args)
{
    args = SDBTrimLeft(args);
    if (args.empty())
    {
        std::println("用法：x N[b|h|w] EXPR");
        return SDBCommandResult::Continue;
    }
    auto Count{std::size_t{0}};
    const auto CountResult{std::from_chars(args.data(), args.data() + args.size(), Count, 10)};
    if (CountResult.ec != std::errc() || Count == 0)
    {
        std::println("错误：缺少有效的扫描数量 N");
        std::println("用法：x N[b|h|w] EXPR");
        return SDBCommandResult::Continue;
    }
    args.remove_prefix(static_cast<std::size_t>(CountResult.ptr - args.data()));
    args = SDBTrimLeft(args);
    auto UnitSize{std::size_t{4}};
    if (!args.empty())
    {
        switch (args.front())
        {
        case 'b':
        {
            UnitSize = 1;
            args.remove_prefix(1);
            break;
        }
        case 'h':
        {
            UnitSize = 2;
            args.remove_prefix(1);
            break;
        }
        case 'w':
        {
            UnitSize = 4;
            args.remove_prefix(1);
            break;
        }
        default:
        {
            break;
        }
        }
    }
    args = SDBTrimLeft(args);
    if (args.empty())
    {
        std::println("错误：缺少地址表达式");
        std::println("用法：x N[b|h|w] EXPR");
        return SDBCommandResult::Continue;
    }
    expressions ExpressionsEngine;
    NPCEvaluationContext EvalContext{context.GetDUT()};
    const auto AddressResult{ExpressionsEngine.evaluate(args, EvalContext)};
    if (!AddressResult)
    {
        std::println("表达式错误：{}", AddressResult.error());
        return SDBCommandResult::Continue;
    }
    const auto StartAddress{AddressResult.value()};
    if (Count > std::numeric_limits<std::uint32_t>::max() / UnitSize)
    {
        std::println("错误：请求范围过大");
        return SDBCommandResult::Continue;
    }
    const auto TotalBytes{static_cast<std::uint32_t>(Count * UnitSize)};
    if (StartAddress > std::numeric_limits<std::uint32_t>::max() - (TotalBytes - 1))
    {
        std::println("错误：地址范围溢出");
        return SDBCommandResult::Continue;
    }
    std::println("正在扫描 {} 个项目（每个 {} 字节），从 0x{:08x} 到 0x{:08x}：",
                 Count, UnitSize, StartAddress, StartAddress + TotalBytes - 1);
    for (std::size_t Index{0}; Index < Count; ++Index)
    {
        const auto CurrentAddress{StartAddress + static_cast<std::uint32_t>(Index * UnitSize)};
        auto ValueResult{context.GetDUT().ReadMemory(CurrentAddress, UnitSize)};
        if (!ValueResult)
        {
            std::println(std::cerr, "错误：在 0x{:08x} 处读取内存失败，扫描停止", CurrentAddress);
            break;
        }
        const auto Value{*ValueResult};
        switch (UnitSize)
        {
        case 1:
        {
            std::println("0x{:08x}: 0x{:02x}", CurrentAddress, Value & 0xffu);
            break;
        }
        case 2:
        {
            std::println("0x{:08x}: 0x{:04x}", CurrentAddress, Value & 0xffffu);
            break;
        }
        case 4:
        {
            std::println("0x{:08x}: 0x{:08x}", CurrentAddress, Value);
            break;
        }
        default:
        {
            break;
        }
        }
    }
    return SDBCommandResult::Continue;
}
