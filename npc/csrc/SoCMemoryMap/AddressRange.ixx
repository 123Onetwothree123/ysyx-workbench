module;
export module npc.SoCMemoryMap.AddressRange;
import npc.config;
import std;

export class AddressRange
{
private:
    std::string_view Name;
    // 地址范围按 CPU XLEN 保存：RV32 为 32 位，RV64 为 64 位。
    npc::XWord Base;
    npc::XWord Size;

public:
    AddressRange() = delete;
    ~AddressRange() = default;
    AddressRange(std::string_view name, npc::XWord base, npc::XWord size);
    std::string_view GetName() const;
    npc::XWord GetBase() const;
    npc::XWord GetSize() const;
    npc::XWord GetEnd() const;
    npc::XWord OffsetOf(npc::XWord Address) const;
    bool Contains(npc::XWord Address, npc::XWord Length = 1) const;
};
