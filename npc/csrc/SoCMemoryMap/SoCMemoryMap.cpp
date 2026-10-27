module;
module npc.SoCMemoryMap.SoCMemoryMap;
import npc.config;
std::optional<AddressRange> SoCMemoryMap::find(npc::XWord address, npc::XWord length)
{
    // 有效 SoC 只有 32 位时，高于 4GiB 的查询不可能命中任何外设，
    // 必须返回空而不是先截断再误匹配低地址区域。
    if constexpr (npc::SocXLen == 32)
    {
        constexpr auto MaxAddress{std::numeric_limits<npc::SocXWord>::max()};
        if (address > MaxAddress || length > MaxAddress)
        {
            return std::nullopt;
        }
        if (length != 0 && address > MaxAddress - (length - 1))
        {
            return std::nullopt;
        }
    }
    const auto it{std::ranges::find_if(Ranges, [address, length](const AddressRange& range)
                                       { return range.Contains(address, length); })};
    if (it != Ranges.end())
    {
        return *it;
    }
    return std::nullopt;
}
