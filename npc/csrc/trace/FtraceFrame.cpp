#include "FtraceFrame.hpp"

/**
 * @file FtraceFrame.cpp
 * @brief ftrace 调用栈帧的实现。
 *
 * @details
 * 这个文件实现 `FtraceFrame`，用来保存一次函数调用的调用PC、
 * 返回PC、函数入口地址和函数名。
 */

FtraceFrame::FtraceFrame() = default;
/**
 * @brief 构造一条指定信息的栈帧。
 *
 * @param InputCallPC std::uint64_t，调用指令所在的PC。
 * @param InputReturnPC std::uint64_t，本次调用预期返回到的PC。
 * @param InputFunctionAddress std::uint64_t，被调用函数的入口地址。
 * @param InputFunctionName std::string_view，被调用函数的名字。
 */
FtraceFrame::FtraceFrame(std::uint64_t InputCallPC, std::uint64_t InputReturnPC, std::uint64_t InputFunctionAddress, std::string_view InputFunctionName) noexcept
{
    CallPC = InputCallPC;
    ReturnPC = InputReturnPC;
    FunctionAddress = InputFunctionAddress;
    FunctionName = InputFunctionName;
}
/**
 * @brief 获取调用指令所在的PC。
 *
 * @return std::uint64_t，调用指令所在的PC。
 */
std::uint64_t FtraceFrame::GetCallPC() const noexcept
{
    return CallPC;
}
/**
 * @brief 获取本次调用预期返回到的PC。
 *
 * @return std::uint64_t，本次调用预期返回到的PC。
 */
std::uint64_t FtraceFrame::GetReturnPC() const noexcept
{
    return ReturnPC;
}
/**
 * @brief 获取被调用函数的入口地址。
 *
 * @return std::uint64_t，被调用函数的入口地址。
 */
std::uint64_t FtraceFrame::GetFunctionAddress() const noexcept
{
    return FunctionAddress;
}
/**
 * @brief 获取被调用函数的名字。
 *
 * @return std::string_view，被调用函数的名字。
 */
std::string_view FtraceFrame::GetFunctionName() const noexcept
{
    return FunctionName;
}
