export module npc.difftest.difftest;
import std;
import npc.DUT;

// 加载参考CPU动态库并初始化比对环境
export std::expected<void, std::string> DifftestInitialize(const std::optional<std::filesystem::path> &RefSoFile, std::size_t ImageSize);
// 参考CPU执行一步后与DUT寄存器状态比对，不一致就直接触发ebreak
export void DifftestStep(DUT &dut);
// 跑完后整体比对：NEMU跑至trap，与DUT最终状态对比
export void DiftestFinalCheck(DUT &dut);
// 返回DiffTest是否已启用
export bool DifftestIsEnabled();
