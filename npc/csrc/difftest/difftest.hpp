#ifndef DIFFTEST_HPP
#define DIFFTEST_HPP
#include <cstddef>
#include <expected>
#include <filesystem>
#include <optional>
#include <string>
class DUT;
// 加载参考CPU动态库并初始化比对环境
std::expected<void, std::string> DifftestInitialize(const std::optional<std::filesystem::path> &RefSoFile, std::size_t ImageSize);
// 参考CPU执行一步后与DUT寄存器状态比对，不一致就直接触发ebreak
void DifftestStep(DUT &dut);
// 返回DiffTest是否已启用
bool DifftestIsEnabled();
#endif
