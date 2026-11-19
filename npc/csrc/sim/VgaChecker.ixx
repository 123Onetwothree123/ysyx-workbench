module;
export module npc.VgaChecker;
import std;
import npc.Output;
export namespace npc
{
struct VgaSample
{
    bool hsync;
    bool vsync;
    bool valid;
    // 0xRRGGBB，对应三个 8 位 VGA 颜色引脚。
    std::uint32_t rgb;
    std::size_t cycle;
};
class VgaChecker
{
public:
    void Enable(bool Hsync, bool Vsync);
    [[nodiscard]] bool Enabled() const;
    // 50000000 周期看门狗触发时返回 true。
    bool Sample(const VgaSample &sample, Output &out);
    [[nodiscard]] bool Report(Output &out);
private:
    bool enabled_{false};
    bool prev_hsync_{true};
    bool prev_vsync_{true};
    std::size_t hsync_run_length_{0};
    std::size_t vsync_run_length_{0};
    std::size_t last_hsync_cycle_{0};
    std::size_t last_vsync_cycle_{0};
    std::size_t line_period_sum_{0};
    std::size_t line_period_count_{0};
    std::size_t line_period_bad_{0};
    std::size_t frame_period_bad_{0};
    std::size_t hsync_width_bad_{0};
    std::size_t vsync_width_bad_{0};
    std::size_t hsync_level_errors_{0};
    std::size_t vsync_level_errors_{0};
    std::size_t valid_timing_errors_{0};
    std::size_t frame_pixel_bad_{0};
    int x_{-1};
    int y_{-1};
    bool horizontal_aligned_{false};
    bool vertical_aligned_{false};
    std::size_t valid_pixels_{0};
    std::size_t last_frame_valid_pixels_{0};
    std::size_t last_frame_missing_pixels_{0};
    std::size_t last_frame_duplicate_pixels_{0};
    std::size_t missing_pixels_{0};
    std::size_t duplicate_pixels_{0};
    std::size_t pos_errors_{0};
    std::size_t frames_{0};
    bool completed_frame_available_{false};
    bool timed_out_{false};
    std::vector<std::uint8_t> working_frame_;
    std::vector<std::uint8_t> frame_;
    std::vector<std::uint8_t> pixel_coverage_;
};
}
