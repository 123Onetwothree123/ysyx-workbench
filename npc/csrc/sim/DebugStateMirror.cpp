module;
module npc.DebugStateMirror;
import std;
import npc.config;
namespace npc
{
void DebugStateMirror::SetGPR(std::uint32_t index, XWord value)
{
    if (index > 0 && index < gpr_.size())
    {
        gpr_[index] = value;
    }
    gpr_[0] = 0;
}
void DebugStateMirror::SetPC(XWord pc)
{
    pc_ = pc;
}
void DebugStateMirror::SetFPR(std::uint32_t index, std::uint64_t value)
{
    if (index < fpr_.size())
    {
        fpr_[index] = value;
    }
}
void DebugStateMirror::SetFCSR(std::uint64_t value)
{
    // fcsr 仅 fflags[4:0]+frm[7:5] 共 8 位。
    fcsr_ = value & 0xffU;
}
std::expected<XWord, std::string>
DebugStateMirror::ReadCachedGPR(std::uint32_t index) const
{
    if (index >= gpr_.size())
    {
        return std::unexpected{std::format("GPR编号都超31号了: {0}", index)};
    }
    return gpr_[index];
}
XWord DebugStateMirror::ReadCachedPC() const noexcept
{
    return pc_;
}
std::expected<std::uint64_t, std::string>
DebugStateMirror::ReadCachedFPR(std::uint32_t index) const
{
    if (index >= fpr_.size())
    {
        return std::unexpected{std::format("FPR 编号超出 31: {0}", index)};
    }
    return fpr_[index];
}
std::uint64_t DebugStateMirror::ReadCachedFCSR() const noexcept
{
    return fcsr_;
}
void DebugStateMirror::RefreshGPR(
    const std::function<XWord(std::uint32_t)> &read)
{
    for (std::uint32_t index{0}; index < gpr_.size(); ++index)
    {
        gpr_[index] = read(index);
    }
    gpr_[0] = 0;
}
void DebugStateMirror::RefreshFPR(
    const std::function<std::uint64_t(std::uint32_t)> &read)
{
    for (std::uint32_t index{0}; index < fpr_.size(); ++index)
    {
        fpr_[index] = read(index);
    }
}
}
