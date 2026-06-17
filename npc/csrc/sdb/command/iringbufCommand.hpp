#ifndef IRINGBUF_COMMAND_HPP
#define IRINGBUF_COMMAND_HPP
#include "command/SDBCommand.hpp"
class iringbufCommand final : public SDBCommand
{
public:
    ~iringbufCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &context, std::string_view args) override;
};
#endif
