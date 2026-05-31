#ifndef Q_COMMAND_HPP
#define Q_COMMAND_HPP
#include "command/SDBCommand.hpp"
class qCommand final : public SDBCommand
{
public:
    ~qCommand() override=default;
    [[nodiscard]] std::string_view Name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList Usage() const noexcept override;
    SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) override;
};
#endif
