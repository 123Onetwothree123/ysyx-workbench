#ifndef FTRACE_HPP
#define FTRACE_HPP

#include "FtraceEvent.hpp"
#include "FtraceFrame.hpp"
#include "readelf.hpp"

#include <cstddef>
#include <cstdint>
#include <expected>
#include <filesystem>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

/*
模块全局状态，负责维护ELF符号、当前调用栈和历史事件
*/
class Ftrace final
{
public:
    Ftrace();                                   // 构造一个空的 ftrace 状态
    Ftrace(const Ftrace &) = delete;            // 禁止复制，避免内部视图和状态被意外复制
    Ftrace &operator=(const Ftrace &) = delete; // 禁止复制赋值
    Ftrace(Ftrace &&) = delete;                 // 禁止移动，保持全局状态地址稳定
    Ftrace &operator=(Ftrace &&) = delete;      // 禁止移动赋值
    ~Ftrace();                                  // 释放 ftrace 内部资源
    // 这是对外接口，模块生命周期部分
    [[nodiscard]] std::expected<void, std::string> LoadElf(std::filesystem::path ElfFile); // 加载ELF文件并准备函数符号表
    void ClearElf();                                                                       // 清空ELF符号和已有 ftrace 状态
    void Reset();                                                                          // 清空调用栈和历史事件，但保留已经加载的ELF符号
    // 这是对外接口，开关控制部分
    void Enable(bool ShouldEnable = true) noexcept;    // 开启或关闭 ftrace，后续 call/ret 会按开关处理
    void Disable() noexcept;                           // 关闭 ftrace，后续 call/ret 直接忽略
    [[nodiscard]] bool IsEnabled() const noexcept;     // 返回当前 ftrace 是否开启
    void SetRecordHistory(bool ShouldRecord) noexcept; // 设置是否记录完整历史事件
    [[nodiscard]] bool RecordHistory() const noexcept; // 返回当前是否记录完整历史事件
    // 这是对外接口，指令事件入口的部分
    void OnInstruction(std::uint64_t PC, std::uint32_t Instruction, std::uint64_t NextPC); // 分析一条指令，识别 call 或 ret 事件
    void OnCall(std::uint64_t CallPC, std::uint64_t FunctionAddress);                      // 处理一次函数调用事件并更新栈/历史
    void OnReturn(std::uint64_t CurrentPC, std::uint64_t TargetPC);                        // 处理一次函数返回事件并更新栈/历史
    // 这是对外接口，状态查询的部分
    [[nodiscard]] std::size_t Depth() const noexcept;                    // 获取当前调用深度（栈大小）
    [[nodiscard]] std::size_t HistorySize() const noexcept;              // 获取当前已记录的历史事件数量
    [[nodiscard]] std::size_t FunctionCount() const noexcept;            // 获取ELF中已加载的函数符号数量
    [[nodiscard]] const FtraceFrame *TopFrame() const noexcept;          // 获取当前栈顶帧，若无则返回 nullptr
    [[nodiscard]] std::span<const FtraceEvent> History() const noexcept; // 获取已记录的历史事件序列
    [[nodiscard]] const Readelf *ElfReader() const noexcept;             // 获取当前ELF读取器，若无则返回 nullptr
    // 这是对外的接口，打印输出的部分
    void PrintCurrentStack() const; // 打印当前调用栈（从底到顶）
    void PrintHistory() const;      // 打印已记录的历史 call和ret 事件
    void PrintStatus() const;       // 打印 ftrace 当前开关、深度与历史配置摘要
private:
    // 这是内部辅助接口，符号解析和输出的部分
    [[nodiscard]] std::string_view ResolveFunctionName(std::uint64_t Address) const noexcept; // 根据地址解析函数名，若找不到则返回空视图
    void PushEvent(FtraceEvent Event);                                                        // 记录并打印一次 call 或 ret 历史事件
    void PrintEventLine(const FtraceEvent &Event) const;                                      // 按当前深度打印一条 call 或 ret 事件
    std::optional<Readelf> Elf{};                                                             // 当前已经加载的ELF读取器
    bool Enabled{false};                                                                      // 看这个 ftrace 是否启用
    bool ShouldRecordHistory{true};                                                           // 是否记录完整历史事件
    std::vector<FtraceFrame> CallStack{};                                                     // 当前函数调用栈
    std::vector<FtraceEvent> EventHistory{};                                                  // 历史 call 和 ret 事件序列
};
extern Ftrace GlobalFtrace;                                                                                               // 全局 ftrace 状态
[[nodiscard]] std::expected<void, std::string> InitializeFtrace(const std::filesystem::path &ElfFile, bool ShouldEnable); // 初始化全局 ftrace 并按参数设置开关
extern "C" void ftrace_record(std::uint64_t PC, std::uint32_t Instruction, std::uint64_t NextPC);                         // Verilog DPI-C入口，记录一条已提交指令
#endif
