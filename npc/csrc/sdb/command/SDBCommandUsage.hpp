#ifndef SDB_COMMAND_USAGE_HPP
#define SDB_COMMAND_USAGE_HPP
#include <span>
#include <string_view>
class SDBCommandUsage
{
public:
    SDBCommandUsage(std::string_view arguments, std::string_view description) noexcept;
    [[nodiscard]] std::string_view GetArguments() const noexcept;
    [[nodiscard]] std::string_view GetDescription() const noexcept;

private:
    std::string_view Arguments;
    std::string_view Description;
};
using SDBCommandUsageList = std::span<const SDBCommandUsage>;
#endif
