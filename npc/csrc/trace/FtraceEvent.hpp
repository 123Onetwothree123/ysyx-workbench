#ifndef FTRACE_EVENT_HPP
#define FTRACE_EVENT_HPP

#include <cstddef>
#include <cstdint>
#include <string_view>

enum class FtraceEventType : std::uint8_t
{
    Call,   // 函数调用事件
    Return, // 函数返回事件
};

/*
历史事件，记录程序运行的时候产生的一次call或ret事件快照
*/
class FtraceEvent final
{
public:
    FtraceEvent();                                                                                                                                                         // 构造一个空历史事件
    FtraceEvent(FtraceEventType InputType, std::uint64_t InputCurrentPC, std::uint64_t InputTargetPC, std::string_view InputFunctionName, std::size_t InputDepth) noexcept; // 构造一条指定类型、PC、函数名和深度的历史事件
    [[nodiscard]] FtraceEventType GetType() const noexcept;                                                                                                                 // 获取当前事件类型，比如说call或ret
    [[nodiscard]] std::uint64_t GetCurrentPC() const noexcept;                                                                                                              // 获取当前事件对应指令的PC
    [[nodiscard]] std::uint64_t GetTargetPC() const noexcept;                                                                                                               // 获取当前事件跳转到的目标PC
    [[nodiscard]] std::string_view GetFunctionName() const noexcept;                                                                                                        // 获取当前事件对应的函数名
    [[nodiscard]] std::size_t GetDepth() const noexcept;                                                                                                                    // 获取当前事件的调用深度
private:
    FtraceEventType Type{FtraceEventType::Call}; // 当前事件类型，比如说call或ret
    std::uint64_t CurrentPC{};                   // 当前事件对应指令的PC
    std::uint64_t TargetPC{};                    // 当前事件跳转到的目标PC
    std::string_view FunctionName{};             // 当前事件对应的函数名
    std::size_t Depth{};                         // 当前事件的调用深度
};

#endif
