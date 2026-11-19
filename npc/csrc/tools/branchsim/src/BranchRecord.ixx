module;
#include <cstdint>
export module BranchRecord;
import std;

// XLEN 编译期由 CONFIG_XLEN 决定；JALR immediate 保持 32 位。
export inline constexpr unsigned kXlen = CONFIG_XLEN;
static_assert(kXlen == 32 || kXlen == 64,
              "CONFIG_XLEN 必须是 32 或 64");

export using Addr = std::conditional_t<kXlen == 64, std::uint64_t, std::uint32_t>;

// 记录类型；link register 按 RISC-V hint 包括 x1/x5。
export enum class BranchKind : std::uint8_t
{
    Branch,
    Jal,
    Call,
    Ret,
    JalrCall,
    Coroutine,
    JalrOther
};
export class BranchRecord
{
private:
    Addr pc;
    Addr target;
    bool taken;
    BranchKind kind;
    // JALR 符号扩展 immediate；预测 (RAS top + imm) & ~1。
    std::uint32_t jalr_immediate;
public:
    BranchRecord() = default;
    BranchRecord(Addr p, Addr t, bool tk,
                 BranchKind k = BranchKind::Branch,
                 std::uint32_t immediate = 0);
    ~BranchRecord() = default;
    Addr GetPC() const;
    Addr GetTarget() const;
    bool GetTaken() const;
    BranchKind GetKind() const;
    std::uint32_t GetJalrImmediate() const;
};
