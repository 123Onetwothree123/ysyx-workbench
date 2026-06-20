module npc.SoCMemoryMap.SoCMemoryMap;
std::optional<AddressRange> SoCMemoryMap::find(std::uint32_t address,std::uint32_t length){
for (const auto &range : Ranges)
    {
        if (range.Contains(address, length))
        {
            return range;
        }
    }
    return std::nullopt;
}
