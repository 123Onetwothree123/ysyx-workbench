module npc.sdb.SDBCommandContext;
DUT &SDBCommandContext::GetDUT() const noexcept
{
    return dut;
}
SimulationContext &SDBCommandContext::GetSimulation() const noexcept
{
    return simulation;
}
SDBCommandContext::SDBCommandContext(DUT &InputDUT)
    : dut{InputDUT}, simulation{InputDUT.Context()}
{
}
