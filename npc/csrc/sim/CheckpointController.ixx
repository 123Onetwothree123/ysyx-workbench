module;
export module npc.CheckpointController;
import std;
export namespace npc
{
// NPC_CHECKPOINT_* / NPC_FORK_* 环境变量触发检查点。
class CheckpointController
{
public:
    using SaveFn = std::function<std::expected<void, std::string>(
        const std::filesystem::path &)>;
    using ExitFn = std::function<void(int)>;
    void LoadEnvironment();
    void SetCallbacks(SaveFn save, ExitFn exit);
    void OnCycle(std::size_t cycle);
    void OnMarker();
    // NPC_FORK_AT 命中时 fork，父进程写 pidfile 后退出。
    void OnFork(std::size_t cycle);
    [[nodiscard]] bool HasMarker() const noexcept;
    [[nodiscard]] std::string_view Marker() const noexcept;
private:
    void SaveNow();
    std::optional<std::size_t> checkpoint_cycle_;
    std::filesystem::path checkpoint_file_;
    std::string checkpoint_marker_;
    bool checkpoint_continue_{false};
    std::optional<std::size_t> fork_cycle_;
    std::filesystem::path fork_pidfile_;
    std::size_t cycle_{0};
    SaveFn save_;
    ExitFn exit_;
};
}
