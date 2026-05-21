#ifndef INFO_COMMAND_HPP
#define INFO_COMMAND_HPP
#include "command/SDBCommand.hpp"
class infoCommand final : public SDBCommand
{
public:
    ~infoCommand() override=default;
    [[nodiscard]] std::string_view Name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList Usage() const noexcept override;
    SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) override;
};
#endif
