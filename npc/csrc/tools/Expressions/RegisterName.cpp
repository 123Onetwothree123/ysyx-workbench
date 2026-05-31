#include "RegisterName.hpp"
#include <array>
#include <cstddef>
std::string_view StripRegisterPrefix(std::string_view Name)
{
    if (!Name.empty() && Name.front() == '$')
    {
        Name.remove_prefix(1);
    }
    return Name;
}
bool IsProgramCounterName(std::string_view Name)
{
    Name = StripRegisterPrefix(Name);
    return Name == "pc";
}
std::optional<std::uint32_t> RegisterNameToIndex(std::string_view Name)
{
    Name = StripRegisterPrefix(Name);
    if (Name.empty() || Name == "pc")
    {
        return std::nullopt;
    }
    if (Name == "0" || Name == "zero")
    {
        return 0;
    }
    if (Name.size() >= 2 && Name.front() == 'x')
    {
        auto Index{std::uint32_t{0}};
        for (std::size_t Pos{1}; Pos < Name.size(); ++Pos)
        {
            if (Name[Pos] < '0' || Name[Pos] > '9')
            {
                return std::nullopt;
            }
            Index = Index * 10u + static_cast<std::uint32_t>(Name[Pos] - '0');
        }
        if (Index < 32)
        {
            return Index;
        }
        return std::nullopt;
    }
    static constexpr std::array<std::string_view, 32> AbiNames{
        "zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
        "s0",   "s1", "a0", "a1", "a2", "a3", "a4", "a5",
        "a6",   "a7", "s2", "s3", "s4", "s5", "s6", "s7",
        "s8",   "s9", "s10", "s11", "t3", "t4", "t5", "t6",
    };
    for (std::size_t Index{0}; Index < AbiNames.size(); ++Index)
    {
        if (Name == AbiNames[Index])
        {
            return static_cast<std::uint32_t>(Index);
        }
    }
    if (Name == "fp")
    {
        return 8;
    }
    return std::nullopt;
}
