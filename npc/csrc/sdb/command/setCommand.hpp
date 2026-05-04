#ifndef SET_COMMAND_HPP
#define SET_COMMAND_HPP
#include "SDBCommand.hpp"
class setCommand : public SDBCommand
{
private:
public:
    ~setCommand() override = default;
    [[nodiscard]] std::string_view Name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList Usage() const noexcept override;
    SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) override;
};
#endif
