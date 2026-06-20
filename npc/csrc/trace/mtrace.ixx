export module npc.trace.mtrace;
import std;

export void MtraceRecord(std::uint32_t pc, std::uint32_t addr, std::uint32_t wdata,
                  std::uint32_t rdata, std::uint8_t width, bool wen);
