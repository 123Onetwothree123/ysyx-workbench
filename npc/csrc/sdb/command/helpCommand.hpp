#ifndef HELP_COMMAND_HPP
#define HELP_COMMAND_HPP
#include "SDBCommand.hpp"
class SDBCommandRegistry;
class helpCommand final : public SDBCommand
{
public:
    helpCommand(const SDBCommandRegistry &InputRegistry);
    ~helpCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &context, std::string_view args) override;
private:
    const SDBCommandRegistry &registry;
};
#endif
