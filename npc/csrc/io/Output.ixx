export module npc.Output;
import std;
export enum class OutputLevel
{
    Error = 0,
    Warn = 1,
    Info = 2,
    Debug = 3,
};
// Error/Warn 走 stderr，其余 stdout；仅终端且无 NO_COLOR 时着色。
export class Output final
{
public:
    Output();
    ~Output();
    Output(const Output &) = delete;
    Output &operator=(const Output &) = delete;
    void SetMinLevel(OutputLevel Level) noexcept;
    [[nodiscard]] std::expected<void, std::string>
        OpenFile(const std::filesystem::path &Path);
    void CloseFile();
    void Write(OutputLevel Level, std::string_view Message);
    void WriteRaw(OutputLevel Level, std::string_view Text);
    template<typename... Args>
    void Error(std::format_string<Args...> Format, Args &&...args)
    {
        Emit(OutputLevel::Error, Format, std::forward<Args>(args)...);
    }
    template<typename... Args>
    void Warn(std::format_string<Args...> Format, Args &&...args)
    {
        Emit(OutputLevel::Warn, Format, std::forward<Args>(args)...);
    }
    template<typename... Args>
    void Info(std::format_string<Args...> Format, Args &&...args)
    {
        Emit(OutputLevel::Info, Format, std::forward<Args>(args)...);
    }
    template<typename... Args>
    void Debug(std::format_string<Args...> Format, Args &&...args)
    {
        Emit(OutputLevel::Debug, Format, std::forward<Args>(args)...);
    }
private:
    template<typename... Args>
    void Emit(OutputLevel Level, std::format_string<Args...> Format, Args &&...args)
    {
        if (Level <= min_level)
        {
            Write(Level, std::format(Format, std::forward<Args>(args)...));
        }
    }
    [[nodiscard]] std::ostream &StreamFor(OutputLevel Level) noexcept;
    [[nodiscard]] bool UseColor(OutputLevel Level) const noexcept;
    [[nodiscard]] std::string_view ColorFor(OutputLevel Level) const noexcept;
    // 换行须在 ANSI 复位码之前写出，故由参数控制而不是调用方补。
    void WriteImpl(OutputLevel level, std::string_view text, bool AppendNewline);
    std::ofstream file;
    OutputLevel min_level{OutputLevel::Debug};
    bool stdout_color{false};
    bool stderr_color{false};
};
