#ifndef HELP_COMMAND_HPP
#define HELP_COMMAND_HPP
#include "command/SDBCommand.hpp"
class SDBCommandRegistry;
class helpCommand final : public SDBCommand
{
public:
    explicit helpCommand(const SDBCommandRegistry &Registry);
    ~helpCommand() override = default;
    [[nodiscard]] std::string_view Name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList Usage() const noexcept override;
    SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) override;
private:
    const SDBCommandRegistry &Registry;
};
#endif
