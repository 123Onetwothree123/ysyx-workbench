module;
#ifdef VRISCV32E_NPC
#include <cerrno>
#include <chrono>
#include <csignal>
#include <fstream>
#include <unistd.h>
#endif
module npc.CheckpointController;
import std;
namespace npc
{
namespace
{
std::optional<std::size_t> EnvCycle(const char *name)
{
    if (const char *value{std::getenv(name)}; value != nullptr && *value != '\0')
    {
        return static_cast<std::size_t>(std::stoull(value));
    }
    return std::nullopt;
}
std::filesystem::path EnvPath(const char *name)
{
    if (const char *value{std::getenv(name)}; value != nullptr && *value != '\0')
    {
        return value;
    }
    return {};
}
std::string EnvString(const char *name)
{
    if (const char *value{std::getenv(name)}; value != nullptr && *value != '\0')
    {
        return value;
    }
    return {};
}
}
void CheckpointController::LoadEnvironment()
{
    checkpoint_cycle_ = EnvCycle("NPC_CHECKPOINT_CYCLE");
    checkpoint_file_ = EnvPath("NPC_CHECKPOINT_FILE");
    checkpoint_marker_ = EnvString("NPC_CHECKPOINT_MARKER");
    if (const char *value{std::getenv("NPC_CHECKPOINT_CONTINUE")};
        value != nullptr)
    {
        checkpoint_continue_ = std::string{value} == "1";
    }
#ifdef VRISCV32E_NPC
    fork_cycle_ = EnvCycle("NPC_FORK_AT");
    fork_pidfile_ = EnvPath("NPC_FORK_PIDFILE");
#endif
}
void CheckpointController::SetCallbacks(SaveFn save, ExitFn exit)
{
    save_ = std::move(save);
    exit_ = std::move(exit);
}
void CheckpointController::OnCycle(std::size_t cycle)
{
    cycle_ = cycle;
    if (checkpoint_cycle_ && cycle == *checkpoint_cycle_ &&
        !checkpoint_file_.empty())
    {
        checkpoint_cycle_.reset();
        SaveNow();
    }
}
void CheckpointController::OnMarker()
{
    std::println(std::cerr, "UART marker 命中: {}", checkpoint_marker_);
    checkpoint_marker_.clear();
    SaveNow();
}
void CheckpointController::SaveNow()
{
    if (checkpoint_file_.empty())
    {
        std::println(std::cerr, "检查点触发但 NPC_CHECKPOINT_FILE 为空");
        exit_(1);
        return;
    }
    const auto begin{std::chrono::steady_clock::now()};
    auto result{save_(checkpoint_file_)};
    const auto end{std::chrono::steady_clock::now()};
    if (!result)
    {
        std::println(std::cerr, "检查点保存失败：{}", result.error());
    }
    else
    {
        std::error_code error;
        const auto bytes{std::filesystem::file_size(checkpoint_file_, error)};
        std::println(std::cerr,
                     "检查点已保存: {} cycle={} bytes={} elapsed_ms={}",
                     checkpoint_file_.string(), cycle_, error ? 0 : bytes,
                     std::chrono::duration_cast<std::chrono::milliseconds>(
                         end - begin)
                         .count());
    }
    if (!checkpoint_continue_)
    {
        exit_(result ? 0 : 1);
    }
}
void CheckpointController::OnFork(std::size_t cycle)
{
#ifdef VRISCV32E_NPC
    cycle_ = cycle;
    if (!fork_cycle_ || cycle != *fork_cycle_)
    {
        return;
    }
    const auto begin{std::chrono::steady_clock::now()};
    const pid_t child{::fork()};
    if (child < 0)
    {
        std::println(std::cerr, "fork 检查点失败: errno={}", errno);
    }
    else if (child == 0)
    {
        // 子进程 SIGSTOP 等待调试器，父进程退出后成为唯一实例。
        ::raise(SIGSTOP);
        fork_cycle_.reset();
        return;
    }
    else
    {
        if (!fork_pidfile_.empty())
        {
            std::ofstream out{fork_pidfile_};
            out << child << "\n";
            out.flush();
        }
        const auto end{std::chrono::steady_clock::now()};
        std::println(std::cerr,
                     "fork 检查点: pid={} cycle={} fork_us={}",
                     child, cycle_,
                     std::chrono::duration_cast<std::chrono::microseconds>(
                         end - begin)
                         .count());
        exit_(0);
        return;
    }
    fork_cycle_.reset();
#else
    static_cast<void>(cycle);
#endif
}
bool CheckpointController::HasMarker() const noexcept
{
    return !checkpoint_marker_.empty();
}
std::string_view CheckpointController::Marker() const noexcept
{
    return checkpoint_marker_;
}
}
