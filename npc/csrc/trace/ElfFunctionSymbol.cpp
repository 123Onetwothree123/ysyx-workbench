module npc.trace.ElfFunctionSymbol;
ElfFunctionSymbol::ElfFunctionSymbol() = default;
ElfFunctionSymbol::~ElfFunctionSymbol() = default;
ElfFunctionSymbol::ElfFunctionSymbol(std::string_view InputName, std::size_t InputStart, std::size_t InputEnd)
{
    name = InputName;
    start = InputStart;
    end = InputEnd;
}
std::string_view ElfFunctionSymbol::GetName()
{
    return name;
}
std::size_t ElfFunctionSymbol::GetStart()
{
    return start;
}
std::size_t ElfFunctionSymbol::GetEnd()
{
    return end;
}
std::size_t ElfFunctionSymbol::GetSize()
{
    return end - start;
}
