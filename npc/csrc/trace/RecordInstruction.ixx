export module npc.trace.RecordInstruction;
import std;

export class RecordInstruction
{
private:
    std::uint64_t pc{0};
    std::uint32_t instruction{0};
    int len{0};
public:
    RecordInstruction() noexcept;
    RecordInstruction(std::uint64_t pc, std::uint32_t instruction, int len);
    ~RecordInstruction();
    [[nodiscard]] std::uint64_t GetPC() const;
    [[nodiscard]] std::uint32_t GetInstruction() const;
    [[nodiscard]] int GetLen() const;
    void SetPC(std::uint64_t InputPC);
    void SetInstruction(std::uint32_t InputInstruction);
    void SetLen(int InputLen);
};
