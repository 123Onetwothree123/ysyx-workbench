#ifndef NPC_SDB_HPP
#define NPC_SDB_HPP

#include <cstddef>
#include <memory>
#include <VRV32E32Reg.h>

void sdb_main_loop(std::unique_ptr<VRV32E32Reg> &top, std::size_t &cycles, bool batch_mode = false);

#endif
