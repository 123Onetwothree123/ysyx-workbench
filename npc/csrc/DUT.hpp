#include <memory>
#include <cstdint>
#include "VRV32I.h"
#include "AXI/AXI.hpp"

class DUT
{
private:
    std::unique_ptr<VRV32I> dut;
    std::size_t cycle{0};

public:
    DUT();
    ~DUT() = default;
    // 运算符重载，少写点代码
    VRV32I &operator*();
    VRV32I *operator->();
    void eval();
    void final();
    void step();
    void step(AXI &axi);
    void reset();
    std::size_t GetCycle() const;
};
