#ifndef SDB_COMMAND_CONTEXT_HPP
#define SDB_COMMAND_CONTEXT_HPP
#include "DUT.hpp"
class SDBCommandContext
{
private:
    DUT &dut;

public:
    SDBCommandContext() = delete;
    ~SDBCommandContext() = default;
    [[nodiscard]] DUT &GetDUT() const noexcept;
    SDBCommandContext(DUT &InputDUT);
};
#endif