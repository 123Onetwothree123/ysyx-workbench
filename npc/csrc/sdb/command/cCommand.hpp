#ifndef C_COMMAND_HPP
#define C_COMMAND_HPP
#include "command/SDBCommand.hpp"
class cCommand final : public SDBCommand
{
public:
    ~cCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &Context, std::string_view Args) override;
};
#endif
