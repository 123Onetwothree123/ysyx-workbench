#ifndef IMMGEN_H
#define IMMGEN_H
#include "Decoder.h"

class ImmGen {
private:
    int32_t extractI(uint32_t inst);
    int32_t extractS(uint32_t inst);
    int32_t extractU(uint32_t inst);
public:
    int32_t Generate(uint32_t inst);
};
#endif