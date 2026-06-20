export module npc.sdb.command.qCommand;
import std;
import npc.sdb.SDBCommand;
import npc.sdb.SDBCommandResult;
import npc.sdb.SDBCommandUsage;
import npc.sdb.SDBCommandContext;

export class qCommand final : public SDBCommand
{
public:
    ~qCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &Context, std::string_view Args) override;
};
