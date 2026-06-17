#ifndef FTRACE_COMMAND_HPP
#define FTRACE_COMMAND_HPP
#include "command/SDBCommand.hpp"
class ftraceCommand final : public SDBCommand
{
public:
    ~ftraceCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &context, std::string_view args) override;
};
#endif
