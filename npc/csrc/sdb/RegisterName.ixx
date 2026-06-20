export module npc.sdb.RegisterName;
import std;

export [[nodiscard]] std::string_view StripRegisterPrefix(std::string_view name);
export [[nodiscard]] bool IsProgramCounterName(std::string_view name);
export [[nodiscard]] std::optional<std::uint32_t> RegisterNameToIndex(std::string_view name);
