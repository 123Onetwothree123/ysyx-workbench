export module npc.sdb.SDBCommandUsage;
import std;

export class SDBCommandUsage
{
public:
    SDBCommandUsage(std::string_view arguments, std::string_view description) noexcept;
    [[nodiscard]] std::string_view GetArguments() const noexcept;
    [[nodiscard]] std::string_view GetDescription() const noexcept;

private:
    std::string_view Arguments;
    std::string_view Description;
};

export using SDBCommandUsageList = std::span<const SDBCommandUsage>;
