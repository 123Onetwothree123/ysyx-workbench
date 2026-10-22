export module npc.trace.mtrace;
import std;

// RV64 的 PC/地址/数据均使用 64 位；RV32 值自然零扩展。
export void MtraceRecord(std::uint64_t pc, std::uint64_t addr, std::uint64_t wdata,
                  std::uint64_t rdata, std::uint8_t widthEncoding, bool wen);
