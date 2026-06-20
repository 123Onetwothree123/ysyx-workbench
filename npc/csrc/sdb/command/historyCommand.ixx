export module npc.sdb.command.historyCommand;
import std;
import npc.sdb.SDBCommand;
import npc.sdb.SDBCommandResult;
import npc.sdb.SDBCommandUsage;
import npc.sdb.SDBCommandContext;
import npc.sdb.SDBCommandRegistry;

export class historyCommand final : public SDBCommand
{
public:
    explicit historyCommand(const SDBCommandRegistry &InputRegistry);
    ~historyCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &context, std::string_view args) override;
private:
    static void PrintGNUHistory(std::size_t n, const SDBCommandRegistry &Registry);
    const SDBCommandRegistry &Registry;
};
