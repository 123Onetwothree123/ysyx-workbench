#ifndef SI_COMMAND_HPP
#define SI_COMMAND_HPP
#include "command/SDBCommand.hpp"
class siCommand final : public SDBCommand
{
public:
    ~siCommand() override = default;
    [[nodiscard]] std::string_view Name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList Usage() const noexcept override;
    SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) override;
};
#endif
