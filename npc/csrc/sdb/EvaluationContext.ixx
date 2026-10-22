export module npc.sdb.EvaluationContext;
import std;

export class EvaluationContext
{
public:
    virtual ~EvaluationContext() = default;
    // 表达式与 SDB 地址统一使用 64 位；RV32 值自然零扩展，RV64 不截断。
    virtual std::uint64_t ReadRegister(std::string_view name) const = 0;
    virtual std::uint64_t ReadMemory(std::uint64_t address, std::size_t size) const = 0;
    virtual std::uint64_t GetPC() const = 0;
};
