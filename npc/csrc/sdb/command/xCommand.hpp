#ifndef X_COMMAND_HPP
#define X_COMMAND_HPP

#include "command/SDBCommand.hpp"

class xCommand final : public SDBCommand
{
public:
    ~xCommand() override = default;

    [[nodiscard]] std::string_view Name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList Usage() const noexcept override;
    SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) override;
};

#endif
