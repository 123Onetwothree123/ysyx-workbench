#ifndef FTRACE_FRAME_HPP
#define FTRACE_FRAME_HPP
#include <cstdint>
#include <string_view>
/*
栈帧，拿来描述一次函数调用的信息
*/
class FtraceFrame final
{
public:
    FtraceFrame();                                                                                                                                       // 构造一个空栈帧
    FtraceFrame(std::uint64_t InputCallPC, std::uint64_t InputReturnPC, std::uint64_t InputFunctionAddress, std::string_view InputFunctionName) noexcept; // 构造一条指定调用PC、返回PC、函数入口和函数名的栈帧
    [[nodiscard]] std::uint64_t GetCallPC() const noexcept;                                                                                               // 获取调用指令所在的PC
    [[nodiscard]] std::uint64_t GetReturnPC() const noexcept;                                                                                             // 获取本次调用预期返回到的PC
    [[nodiscard]] std::uint64_t GetFunctionAddress() const noexcept;                                                                                      // 获取被调用函数的入口地址
    [[nodiscard]] std::string_view GetFunctionName() const noexcept;                                                                                      // 获取被调用函数的名字
private:
    std::uint64_t CallPC{};          // 调用指令所在的PC
    std::uint64_t ReturnPC{};        // 本次调用预期返回到的PC
    std::uint64_t FunctionAddress{}; // 被调用函数的入口地址
    std::string_view FunctionName{}; // 被调用函数的名字
};
#endif
