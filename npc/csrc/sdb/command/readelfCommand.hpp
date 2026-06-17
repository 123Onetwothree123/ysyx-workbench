#ifndef READELF_COMMAND_HPP
#define READELF_COMMAND_HPP
#include "command/SDBCommand.hpp"
class readelfCommand final : public SDBCommand
{
public:
    ~readelfCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &context, std::string_view args) override;
};
#endif
