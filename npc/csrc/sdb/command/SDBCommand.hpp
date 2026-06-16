#ifndef SDB_COMMAND_HPP
#define SDB_COMMAND_HPP
#include "../SDBCommandResult.hpp"
#include "SDBCommandUsage.hpp"
#include <string_view>
class SDBCommandContext;
class SDBCommand
{
public:
    SDBCommand() = default;
    virtual ~SDBCommand() = default;
    [[nodiscard]] virtual std::string_view name() const noexcept = 0;
    [[nodiscard]] virtual SDBCommandUsageList usage() const noexcept = 0;
    virtual SDBCommandResult execute(SDBCommandContext &context, std::string_view args) = 0;
};
#endif
