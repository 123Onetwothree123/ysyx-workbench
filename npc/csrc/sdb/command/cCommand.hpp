#ifndef C_COMMAND_HPP
#define C_COMMAND_HPP
#include "command/SDBCommand.hpp"
class cCommand final : public SDBCommand
{
public:
    ~cCommand() override = default;
    [[nodiscard]] std::string_view Name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList Usage() const noexcept override;
    SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) override;
};
#endif
