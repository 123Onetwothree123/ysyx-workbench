#include "NPCEvaluationContext.hpp"
#include "SDBDPI.hpp"
#include "SDBMemory.hpp"
#include "tools/Expressions/ExpressionError.hpp"
#include "tools/Expressions/RegisterName.hpp"
#include <cstddef>
#include <cstdint>
#include <format>
#include <string>
#include <string_view>
std::uint32_t NPCEvaluationContext::ReadRegister(std::string_view Name) const
{
    if (IsProgramCounterName(Name))
    {
        return CPP_NPCGetPC();
    }
    const auto RegIndex{RegisterNameToIndex(Name)};
    if (RegIndex)
    {
        if (*RegIndex == 0)
        {
            return 0;
        }
        return CPP_NPCGetGPR(static_cast<int>(*RegIndex));
    }
    throw ExpressionError(std::format("无效的寄存器名: {0}", std::string(Name)));
}
std::uint32_t NPCEvaluationContext::ReadMemory(std::uint32_t Address, std::size_t Size) const
{
    return NPCMemoryRead(Address, Size);
}
std::uint32_t NPCEvaluationContext::GetProgramCounter() const
{
    return CPP_NPCGetPC();
}
