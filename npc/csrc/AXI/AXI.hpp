#ifndef AXI_HPP
#define AXI_HPP
#include <cstdint>
#include "../MMIO/MMIO.hpp"
#include "VysyxSoCFull.h"
class AXI
{
private:
    // AR到R
    std::uint32_t ReadAddress{0};
    bool ReadPending{false}; // 发了地址，等数据回来
    void HandleReadAR(VysyxSoCFull &cpu) noexcept;
    void HandleReadR(VysyxSoCFull &cpu) noexcept;
    // AW+W到B
    bool DataWriteResponsePending{false}; // 发了地址和数据，等B回复
    void HandleWriteAW_W(VysyxSoCFull &cpu) noexcept;
    void HandleWriteB(VysyxSoCFull &cpu) noexcept;
    // 新加的，为了接时间器
    MMIO mmio;
    std::uint64_t Cycles{0};
    // 新加的，为了模拟真的AXI，因为刚刚改了LSU，所以现在也加一套接受写入的东西
    std::uint32_t DataWriteAddress{0};
    std::uint32_t DataWriteData{0};
    std::uint8_t DataWriteMask{0};
    // 这个建议的双状态的pending以后再重做的时候看看有没有好的状态机的设计思路来设计
    bool DataWriteAddressPending{false};
    bool DataWriteDataPending{false};

public:
    AXI() = default;
    ~AXI() = default;
    void reset(VysyxSoCFull &CPU);
    void eval(VysyxSoCFull &CPU);
};
#endif