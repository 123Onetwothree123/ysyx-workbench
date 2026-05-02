#ifndef READELF_COMMAND_HPP
#define READELF_COMMAND_HPP

#include "command/SDBCommand.hpp"

class readelfCommand final : public SDBCommand
{
public:
    ~readelfCommand() override = default;
    [[nodiscard]] std::string_view Name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList Usage() const noexcept override;
    SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) override;
};

#endif
