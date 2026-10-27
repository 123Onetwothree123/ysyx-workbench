module;
module npc.SoCMemoryMap.AddressRange;
import npc.config;
std::string_view AddressRange::GetName() const
{
    return Name;
}
npc::XWord AddressRange::GetSize() const
{
    return Size;
}
npc::XWord AddressRange::GetBase() const
{
    return Base;
}
npc::XWord AddressRange::GetEnd() const
{
    return Base + Size;
}
npc::XWord AddressRange::OffsetOf(npc::XWord Address) const
{
    return Address - Base;
}
bool AddressRange::Contains(npc::XWord Address, npc::XWord Length) const
{
    return Address >= Base && Length <= Size && Address - Base <= Size - Length;
}
AddressRange::AddressRange(std::string_view name, npc::XWord base, npc::XWord size)
    : Name{name}, Base{base}, Size{size}
{
}
