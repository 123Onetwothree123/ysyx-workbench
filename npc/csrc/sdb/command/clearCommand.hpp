#ifndef CLEAR_COMMAND_HPP
#define CLEAR_COMMAND_HPP
#include "command/SDBCommand.hpp"
class clearCommand final : public SDBCommand
{
public:
    ~clearCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &context, std::string_view args) override;
};
#endif
