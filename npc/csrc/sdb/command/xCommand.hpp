#ifndef X_COMMAND_HPP
#define X_COMMAND_HPP
#include "command/SDBCommand.hpp"
class xCommand final : public SDBCommand
{
public:
    ~xCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &Context, std::string_view Args) override;
};
#endif
