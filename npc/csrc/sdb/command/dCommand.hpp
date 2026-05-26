#ifndef D_COMMAND_HPP
#define D_COMMAND_HPP
#include <string_view>
#include "SDBCommand.hpp"
class dCommand : public SDBCommand
{
public:
    ~dCommand() override = default;
    [[nodiscard]] std::string_view Name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList Usage() const noexcept override;
    SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) override;
};
#endif
