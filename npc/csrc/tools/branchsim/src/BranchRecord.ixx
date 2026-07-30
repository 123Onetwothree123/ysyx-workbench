module;
#include <cstdint>
export module BranchRecord;
import std;
export class BranchRecord
{
private:
    std::uint32_t pc; // 分支指令所在地址
    std::uint32_t target;
    bool taken; // 跳没跳
public:
    BranchRecord() = default;
    BranchRecord(std::uint32_t p, std::uint32_t t, bool tk);
    ~BranchRecord() = default;
    std::uint32_t GetPC() const;
    std::uint32_t GetTarget() const;
    bool GetTaken() const;
};