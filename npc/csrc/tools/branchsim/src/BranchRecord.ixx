module;
#include <cstdint>
export module BranchRecord;
import std;
// 记录类型: 条件分支 / jal(jalr/ret 需要RAS, 不在本工具范围内)
export enum class BranchKind : std::uint8_t { Branch, Jal };
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
