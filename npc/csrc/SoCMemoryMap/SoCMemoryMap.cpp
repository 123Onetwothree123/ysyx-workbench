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
bool SoCMemoryMap::IsShadowRam(npc::XWord address, npc::XWord length)
{
    // shadow 按 store 起始地址登记，不要求整段访问落在窗口内；保持与
    // 旧实现一致，仅拒绝零长度访问。
    return length != 0 &&
           (SRAM.Contains(address) || PSRAM.Contains(address) ||
            SDRAM.Contains(address) || address >= npc::PmaChipLinkMemBase);
}
bool SoCMemoryMap::IsReferenceModeledRam(npc::XWord address,
                                         npc::XWord length)
{
    // REF 只接收完整落在可建模窗口内的访问；ChipLinkMem 不在 REF 模型内。
    return length != 0 &&
           (SRAM.Contains(address, length) ||
            MROM.Contains(address, length) ||
            PSRAM.Contains(address, length) ||
            SDRAM.Contains(address, length));
}
