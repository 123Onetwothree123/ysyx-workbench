export module BPAlgorithmsType;
import std;
import BPAlgorithmBase;
import AlwaysJump;
import BTFN;

export class BPAlgorithmsType
{
private:
    std::vector<std::unique_ptr<BPAlgorithmBase>> algos;
public:
    BPAlgorithmsType();
    ~BPAlgorithmsType() = default;

    BPAlgorithmsType(BPAlgorithmsType&&) noexcept = default;
    BPAlgorithmsType& operator=(BPAlgorithmsType&&) noexcept = default;
    BPAlgorithmsType(const BPAlgorithmsType&) = delete;
    BPAlgorithmsType& operator=(const BPAlgorithmsType&) = delete;

    [[nodiscard]] auto begin() const noexcept;
    [[nodiscard]] auto end()   const noexcept;
};