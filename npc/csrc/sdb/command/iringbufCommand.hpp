#ifndef IRINGBUF_COMMAND_HPP
#define IRINGBUF_COMMAND_HPP

#include "command/SDBCommand.hpp"

class iringbufCommand final : public SDBCommand
{
public:
    ~iringbufCommand() override = default;
    [[nodiscard]] std::string_view Name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList Usage() const noexcept override;
    SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) override;
};

#endif
