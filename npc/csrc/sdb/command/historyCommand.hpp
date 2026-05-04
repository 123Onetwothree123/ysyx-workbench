#ifndef HISTORY_COMMAND_HPP
#define HISTORY_COMMAND_HPP

#include "command/SDBCommand.hpp"

class SDBCommandRegistry;

class historyCommand final : public SDBCommand
{
public:
    explicit historyCommand(const SDBCommandRegistry &Registry);
    ~historyCommand() override = default;
    [[nodiscard]] std::string_view Name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList Usage() const noexcept override;
    SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) override;

private:
    const SDBCommandRegistry &Registry;
    static void PrintGNUHistory(std::size_t n, const SDBCommandRegistry &Registry);
};

#endif
