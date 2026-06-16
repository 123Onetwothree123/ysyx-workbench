#ifndef SI_COMMAND_HPP
#define SI_COMMAND_HPP
#include "command/SDBCommand.hpp"
class siCommand final : public SDBCommand
{
public:
    ~siCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &context, std::string_view args) override;
};
#endif
