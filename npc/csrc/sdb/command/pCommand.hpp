#ifndef PCOMMAND_HPP
#define PCOMMAND_HPP
#include "command/SDBCommand.hpp"
class pCommand : public SDBCommand
{
public:
    ~pCommand() override = default;
    [[nodiscard]] std::string_view Name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList Usage() const noexcept override;
    SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) override;
};
#endif
