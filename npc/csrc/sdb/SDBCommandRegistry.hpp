#ifndef SDB_COMMAND_REGISTRY_HPP
#define SDB_COMMAND_REGISTRY_HPP
#include "command/SDBCommand.hpp"
#include "SDBCommandContext.hpp"
#include <memory>
#include <string>
#include <string_view>
#include <vector>
class SDBCommandRegistry
{
public:
    SDBCommandRegistry(DUT &dut);
    ~SDBCommandRegistry();
    SDBCommandResult Execute(std::string_view line);
    void RegisterCommand(std::unique_ptr<SDBCommand> command);
    [[nodiscard]] const SDBCommand *FindCommand(std::string_view name) const;
    void PrintHelp() const;
    void PrintHelp(const SDBCommand &command) const;
    void AddHistory(std::string_view line);
    [[nodiscard]] const std::vector<std::string> &GetHistory() const noexcept;

private:
    void RegisterBuiltins();
    SDBCommandContext Context;
    std::vector<std::unique_ptr<SDBCommand>> Commands{};
    std::vector<std::string> History{};
};
#endif