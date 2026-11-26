export module npc.DUT;
export import ysyx_26030103_TargetTop;
import npc.config;
import std;
import npc.DicArchCounterStats;
import npc.VgaChecker;
import npc.UartController;
import npc.CheckpointController;
import npc.DebugStateMirror;
import npc.PerfCounterAccumulator;
import npc.SimulationContext;

export class DUT
{
private:
    struct Tracer;
    SimulationContext &context;
    // 先于 dut 析构，避免模型仍引用已销毁的 tracer。
    std::unique_ptr<Tracer> tracer;
    std::unique_ptr<TargetTop> dut;
    std::size_t cycle{0};

    npc::VgaChecker vga;
    std::size_t instructions{0};
    npc::PerfCounterAccumulator perf;
    std::size_t fault_count{0}; // AXI fault 打印限流计数
    bool finalized{false};
    std::function<void()> shutdown_hook;
    bool heartbeat{false};
    npc::CheckpointController checkpoint;
    npc::UartController uart;
    // 每次真实写 GPR 后回读物理目标，RV64 保留完整 64 位。
    npc::DebugStateMirror mirror;
    // 仅记录精确退休的 store；RV64 用完整 64 位地址键。
    std::unordered_map<std::uint64_t, std::uint8_t> debug_memory_writes;

    void Tick();
    [[nodiscard]] npc::XWord ReadPhysicalGPR(std::uint32_t index);
    // 旧RTL无浮点调试口时返回0，调用方先查HasFPDebugPorts
    [[nodiscard]] std::uint64_t ReadPhysicalFPR(std::uint32_t index);

public:
    struct RetireCSRState
    {
        npc::XWord mstatus{};
        npc::XWord mtvec{};
        npc::XWord mepc{};
        npc::XWord mcause{};
    };
    struct TrapCSRState
    {
        npc::XWord mstatus{};
        npc::XWord mtvec{};
        npc::XWord mepc{};
        npc::XWord mcause{};
    };
    explicit DUT(SimulationContext &Context);
    ~DUT();
    [[nodiscard]] SimulationContext &Context() noexcept;
    TargetTop &operator*();
    TargetTop *operator->();
    void eval();
    void final();
    // 退出前统一清理钩子；未设置时退化为 final()。
    void SetShutdownHook(std::function<void()> hook);
    void EnableVGACheck();
    [[nodiscard]] bool VGACheckReport();
    void step();
    // 非阻塞转发 stdin 到 direct-NPC UART；full-SoC 为空操作。
    void EnableUARTStdin(bool enable);
    // 沿后调用；须在检查 Verilated::gotFinish() 前结算。
    [[nodiscard]] bool PollSimulationHalt();
    // 推进一拍并按优先级结算 halt；返回 true 表示调用方应退出运行循环。
    [[nodiscard]] bool StepWithHaltPriority();
    void reset();
    // 把镜像写入 direct-NPC RAM；SoC 路径为 no-op。
    void InitializeMemory(std::span<const std::uint8_t> image);
    // 仅保存 RTL 状态；未启用 --savable 时返回错误。
    std::expected<void, std::string>
        SaveCheckpoint(const std::filesystem::path &path);
    std::expected<void, std::string>
        RestoreCheckpoint(const std::filesystem::path &path);
    std::size_t GetCycle() const;
    std::size_t GetInstructions() const;
    [[nodiscard]] const DicArchCounterStats &GetDicArchCounterStats() const noexcept;
    // 读口统一返回 XWord（RV32 32 位，RV64 64 位）。
    [[nodiscard]] std::expected<npc::XWord, std::string> ReadGPR(std::uint32_t index);
    [[nodiscard]] std::expected<npc::XWord, std::string> ReadPC();
    [[nodiscard]] std::expected<npc::XWord, std::string>
        ReadCachedGPR(std::uint32_t index) const;
    [[nodiscard]] npc::XWord ReadCachedPC() const noexcept;
    // FLEN=32 时高 32 位为 0；旧 RTL 无端口时返回不可用。
    [[nodiscard]] bool HasFPDebugState() const;
    [[nodiscard]] std::expected<std::uint64_t, std::string>
        ReadCachedFPR(std::uint32_t index) const;
    [[nodiscard]] std::uint64_t ReadCachedFCSR() const noexcept;
    // 退休点与 mtrace 调试端口；RV64 由低/高 32 位字拼成 XWord。
    [[nodiscard]] npc::XWord ReadRetirePC() const;
    [[nodiscard]] npc::XWord ReadNextPC() const;
    [[nodiscard]] npc::XWord ReadMtracePC() const;
    [[nodiscard]] npc::XWord ReadMtraceAddress() const;
    // 完整 XLEN 的 halt/trap 地址视图。
    [[nodiscard]] npc::XWord ReadHaltPC() const;
    [[nodiscard]] npc::XWord ReadTrapPC() const;
    [[nodiscard]] npc::XWord ReadTrapTarget() const;
    // 退休点与 trap 点 CSR 调试视图，字段名与 RISC-V CSR 一致。
    [[nodiscard]] RetireCSRState ReadRetireCSR() const;
    [[nodiscard]] TrapCSRState ReadTrapCSR() const;
    // 无 tval 调试端口时返回 false，DiffTest 走 cause-only。
    [[nodiscard]] bool HasTrapTvalDebugState() const noexcept;
    [[nodiscard]] npc::XWord ReadTrapTval() const;
    // mcause 在低 32 位，高半区另行读取。
    [[nodiscard]] std::uint32_t ReadTrapCause() const;
    // 复位与最终边界的一次性物理 RF 扫描。
    void RefreshCachedArchitecturalState();
    [[nodiscard]] std::expected<std::uint64_t, std::string> ReadMemory(std::uint64_t addr, std::size_t size);
};
