#ifndef INFO_COMMAND_HPP
#define INFO_COMMAND_HPP
#include "command/SDBCommand.hpp"
class infoCommand final : public SDBCommand
{
public:
    ~infoCommand() override=default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &Context, std::string_view Args) override;
};
#endif
