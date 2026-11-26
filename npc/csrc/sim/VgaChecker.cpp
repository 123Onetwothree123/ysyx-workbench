module npc.VgaChecker;
import std;
import npc.Output;
namespace
{
constexpr int HSync{96};
constexpr int HBackPorch{48};
constexpr int HVisible{640};
constexpr int HFrontPorch{16};
constexpr int HTotal{HSync + HBackPorch + HVisible + HFrontPorch};
constexpr int VSync{2};
constexpr int VBackPorch{33};
constexpr int VVisible{480};
constexpr int VFrontPorch{10};
constexpr int VTotal{VSync + VBackPorch + VVisible + VFrontPorch};
constexpr std::size_t VisiblePixels{
    static_cast<std::size_t>(HVisible) * VVisible};
constexpr std::size_t BytesPerPixel{3};
}
namespace npc
{
void VgaChecker::Enable(bool Hsync, bool Vsync)
{
    enabled_ = true;
    // reset 后 X=1/Y=1，当前电平作为首个采样周期。
    prev_hsync_ = Hsync;
    prev_vsync_ = Vsync;
    hsync_run_length_ = 1;
    vsync_run_length_ = 1;
    last_hsync_cycle_ = 0;
    last_vsync_cycle_ = 0;
    line_period_sum_ = 0;
    line_period_count_ = 0;
    line_period_bad_ = 0;
    frame_period_bad_ = 0;
    hsync_width_bad_ = 0;
    vsync_width_bad_ = 0;
    hsync_level_errors_ = 0;
    vsync_level_errors_ = 0;
    valid_timing_errors_ = 0;
    frame_pixel_bad_ = 0;
    x_ = -1;
    y_ = -1;
    horizontal_aligned_ = false;
    vertical_aligned_ = false;
    valid_pixels_ = 0;
    last_frame_valid_pixels_ = 0;
    last_frame_missing_pixels_ = 0;
    last_frame_duplicate_pixels_ = 0;
    missing_pixels_ = 0;
    duplicate_pixels_ = 0;
    pos_errors_ = 0;
    frames_ = 0;
    completed_frame_available_ = false;
    timed_out_ = false;
    working_frame_.assign(VisiblePixels * BytesPerPixel, 0);
    frame_.assign(VisiblePixels * BytesPerPixel, 0);
    pixel_coverage_.assign(VisiblePixels, 0);
}
bool VgaChecker::Enabled() const
{
    return enabled_;
}
bool VgaChecker::Sample(const VgaSample &sample, Output &out)
{
    if (!enabled_) return false;
    const std::size_t cycle{sample.cycle};
    bool hs{sample.hsync};
    const bool vs{sample.vsync};
    bool vv{sample.valid};
#ifdef NPC_VGA_CHECK_TEST_INJECT_CYCLE
    // 测试专用：仅此周期反转 hs/vv，生产构建不定义。
    static_assert(NPC_VGA_CHECK_TEST_INJECT_CYCLE > 0);
    if (cycle == static_cast<std::size_t>(NPC_VGA_CHECK_TEST_INJECT_CYCLE))
    {
        hs = !hs;
        vv = !vv;
    }
#endif
    const bool hsync_rise{hs && !prev_hsync_};
    const bool vsync_rise{vs && !prev_vsync_};
    // 同步信号低有效，逐跳变检查宽度以发现脉宽错误。
    if (hs == prev_hsync_)
    {
        ++hsync_run_length_;
    }
    else
    {
        const std::size_t expected_width{
            prev_hsync_ ? static_cast<std::size_t>(HTotal - HSync)
                        : static_cast<std::size_t>(HSync)};
        if (hsync_run_length_ != expected_width)
            ++hsync_width_bad_;
        hsync_run_length_ = 1;
    }
    if (vs == prev_vsync_)
    {
        ++vsync_run_length_;
    }
    else
    {
        const std::size_t expected_width{
            prev_vsync_
                ? static_cast<std::size_t>(VTotal - VSync) * HTotal
                : static_cast<std::size_t>(VSync) * HTotal};
        if (vsync_run_length_ != expected_width)
            ++vsync_width_bad_;
        vsync_run_length_ = 1;
    }
    // 首个 HSYNC 上升沿锚定 X=97，之后光栅位置自由跑。
    bool line_start{false};
    if (!horizontal_aligned_)
    {
        if (hsync_rise)
        {
            horizontal_aligned_ = true;
            x_ = HSync + 1;
        }
    }
    else
    {
        x_ = x_ == HTotal ? 1 : x_ + 1;
        line_start = x_ == 1;
    }
    if (hsync_rise)
    {
        const auto period{cycle - last_hsync_cycle_};
        if (line_period_count_ > 0)
        {
            line_period_sum_ += period;
            if (period != HTotal) ++line_period_bad_;
        }
        ++line_period_count_;
        last_hsync_cycle_ = cycle;
    }
    if (vertical_aligned_ && line_start)
    {
        y_ = y_ == VTotal ? 1 : y_ + 1;
    }
    if (vsync_rise)
    {
        const auto period{cycle - last_vsync_cycle_};
        if (vertical_aligned_)
        {
            if (period != static_cast<std::size_t>(HTotal) * VTotal)
                ++frame_period_bad_;
            std::size_t missing{0};
            std::size_t duplicate{0};
            for (const auto coverage : pixel_coverage_)
            {
                if (coverage == 0)
                    ++missing;
                else if (coverage > 1)
                    duplicate += static_cast<std::size_t>(coverage - 1);
            }
            ++frames_;
            last_frame_valid_pixels_ = valid_pixels_;
            last_frame_missing_pixels_ = missing;
            last_frame_duplicate_pixels_ = duplicate;
            missing_pixels_ += missing;
            duplicate_pixels_ += duplicate;
            if (valid_pixels_ != VisiblePixels || missing != 0 ||
                duplicate != 0)
                ++frame_pixel_bad_;
            // 完整帧边界才导出，工作缓冲不污染快照。
            frame_ = working_frame_;
            completed_frame_available_ = true;
        }
        last_vsync_cycle_ = cycle;
        vertical_aligned_ = true;
        y_ = VSync + 1;
        valid_pixels_ = 0;
        std::ranges::fill(working_frame_, std::uint8_t{0});
        std::ranges::fill(pixel_coverage_, std::uint8_t{0});
        if (horizontal_aligned_ && x_ != 1)
            ++pos_errors_;
    }
    if (horizontal_aligned_)
    {
        const bool expected_hsync{x_ > HSync};
        if (hs != expected_hsync) ++hsync_level_errors_;
    }
    if (vertical_aligned_)
    {
        const bool expected_vsync{y_ > VSync};
        if (vs != expected_vsync) ++vsync_level_errors_;
    }
    const bool raster_aligned{horizontal_aligned_ && vertical_aligned_};
    const bool expected_valid{raster_aligned &&
        x_ > HSync + HBackPorch &&
        x_ <= HSync + HBackPorch + HVisible &&
        y_ > VSync + VBackPorch &&
        y_ <= VSync + VBackPorch + VVisible};
    if (raster_aligned && vv != expected_valid)
        ++valid_timing_errors_;
    if (vv)
    {
        ++valid_pixels_;
        const int px{x_ - (HSync + HBackPorch + 1)};
        const int py{y_ - (VSync + VBackPorch + 1)};
        if (!raster_aligned || px < 0 || px >= HVisible || py < 0 ||
            py >= VVisible)
        {
            ++pos_errors_;
        }
        else
        {
            const auto pixel_index{static_cast<std::size_t>(py) * HVisible +
                static_cast<std::size_t>(px)};
            auto &coverage{pixel_coverage_[pixel_index]};
            if (coverage != std::numeric_limits<std::uint8_t>::max())
                ++coverage;
            const auto byte_index{pixel_index * BytesPerPixel};
            const std::uint32_t rgb{sample.rgb};
            working_frame_[byte_index + 0] =
                static_cast<std::uint8_t>(rgb >> 16);
            working_frame_[byte_index + 1] =
                static_cast<std::uint8_t>(rgb >> 8);
            working_frame_[byte_index + 2] =
                static_cast<std::uint8_t>(rgb);
        }
    }
    prev_hsync_ = hs;
    prev_vsync_ = vs;
    if (!timed_out_ && cycle >= 50000000)
    {
        timed_out_ = true;
        out.Error(
            "VGA时序检查超过50000000周期，强制终止");
        return true;
    }
    return false;
}
bool VgaChecker::Report(Output &out)
{
    if (!enabled_) return true;
    out.Info("VGA监视器结果:");
    out.Info("  完整帧数: {}", frames_);
    if (line_period_count_ > 1)
    {
        out.Info("  行周期(期望{}拍): 平均 {:.1f}, 异常行数 {}", HTotal,
            static_cast<double>(line_period_sum_) / static_cast<double>(line_period_count_ - 1), line_period_bad_);
    }
    out.Info("  帧周期异常次数(期望{}拍): {}", HTotal * VTotal, frame_period_bad_);
    out.Info("  HSYNC脉宽异常(期望低{}/高{}拍): {}", HSync, HTotal - HSync, hsync_width_bad_);
    out.Info("  VSYNC脉宽异常(期望低{}/高{}拍): {}", VSync * HTotal, (VTotal - VSync) * HTotal, vsync_width_bad_);
    out.Info("  HSYNC光栅电平错误拍数: {}", hsync_level_errors_);
    out.Info("  VSYNC光栅电平错误拍数: {}", vsync_level_errors_);
    out.Info("  有效区/porch时序错误拍数: {}", valid_timing_errors_);
    out.Info("  最近一帧有效像素(期望{}): {}", VisiblePixels, last_frame_valid_pixels_);
    out.Info("  最近一帧缺失/重复像素: {}/{}",
        last_frame_missing_pixels_, last_frame_duplicate_pixels_);
    out.Info("  累计缺失/重复像素: {}/{}",
        missing_pixels_, duplicate_pixels_);
    out.Info("  有效像素异常帧数: {}", frame_pixel_bad_);
    out.Info("  像素位置错误: {}", pos_errors_);
    bool ppm_ok{completed_frame_available_};
    if (!completed_frame_available_)
    {
        out.Error("  VGA帧导出失败: 尚未采集到完整帧");
    }
    else
    {
        std::ofstream f{"vga_frame.ppm", std::ios::binary | std::ios::trunc};
        if (!f.is_open())
        {
            out.Error("  VGA帧导出失败: 无法打开 vga_frame.ppm");
            ppm_ok = false;
        }
        else
        {
            f << std::format("P6\n{} {}\n255\n", HVisible, VVisible);
            f.write(reinterpret_cast<const char *>(frame_.data()),
                    static_cast<std::streamsize>(frame_.size()));
            if (!f)
            {
                out.Error("  VGA帧导出失败: 写入 vga_frame.ppm 失败");
                ppm_ok = false;
            }
            f.close();
            if (f.fail())
            {
                out.Error("  VGA帧导出失败: 关闭 vga_frame.ppm 失败");
                ppm_ok = false;
            }
        }
    }
    if (ppm_ok)
        out.Info("  已完成帧的稳定快照已导出: vga_frame.ppm");
    const bool ok{frames_ > 0 && line_period_bad_ == 0 && frame_period_bad_ == 0 &&
        hsync_width_bad_ == 0 && vsync_width_bad_ == 0 &&
        hsync_level_errors_ == 0 && vsync_level_errors_ == 0 &&
        valid_timing_errors_ == 0 &&
        frame_pixel_bad_ == 0 && last_frame_valid_pixels_ == VisiblePixels &&
        last_frame_missing_pixels_ == 0 &&
        last_frame_duplicate_pixels_ == 0 && pos_errors_ == 0 &&
        !timed_out_ && ppm_ok};
    out.Info("  VGA时序检查: {}", ok ? "PASS" : "FAIL");
    return ok;
}
}
