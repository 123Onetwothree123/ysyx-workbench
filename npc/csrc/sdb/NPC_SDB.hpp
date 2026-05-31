#ifndef NPC_SDB_HPP
#define NPC_SDB_HPP
#include <cstddef>
class VRV32E32Reg;
void sdb_main_loop(VRV32E32Reg &top, std::size_t &cycles, bool batch_mode = false);
#endif
