#ifndef CLEAR_COMMAND_HPP
#define CLEAR_COMMAND_HPP
#include "command/SDBCommand.hpp"
class clearCommand final : public SDBCommand
{
public:
    ~clearCommand() override = default;
    [[nodiscard]] std::string_view Name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList Usage() const noexcept override;
    SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) override;
};
#endif
