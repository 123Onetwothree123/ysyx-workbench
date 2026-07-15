#ifndef IMMGEN_H
#define IMMGEN_H
#include "Decoder.h"
#include <cstdint>
class ImmGen
{
private:
    Decoder decoder;
    // I-type 提取并符号扩展
    int32_t extractI(uint32_t inst);
    // S-type 提取并符号扩展
    int32_t extractS(uint32_t inst);
    // U-type 提取（高20位立即数）
    int32_t extractU(uint32_t inst);
public:
    ImmGen() = default;
    ~ImmGen() = default;
    int32_t Generate(uint32_t inst);
};
#endif