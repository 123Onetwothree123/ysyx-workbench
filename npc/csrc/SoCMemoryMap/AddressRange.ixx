export module npc.SoCMemoryMap.AddressRange;
import std;

export class AddressRange
{
private:
    std::string_view Name;
    std::uint32_t Base;
    std::uint32_t Size;

public:
    AddressRange() = delete;
    ~AddressRange() = default;
    AddressRange(std::string_view name, std::uint32_t base, std::uint32_t size);
    std::string_view GetName() const;
    std::uint32_t GetBase() const;
    std::uint32_t GetSize() const;
    std::uint32_t GetEnd() const;
    std::uint32_t OffsetOf(std::uint32_t Address) const;
    bool Contains(std::uint32_t Address, std::uint32_t Length = 1) const;
};
