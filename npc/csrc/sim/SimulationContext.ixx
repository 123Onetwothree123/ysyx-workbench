export module npc.SimulationContext;
import std;
import npc.config;
import npc.Output;
import npc.trace.ftrace;
import npc.sdb.command.WatchpointPool;

export struct TrapState
{
    bool halted{false};
    npc::XWord pc{0};
    std::uint32_t code{0};

    void Halt(npc::XWord PC, std::uint32_t Code) noexcept;
};

export class SimulationContext final
{
public:
    SimulationContext();
    ~SimulationContext();
    SimulationContext(const SimulationContext &) = delete;
    SimulationContext &operator=(const SimulationContext &) = delete;
    static void SetActive(SimulationContext &Context);
    static void ClearActive() noexcept;
    // DPI 回调无调用者参数，取当前上下文；未设置时明确报错。
    [[nodiscard]] static SimulationContext &Active();
    [[nodiscard]] Output &Out() noexcept;
    [[nodiscard]] std::vector<std::uint8_t> &Flash() noexcept;
    [[nodiscard]] std::vector<std::uint8_t> &Mrom() noexcept;
    [[nodiscard]] WatchpointPool &Watchpoints() noexcept;
    [[nodiscard]] Ftrace &Trace() noexcept;
    [[nodiscard]] TrapState &Trap() noexcept;
private:
    Output output;
    std::vector<std::uint8_t> flash;
    std::vector<std::uint8_t> mrom;
    WatchpointPool watchpoints;
    Ftrace ftrace;
    TrapState trap;
    static SimulationContext *active;
};
