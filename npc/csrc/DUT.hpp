#include <memory>
#include <cstdint>
#include "VysyxSoCFull.h"

class DUT
{
private:
    std::unique_ptr<VysyxSoCFull> dut;
    std::size_t cycle{0};

public:
    DUT();
    ~DUT() = default;
    // 运算符重载，少写点代码
    VysyxSoCFull &operator*();
    VysyxSoCFull *operator->();
    void eval();
    void final();
    void step();
    void reset();
    std::size_t GetCycle() const;
};
