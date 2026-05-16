#ifndef FTRACE_COMMAND_HPP
#define FTRACE_COMMAND_HPP
#include "command/SDBCommand.hpp"
class ftraceCommand final : public SDBCommand
{
public:
    ~ftraceCommand() override = default;
    [[nodiscard]] std::string_view Name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList Usage() const noexcept override;
    SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) override;
};
#endif
