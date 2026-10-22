module npc.trace.mtrace;
void MtraceRecord(std::uint64_t pc, std::uint64_t addr, std::uint64_t wdata,
                  std::uint64_t rdata, std::uint8_t widthEncoding, bool wen)
{
    // width=3 是 RV64 的 8 字节 LD/SD。
    const auto width{widthEncoding <= 3U
                         ? std::format("{}B", 1U << widthEncoding)
                         : std::format("invalid(code={})", static_cast<unsigned>(widthEncoding))};
    if (wen)
    {
        std::println("Memory Write: PC=0x{:x}, Addr=0x{:x}, Data=0x{:x}, Width={}",
                     pc, addr, wdata, width);
    }
    else
    {
        std::println("Memory Read:  PC=0x{:x}, Addr=0x{:x}, Data=0x{:x}, Width={}",
                     pc, addr, rdata, width);
    }
}
