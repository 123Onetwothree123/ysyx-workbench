module;
#include <cstdint>
export module AccessRecord;
import std;

// XLEN 编译期由 CONFIG_XLEN 决定，地址/块号使用 Addr。
export inline constexpr unsigned kXlen = CONFIG_XLEN;
static_assert(kXlen == 32 || kXlen == 64,
              "CONFIG_XLEN 必须是 32 或 64");

export using Addr = std::conditional_t<kXlen == 64, std::uint64_t, std::uint32_t>;

export class AccessRecord
{
private:
    Addr addr; // 访存地址
    bool is_write; // 0=load，1=store
public:
    AccessRecord() = default;
    AccessRecord(Addr a, bool w);
    ~AccessRecord() = default;
    Addr GetAddr() const;
    bool GetIsWrite() const;
};
