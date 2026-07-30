module;
#include <cstdint>
export module AccessRecord;
import std;
export class AccessRecord
{
private:
    std::uint32_t addr; // 访存地址
    bool is_write;      // 0=读(load), 1=写(store)
public:
    AccessRecord() = default;
    AccessRecord(std::uint32_t a, bool w);
    ~AccessRecord() = default;
    std::uint32_t GetAddr() const;
    bool GetIsWrite() const;
};
