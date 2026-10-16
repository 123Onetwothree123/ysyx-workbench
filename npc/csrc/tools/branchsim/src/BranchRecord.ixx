module;
#include <cstdint>
export module BranchRecord;
import std;
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
    std::uint32_t pc; // 分支指令所在地址
    std::uint32_t target;
    bool taken; // 跳没跳
    BranchKind kind;
    // JALR的符号扩展immediate位模式。Ret/Coroutine的JAL BTB保存它，
    // 预测目标为 (RAS top + immediate) & ~1；旧trace缺省为0。
    std::uint32_t jalr_immediate;
public:
    BranchRecord() = default;
    BranchRecord(std::uint32_t p, std::uint32_t t, bool tk,
                 BranchKind k = BranchKind::Branch,
                 std::uint32_t immediate = 0);
    ~BranchRecord() = default;
    std::uint32_t GetPC() const;
    std::uint32_t GetTarget() const;
    bool GetTaken() const;
    BranchKind GetKind() const;
    std::uint32_t GetJalrImmediate() const;
};
