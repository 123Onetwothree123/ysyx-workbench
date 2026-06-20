module npc.sdb.NPCEvaluationContext;
import npc.sdb.RegisterName;
import npc.tools.expressions.ExpressionError;
NPCEvaluationContext::NPCEvaluationContext(DUT &InputDUT) : dut{InputDUT}
{
}
std::uint32_t NPCEvaluationContext::ReadMemory(std::uint32_t address, std::size_t size) const
{
    auto result{dut.ReadMemory(address, size)};
    if (!result)
    {
        throw ExpressionError(std::format("读取内存失败 0x{:08x}：{}", address, result.error()));
    }
    return *result;
}
std::uint32_t NPCEvaluationContext::GetPC() const
{
    return dut.ReadPC().value();
}
std::uint32_t NPCEvaluationContext::ReadRegister(std::string_view name) const
{
    if (IsProgramCounterName(name))
    {
        auto result{dut.ReadPC()};
        if (!result)
        {
            throw ExpressionError(std::format("读寄存器的时候，先出现了读取PC失败：{}", result.error()));
        }
        return *result;
    }
    auto index{RegisterNameToIndex(name)};
    if (!index)
    {
        throw ExpressionError(std::format("{}是无效的寄存器名字", std::string(name)));
    }
    auto result{dut.ReadGPR(*index)};
    if (!result)
    {
        throw ExpressionError(std::format("读取GPR[{}]失败：{}", *index, result.error()));
    }
    return *result;
}
