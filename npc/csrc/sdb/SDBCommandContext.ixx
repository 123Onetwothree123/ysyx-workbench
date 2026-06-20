export module npc.sdb.SDBCommandContext;
import std;
import npc.DUT;

export class SDBCommandContext
{
private:
    DUT &dut;

public:
    SDBCommandContext() = delete;
    ~SDBCommandContext() = default;
    [[nodiscard]] DUT &GetDUT() const noexcept;
    SDBCommandContext(DUT &InputDUT);
};
