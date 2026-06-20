export module npc.sdb.command.helpCommand;
import std;
import npc.sdb.SDBCommand;
import npc.sdb.SDBCommandResult;
import npc.sdb.SDBCommandUsage;
import npc.sdb.SDBCommandContext;
import npc.sdb.SDBCommandRegistry;

export class helpCommand final : public SDBCommand
{
public:
    helpCommand(const SDBCommandRegistry &InputRegistry);
    ~helpCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &context, std::string_view args) override;
private:
    const SDBCommandRegistry &registry;
};
