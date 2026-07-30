module;
#include <cstdint>
export module BranchRecord;
import std;
// 记录类型:
//   Branch=条件分支, Jal=jal非调用(rd!=ra), Call=jal ra直接调用(压RAS),
//   Ret=ret(jalr x0,ra,0, 弹RAS), JalrCall=jalr ra间接调用(压RAS), JalrOther=其他jalr
export enum class BranchKind : std::uint8_t { Branch, Jal, Call, Ret, JalrCall, JalrOther };
export class BranchRecord
{
private:
    std::uint32_t pc; // 分支指令所在地址
    std::uint32_t target;
    bool taken; // 跳没跳
    BranchKind kind;
public:
    BranchRecord() = default;
    BranchRecord(std::uint32_t p, std::uint32_t t, bool tk, BranchKind k = BranchKind::Branch);
    ~BranchRecord() = default;
    std::uint32_t GetPC() const;
    std::uint32_t GetTarget() const;
    bool GetTaken() const;
    BranchKind GetKind() const;
};
