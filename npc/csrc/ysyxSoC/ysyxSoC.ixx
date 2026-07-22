export module npc.ysyxSoC;
import std;

export extern std::vector<std::uint8_t> mrom;
export extern std::vector<std::uint8_t> FlashMemory;
export extern "C" void flash_read(std::int32_t addr, std::int32_t *data);
export extern "C" void mrom_read(std::int32_t addr, std::int32_t *data);
export extern "C" void axi_debug_probe(std::int32_t tag, std::int32_t addr, std::int32_t resp);
