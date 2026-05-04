#ifndef SDB_COMMAND_HPP
#define SDB_COMMAND_HPP

#include <span>
#include <string_view>

#include "command/SDBCommandContext.hpp"

enum class SDBCommandResult
{
    Continue,
    Quit,
};

struct SDBCommandUsage
{
    std::string_view Arguments;
    std::string_view Description;
};

using SDBCommandUsageList = std::span<const SDBCommandUsage>;

class SDBCommand
{
public:
    virtual ~SDBCommand();
    [[nodiscard]] virtual std::string_view Name() const noexcept = 0;
    [[nodiscard]] virtual SDBCommandUsageList Usage() const noexcept = 0;
    virtual SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) = 0;
};

#endif
