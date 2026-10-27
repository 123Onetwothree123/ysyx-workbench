module npc.configgen;
import std;
namespace npc::configgen {
const std::vector<PerfGroup> &perfGroups()
{
    static const std::vector<PerfGroup> groups{
        {"BASIC", {"IFU_FETCH", "EXU_DONE", "LSU_LOAD", "LSU_STORE"}},
        {"OPCLASS", {"ALU_OP", "MEM_OP", "CSR_OP", "BRANCH_OP", "JAL_OP", "JALR_OP", "EXECUTION_ACTIVE"}},
        {"MDU", {"MDU_REQ", "MDU_DONE", "MDU_OP", "MDU_ACTIVE", "MDU_WAIT"}},
        {"IFU_STALL", {"IFU_STALL_PIPELINE", "IFU_STALL_AXI", "IFU_STALL_AR", "IFU_STALL_R", "IFU_STALL_REDIRECT", "IFU_STALL_IDLE"}},
        {"ICACHE", {"ICACHE_HIT", "ICACHE_MISS"}},
        {"DCACHE", {"DCACHE_HIT", "DCACHE_MISS", "DCACHE_REFILL_REQ", "DCACHE_REFILL_RESP"}},
        {"EXU_STALL", {"EXU_STALL_LSU", "EXU_IDLE_NOINPUT", "MEM_WAITSLOT"}},
        {"LSU", {"LSU_ACTIVE", "LSU_LOAD_ACTIVE", "LSU_STORE_ACTIVE", "LSU_STALL_READ_AR", "LSU_STALL_READ_R", "LSU_STALL_WRITE_REQ", "LSU_STALL_WRITE_B"}},
        {"IDU_STALL", {"IDU_STALL_RAW", "IDU_STALL_RAW_LOADUSE", "IDU_STALL_RAW_ALU"}},
        {"TRAP", {"TRAP_COUNT"}},
    };
    return groups;
}
}
