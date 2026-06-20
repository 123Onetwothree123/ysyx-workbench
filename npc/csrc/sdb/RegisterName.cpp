module npc.sdb.RegisterName;
std::string_view StripRegisterPrefix(std::string_view name)
{
    if (!name.empty() && name.front() == '$')
    {
        name.remove_prefix(1);
    }
    return name;
}
bool IsProgramCounterName(std::string_view name)
{
    name = StripRegisterPrefix(name);
    return name == "pc";
}
std::optional<std::uint32_t> RegisterNameToIndex(std::string_view name)
{
    name = StripRegisterPrefix(name);
    if (name.empty() || name == "pc")
    {
        return std::nullopt;
    }
    if (name == "0" || name == "zero")
    {
        return 0;
    }
    if (name.size() >= 2 && name.front() == 'x')
    {
        auto Index{std::uint32_t{0}};
        for (std::size_t Pos{1}; Pos < name.size(); ++Pos)
        {
            if (name[Pos] < '0' || name[Pos] > '9')
            {
                return std::nullopt;
            }
            Index = Index * 10u + static_cast<std::uint32_t>(name[Pos] - '0');
        }
        if (Index < 32)
        {
            return Index;
        }
        return std::nullopt;
    }
    static constexpr std::array<std::string_view, 32> ABIName{
        "zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
        "s0",   "s1", "a0", "a1", "a2", "a3", "a4", "a5",
        "a6",   "a7", "s2", "s3", "s4", "s5", "s6", "s7",
        "s8",   "s9", "s10", "s11", "t3", "t4", "t5", "t6",
    };
    for (std::size_t Index{0}; Index < ABIName.size(); ++Index)
    {
        if (name == ABIName[Index])
        {
            return static_cast<std::uint32_t>(Index);
        }
    }
    if (name == "fp")
    {
        return 8;
    }
    return std::nullopt;
}
