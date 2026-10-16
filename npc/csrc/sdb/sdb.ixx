export module npc.sdb.sdb;
import std;
import npc.DUT;

// MainLoop must tell its caller why it returned.  A bool could not distinguish
// an explicit `q` from Verilator's $finish/EOF paths, which let a `q` queued
// after $finish turn an unfinished simulation into a successful exit.
export enum class SDBExitReason
{
    UserQuit,
    EndOfInput,
    BatchComplete,
    VerilatorFinish,
};

export class SDB final
{
public:
    SDB() = delete;
    [[nodiscard]] static SDBExitReason MainLoop(DUT &dut, bool batch_mode = false);
};
