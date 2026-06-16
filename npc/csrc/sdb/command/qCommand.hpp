#ifndef Q_COMMAND_HPP
#define Q_COMMAND_HPP
#include "command/SDBCommand.hpp"
class qCommand final : public SDBCommand
{
public:
    ~qCommand() override=default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &context, std::string_view args) override;
};
#endif
