#ifndef SDB_COMMAND_REGISTRY_HPP
#define SDB_COMMAND_REGISTRY_HPP
#include "command/SDBCommand.hpp"
#include <memory>
#include <string>
#include <string_view>
#include <vector>

class VRV32E32Reg;

class SDBCommandRegistry final
{
public:
    SDBCommandRegistry(VRV32E32Reg &Top, std::size_t &Cycles); // 这里直接传引用，命令执行的时候就可以直接操作这个对象
    ~SDBCommandRegistry();
    SDBCommandResult Execute(std::string_view Line);                          // 执行一行命令
    void RegisterCommand(std::unique_ptr<SDBCommand> Command);                // 注册一个命令
    [[nodiscard]] const SDBCommand *FindCommand(std::string_view Name) const; // 查找一个命令
    void PrintHelp() const;
    void PrintHelp(const SDBCommand &Command) const;
    // 命令历史管理
    void AddHistory(std::string_view Line);                                    // 添加一条命令到历史记录
    [[nodiscard]] const std::vector<std::string> &GetHistory() const noexcept; // 获取命令历史记录

private:
    void RegisterBuiltins(); // 注册内置命令
    SDBCommandContext Context;
    std::vector<std::unique_ptr<SDBCommand>> Commands{}; // 已注册的命令列表
    std::vector<std::string> History{};
};

#endif
