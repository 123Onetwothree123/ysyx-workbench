module;
#include <cstdint>
export module BranchRecord;
import std;

// 目标 XLEN 由 Kconfig CONFIG_XLEN 在编译期确定。PC、分支目标、RAS、
// BTB tag 使用 Addr；JALR immediate 是真实 32 位指令字段，保持 uint32_t。
export inline constexpr unsigned kXlen = CONFIG_XLEN;
static_assert(kXlen == 32 || kXlen == 64,
              "CONFIG_XLEN 必须是 32 或 64");

export using Addr = std::conditional_t<kXlen == 64, std::uint64_t, std::uint32_t>;

// 记录类型:
//   Branch=条件分支, Jal=jal非调用(rd!=ra), Call=jal ra直接调用(压RAS),
//   Ret=jalr只弹RAS, JalrCall=jalr只压RAS, Coroutine=jalr同拍弹后压,
//   JalrOther=不含RAS hint的其他jalr。link register按RISC-V hint同时包括x1/x5。
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
    Addr pc; // 分支指令所在地址
    Addr target;
    bool taken; // 跳没跳
    BranchKind kind;
    // JALR的符号扩展immediate位模式。Ret/Coroutine的JAL BTB保存它，
    // 预测目标为 (RAS top + immediate) & ~1；旧trace缺省为0。
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
