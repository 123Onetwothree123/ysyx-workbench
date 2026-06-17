#ifndef HISTORY_COMMAND_HPP
#define HISTORY_COMMAND_HPP
#include "command/SDBCommand.hpp"
class SDBCommandRegistry;
class historyCommand final : public SDBCommand
{
public:
    explicit historyCommand(const SDBCommandRegistry &InputRegistry);
    ~historyCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &context, std::string_view args) override;
private:
    static void PrintGNUHistory(std::size_t n, const SDBCommandRegistry &Registry);
    const SDBCommandRegistry &Registry;
};
#endif
