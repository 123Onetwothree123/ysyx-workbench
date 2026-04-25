#ifndef NPC_SDB_H
#define NPC_SDB_H

#include <cstdint>
#include <cstddef>
#include <print>
#include <VRV32E32Reg.h>

extern "C" int NPCGetGPR(int RegNum);
uint32_t CPP_NPCGetGPR(int reg_num);
void PrintGPR();
// 内存扫描接口
uint32_t NPCMemoryRead(uint32_t addr, size_t len = 4); // 单地址内存读取
void NPCMemoryScan(uint32_t addr, size_t count);       // 批量内存扫描
extern "C" int NPCGetPC();
uint32_t CPP_NpcGetPC();
void sdb_main_loop(std::unique_ptr<VRV32E32Reg> &top, size_t &cycles);
#endif
