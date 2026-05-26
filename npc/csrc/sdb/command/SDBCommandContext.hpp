#ifndef SDB_COMMAND_CONTEXT_HPP
#define SDB_COMMAND_CONTEXT_HPP
#include <cstddef>
class VRV32E32Reg;
class SDBCommandContext
{
public:
    SDBCommandContext(VRV32E32Reg &Top, std::size_t &Cycles);
    [[nodiscard]] VRV32E32Reg &GetTop() const noexcept;
    [[nodiscard]] std::size_t &GetCycles() const noexcept;
private:
    VRV32E32Reg &TopRef;// 对VRV32E32Reg的引用，命令执行时可以直接操作这个对象
    std::size_t &CyclesRef;// 对周期数的引用，命令执行时可以直接修改这个值
};
#endif
