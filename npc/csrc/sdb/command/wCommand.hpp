#ifndef W_COMMAND_HPP
#define W_COMMAND_HPP
#include "SDBCommand.hpp"
#include "WatchpointPool.hpp"
class wCommand : public SDBCommand
{
public:
    ~wCommand() override = default;
    [[nodiscard]] std::string_view Name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList Usage() const noexcept override;
    SDBCommandResult Execute(SDBCommandContext &Context, std::string_view Args) override;
};
#endif
