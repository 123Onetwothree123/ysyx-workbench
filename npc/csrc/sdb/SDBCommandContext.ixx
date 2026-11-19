export module npc.sdb.SDBCommandContext;
import std;
import npc.DUT;
import npc.SimulationContext;

export class SDBCommandContext
{
private:
    DUT &dut;
    SimulationContext &simulation;

public:
    SDBCommandContext() = delete;
    ~SDBCommandContext() = default;
    [[nodiscard]] DUT &GetDUT() const noexcept;
    [[nodiscard]] SimulationContext &GetSimulation() const noexcept;
    SDBCommandContext(DUT &InputDUT);
};
