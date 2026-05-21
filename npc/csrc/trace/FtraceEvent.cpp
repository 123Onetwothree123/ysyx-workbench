#include "FtraceEvent.hpp"
/**
 * @file FtraceEvent.cpp
 * @brief ftrace历史事件的实现。
 * @details
 * 这个文件实现`FtraceEvent`，用来保存程序运行时产生的一次
 * call或ret事件快照。
 */
FtraceEvent::FtraceEvent() = default;
/**
 * @brief 构造一条指定信息的历史事件。
 * @param InputType FtraceEventType，当前事件类型，比如说call或ret。
 * @param InputCurrentPC std::uint64_t，当前事件对应指令的PC。
 * @param InputTargetPC std::uint64_t，当前事件跳转到的目标PC。
 * @param InputFunctionName std::string_view，当前事件对应的函数名。
 * @param InputDepth std::size_t，当前事件的调用深度。
 */
FtraceEvent::FtraceEvent(FtraceEventType InputType, std::uint64_t InputCurrentPC, std::uint64_t InputTargetPC, std::string_view InputFunctionName, std::size_t InputDepth) noexcept
{
    Type = InputType;
    CurrentPC = InputCurrentPC;
    TargetPC = InputTargetPC;
    FunctionName = InputFunctionName;
    Depth = InputDepth;
}
/**
 * @brief 获取当前事件类型。
 * @return FtraceEventType，当前事件类型，比如说call或ret。
 */
FtraceEventType FtraceEvent::GetType() const noexcept
{
    return Type;
}
/**
 * @brief 获取当前事件对应指令的PC。
 * @return std::uint64_t，当前事件对应指令的PC。
 */
std::uint64_t FtraceEvent::GetCurrentPC() const noexcept
{
    return CurrentPC;
}
/**
 * @brief 获取当前事件跳转到的目标PC。
 * @return std::uint64_t，当前事件跳转到的目标PC。
 */
std::uint64_t FtraceEvent::GetTargetPC() const noexcept
{
    return TargetPC;
}
/**
 * @brief 获取当前事件对应的函数名。
 * @return std::string_view，当前事件对应的函数名。
 */
std::string_view FtraceEvent::GetFunctionName() const noexcept
{
    return FunctionName;
}
/**
 * @brief 获取当前事件的调用深度。
 * @return std::size_t，当前事件的调用深度。
 */
std::size_t FtraceEvent::GetDepth() const noexcept
{
    return Depth;
}
