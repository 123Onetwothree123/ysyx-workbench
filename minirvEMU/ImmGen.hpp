#ifndef IMMGEN_HPP
#define IMMGEN_HPP
#include "Decoder.hpp"

class ImmGen
{
public:
    ImmGen() = default;
    ~ImmGen() = default;
    std::int32_t Generate(std::uint32_t inst);
private:
    static std::int32_t extractI(std::uint32_t inst);
    static std::int32_t extractS(std::uint32_t inst);
    static std::int32_t extractU(std::uint32_t inst);
};
#endif
