export module BPAlgorithmsType;
import std;
import BPAlgorithmBase;
import AlwaysJump;
import BTFN;
import BTFNSharedJal;
import BTFNSplitJal;
import BTB;
import BPConfig;
export class BPAlgorithmsType
{
private:
    std::vector<std::unique_ptr<BPAlgorithmBase>> algos;


public:
    explicit BPAlgorithmsType(const BPConfig& config);
    ~BPAlgorithmsType() = default;
    BPAlgorithmsType(BPAlgorithmsType &&) noexcept = default;
    BPAlgorithmsType &operator=(BPAlgorithmsType &&) noexcept = default;
    BPAlgorithmsType(const BPAlgorithmsType &) = delete;
    BPAlgorithmsType &operator=(const BPAlgorithmsType &) = delete;
    [[nodiscard]] auto begin() const noexcept;
    [[nodiscard]] auto end() const noexcept;
    [[nodiscard]] std::size_t size() const noexcept;
    BPAlgorithmBase& operator[](std::size_t i) const;
};
