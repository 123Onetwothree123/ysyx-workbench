export module npc.sdb.sdb;
import std;
import npc.DUT;

export class SDB final
{
public:
    SDB() = delete;
    // true only for an explicit interactive `q`; simulation completion,
    // batch mode, and stdin EOF are not silently treated as success.
    [[nodiscard]] static bool MainLoop(DUT &dut, bool batch_mode = false);
};
