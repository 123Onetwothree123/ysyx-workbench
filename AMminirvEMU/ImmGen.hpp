#include <cstdint>
#ifndef IMMGEN_HPP
#define IMMGEN_HPP
#include "Decoder.hpp"

class ImmGen {
private:
    std::int32_t extractI(std::uint32_t inst);
    std::int32_t extractS(std::uint32_t inst);
    std::int32_t extractU(std::uint32_t inst);
public:
    std::int32_t Generate(std::uint32_t inst);
};
#endif