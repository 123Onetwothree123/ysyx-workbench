module;
#ifdef VRISCV32E_NPC
#include "Vriscv32e_npc_SimTop.h"
#define TOP_MODULE Vriscv32e_npc_SimTop
#else
#include "VysyxSoCFull.h"
#define TOP_MODULE VysyxSoCFull
#endif
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
    std::size_t vga_last_hsync_cycle{0};
    std::size_t vga_last_vsync_cycle{0};
    std::size_t vga_line_period_sum{0};
    std::size_t vga_line_period_count{0};
    std::size_t vga_line_period_bad{0};
    std::size_t vga_frame_period_bad{0};
    int vga_x{-1};
    int vga_y{0};
    std::size_t vga_valid_pixels{0};
    std::size_t vga_last_frame_valid_pixels{0};
    std::size_t vga_pos_errors{0};
    std::size_t vga_frames{0};
    std::vector<std::uint8_t> vga_frame;
    std::size_t instructions{0};
    PerfStats perf{};

public:
    DUT();
    ~DUT() = default;
    // 运算符重载，少写点代码
    TOP_MODULE &operator*();
    TOP_MODULE *operator->();
    void eval();
    void final();
    void EnableVGACheck();
    void VGACheckReport();
    void step();
    void reset();
    std::size_t GetCycle() const;
    std::size_t GetInstructions() const;
    [[nodiscard]] const PerfStats &GetPerfStats() const noexcept;
    // 给sdb的
    [[nodiscard]] std::expected<std::uint32_t, std::string> ReadGPR(std::uint32_t index);
    [[nodiscard]] std::expected<std::uint32_t, std::string> ReadPC();
    [[nodiscard]] std::expected<std::uint32_t, std::string> ReadMemory(std::uint32_t addr, std::size_t size);
};
