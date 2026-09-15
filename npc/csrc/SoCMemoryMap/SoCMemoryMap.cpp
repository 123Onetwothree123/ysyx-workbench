module npc.SoCMemoryMap.SoCMemoryMap;
std::optional<AddressRange> SoCMemoryMap::find(std::uint32_t address, std::uint32_t length)
{
    const auto it{std::ranges::find_if(Ranges, [address, length](const AddressRange& range)
                                       { return range.Contains(address, length); })};
    if (it != Ranges.end())
    {
        return *it;
    }
    return std::nullopt;
}
