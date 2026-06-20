module npc.SoCMemoryMap.AddressRange;
std::string_view AddressRange::GetName() const
{
    return Name;
}
std::uint32_t AddressRange::GetSize() const
{
    return Size;
}
std::uint32_t AddressRange::GetBase() const
{
    return Base;
}
std::uint32_t AddressRange::GetEnd() const
{
    return Base + Size;
}
std::uint32_t AddressRange::OffsetOf(std::uint32_t Address) const
{
    return Address - Base;
}
bool AddressRange::Contains(std::uint32_t Address, std::uint32_t Length) const
{
    return Address >= Base && Length <= Size && Address - Base <= Size - Length;
}
AddressRange::AddressRange(std::string_view name, std::uint32_t base, std::uint32_t size)
    : Name{name}, Base{base}, Size{size}
{
}
