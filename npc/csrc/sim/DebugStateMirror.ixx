module;
export module npc.DebugStateMirror;
import std;
import npc.config;
export namespace npc
{
// GPR/FPR/PC/fcsr 的软件退休镜像。
class DebugStateMirror
{
public:
    void SetGPR(std::uint32_t index, XWord value);
    void SetPC(XWord pc);
    void SetFPR(std::uint32_t index, std::uint64_t value);
    void SetFCSR(std::uint64_t value);
    [[nodiscard]] std::expected<XWord, std::string>
        ReadCachedGPR(std::uint32_t index) const;
    [[nodiscard]] XWord ReadCachedPC() const noexcept;
    [[nodiscard]] std::expected<std::uint64_t, std::string>
        ReadCachedFPR(std::uint32_t index) const;
    [[nodiscard]] std::uint64_t ReadCachedFCSR() const noexcept;
    // 复位后/最终边界的一次性物理寄存器扫描。
    void RefreshGPR(const std::function<XWord(std::uint32_t)> &read);
    void RefreshFPR(const std::function<std::uint64_t(std::uint32_t)> &read);
private:
    std::array<XWord, 32> gpr_{};
    XWord pc_{0};
    std::array<std::uint64_t, 32> fpr_{};
    std::uint64_t fcsr_{0};
};
}
