#ifndef PCOMMAND_HPP
#define PCOMMAND_HPP
#include "command/SDBCommand.hpp"
class pCommand : public SDBCommand
{
public:
    ~pCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &context, std::string_view args) override;
};
#endif
