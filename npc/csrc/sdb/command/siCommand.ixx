export module npc.sdb.command.siCommand;
import std;
import npc.sdb.SDBCommand;
import npc.sdb.SDBCommandResult;
import npc.sdb.SDBCommandUsage;
import npc.sdb.SDBCommandContext;

export class siCommand final : public SDBCommand
{
public:
    ~siCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &context, std::string_view args) override;
};
