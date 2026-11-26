export module npc.sdb.SDBCommandRegistry;
import std;
import npc.sdb.SDBCommand;
import npc.sdb.SDBCommandContext;
import npc.sdb.SDBCommandResult;
import npc.DUT;

export class SDBCommandRegistry
{
public:
    SDBCommandRegistry(DUT &dut);
    ~SDBCommandRegistry();
    SDBCommandResult Execute(std::string_view line);                           // 解析并执行一行用户输入
    void RegisterCommand(std::unique_ptr<SDBCommand> command);                 // 注册单个命令
    [[nodiscard]] const SDBCommand *FindCommand(std::string_view name) const;  // 按名字查找命令
    void PrintHelp() const;                                                    // 打印全部命令帮助
    void PrintHelp(const SDBCommand &command) const;                           // 打印单个命令帮助

private:
    void RegisterBuiltins(); // 注册所有内置命令
    // 供 Execute 使用：返回可执行命令指针，找不到时为 nullptr。
    [[nodiscard]] SDBCommand *FindCommandForExecution(std::string_view name) const;
    SDBCommandContext Context;                           // 命令上下文，持有 DUT 引用
    std::vector<std::unique_ptr<SDBCommand>> Commands{}; // 已注册的命令列表
};
