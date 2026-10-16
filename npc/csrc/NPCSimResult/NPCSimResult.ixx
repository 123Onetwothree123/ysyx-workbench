export module npc.NPCSimResult;
import std;
import npc.PerfStats;

export class NPCSimResult final
{
public:
    NPCSimResult() = delete;
    [[nodiscard]] static std::filesystem::path CreateDefaultResultDirectory();
    static void Save(
        std::filesystem::path result_dir,
        const PerfStats &stats,
        std::size_t total_cycles,
        std::size_t total_instructions);
};
