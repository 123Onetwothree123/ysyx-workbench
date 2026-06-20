export module npc.sdb.SDBCommand;
import std;
import npc.sdb.SDBCommandResult;
import npc.sdb.SDBCommandUsage;
import npc.sdb.SDBCommandContext;

export class SDBCommand
{
public:
    SDBCommand() = default;
    virtual ~SDBCommand() = default;
    [[nodiscard]] virtual std::string_view name() const noexcept = 0;
    [[nodiscard]] virtual SDBCommandUsageList usage() const noexcept = 0;
    virtual SDBCommandResult execute(SDBCommandContext &context, std::string_view args) = 0;
};
