export module npc.sdb.command.wCommand;
import std;
import npc.sdb.SDBCommand;
//import npc.sdb.command.WatchpointPool;
import npc.sdb.SDBCommandResult;
import npc.sdb.SDBCommandUsage;
import npc.sdb.SDBCommandContext;

export class wCommand final : public SDBCommand
{
public:
    ~wCommand() override = default;
    [[nodiscard]] std::string_view name() const noexcept override;
    [[nodiscard]] SDBCommandUsageList usage() const noexcept override;
    SDBCommandResult execute(SDBCommandContext &Context, std::string_view Args) override;
};
