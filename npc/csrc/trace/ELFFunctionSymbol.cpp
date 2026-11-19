module npc.trace.ELFFunctionSymbol;
ELFFunctionSymbol::ELFFunctionSymbol() = default;
ELFFunctionSymbol::~ELFFunctionSymbol() = default;
ELFFunctionSymbol::ELFFunctionSymbol(std::string_view InputName, std::size_t InputStart, std::size_t InputEnd)
{
    name = InputName;
    start = InputStart;
    end = InputEnd;
}
std::string_view ELFFunctionSymbol::GetName()
{
    return name;
}
std::size_t ELFFunctionSymbol::GetStart()
{
    return start;
}
std::size_t ELFFunctionSymbol::GetEnd()
{
    return end;
}
std::size_t ELFFunctionSymbol::GetSize()
{
    return end - start;
}
