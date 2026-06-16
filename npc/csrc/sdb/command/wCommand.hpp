#ifndef W_COMMAND_HPP
#define W_COMMAND_HPP
#include "SDBCommand.hpp"
//#include "WatchpointPool.hpp"
class wCommand : public SDBCommand
{
public:
    ~wCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &context, std::string_view args) override;
};
#endif
