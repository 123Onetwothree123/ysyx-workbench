module;
#ifdef VRISCV32E_NPC
#include "Vriscv32e_npc_SimTop.h"
#define TOP_MODULE Vriscv32e_npc_SimTop
#else
#include "VysyxSoCFull.h"
#define TOP_MODULE VysyxSoCFull
#endif
#include "Config.h"
export module npc.DUT;
import std;
import npc.PerfStats;

export class DUT
{
private:
    std::unique_ptr<TOP_MODULE> dut;
    std::size_t cycle{0};

    bool vga_check{false};
    bool vga_prev_hsync{true};
    bool vga_prev_vsync{true};
    std::size_t vga_hsync_run_length{0};
    std::size_t vga_vsync_run_length{0};
    std::size_t vga_last_hsync_cycle{0};
    std::size_t vga_last_vsync_cycle{0};
    std::size_t vga_line_period_sum{0};
    std::size_t vga_line_period_count{0};
    std::size_t vga_line_period_bad{0};
    std::size_t vga_frame_period_bad{0};
    std::size_t vga_hsync_width_bad{0};
    std::size_t vga_vsync_width_bad{0};
    std::size_t vga_hsync_level_errors{0};
    std::size_t vga_vsync_level_errors{0};
    std::size_t vga_valid_timing_errors{0};
    std::size_t vga_frame_pixel_bad{0};
    int vga_x{-1};
    int vga_y{-1};
    bool vga_horizontal_aligned{false};
    bool vga_vertical_aligned{false};
    std::size_t vga_valid_pixels{0};
    std::size_t vga_last_frame_valid_pixels{0};
    std::size_t vga_last_frame_missing_pixels{0};
    std::size_t vga_last_frame_duplicate_pixels{0};
    std::size_t vga_missing_pixels{0};
    std::size_t vga_duplicate_pixels{0};
    std::size_t vga_pos_errors{0};
    std::size_t vga_frames{0};
    bool vga_completed_frame_available{false};
    bool vga_timed_out{false};
    std::vector<std::uint8_t> vga_working_frame;
    std::vector<std::uint8_t> vga_frame;
    std::vector<std::uint8_t> vga_pixel_coverage;
    std::size_t instructions{0};
    PerfStats perf{};
    std::size_t fault_count{0}; // AXI fault 打印限流计数
    bool finalized{false};
    // Seeded once when a REF is enabled, then updated by reading the physical
    // destination after each real write.  DiffTest can snapshot all 32 GPRs
    // without 32 debug-address changes/evals per retirement.
    // RV64 下必须保留完整 64 位，不能沿用只够 RV32 的 32 位镜像。
    std::array<npc::XWord, 32> architectural_gpr{};
    npc::XWord architectural_pc{0};
#ifdef CONFIG_SDB
    // Verilator 顶层没有稳定的 RAM 读端口。只记录已精确退休的
    // CPU store，与初始镜像合并成可证明正确的调试内存视图。
    // RV64 地址使用完整 64 位键。
    std::unordered_map<std::uint64_t, std::uint8_t> debug_memory_writes;
#endif

public:
    DUT();
    ~DUT() = default;
    // 运算符重载，少写点代码
    TOP_MODULE &operator*();
    TOP_MODULE *operator->();
    void eval();
    void final();
    void EnableVGACheck();
    [[nodiscard]] bool VGACheckReport();
    void step();
    // 在每次时钟沿后立即调用：若本拍 trap_valid 有效，记录 custom halt。
    // 调用者必须在检查 Verilated::gotFinish() 之前调用，保证同一拍的
    // $finish 不会把已可见的 GOOD/BAD trap 吞成“未触发陷阱”。
    [[nodiscard]] bool PollSimulationHalt();
    void reset();
    // 把 ImageLoader 产生的唯一镜像写入 direct-NPC RAM。
    // SoC 路径的 MROM/flash 本来就直接读取这个 vector，因此为 no-op。
    void InitializeMemory(std::span<const std::uint8_t> image);
    std::size_t GetCycle() const;
    std::size_t GetInstructions() const;
    [[nodiscard]] const PerfStats &GetPerfStats() const noexcept;
    // 给 SDB 的读口统一返回 XWord：RV32 是 32 位，RV64 是完整 64 位。
    [[nodiscard]] std::expected<npc::XWord, std::string> ReadGPR(std::uint32_t index);
    [[nodiscard]] std::expected<npc::XWord, std::string> ReadPC();
    [[nodiscard]] std::expected<npc::XWord, std::string>
        ReadCachedGPR(std::uint32_t index) const;
    [[nodiscard]] npc::XWord ReadCachedPC() const noexcept;
    // 仿真 custom halt 与 trap trace 的完整 XLEN 地址视图。
    [[nodiscard]] npc::XWord ReadHaltPC() const;
    [[nodiscard]] npc::XWord ReadTrapPC() const;
    [[nodiscard]] npc::XWord ReadTrapTarget() const;
    // mcause 是异常/中断编码，RTL 调试端口固定 32 位，保留真实宽度。
    [[nodiscard]] std::uint32_t ReadTrapCause() const;
    // One-time physical RF rescan used at reset and at the final DiffTest
    // boundary.  Per-retirement snapshots continue to use the write mirror.
    void RefreshCachedArchitecturalState();
    [[nodiscard]] std::expected<std::uint64_t, std::string> ReadMemory(std::uint64_t addr, std::size_t size);
};
