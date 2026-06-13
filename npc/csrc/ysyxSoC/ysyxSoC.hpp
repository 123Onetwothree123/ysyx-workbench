#ifndef YSYXSOC_HPP
#define YSYXSOC_HPP
#include <cstdint>
#include <vector>
extern std::vector<uint8_t> mrom; // 暂时存的
extern "C" void flash_read(int32_t addr, int32_t *data);
extern "C" void mrom_read(int32_t addr, int32_t *data);
#endif