#ifndef RECORD_INSTRUCTION_HPP
#define RECORD_INSTRUCTION_HPP
#include <cstdint>
class RecordInstruction
{
private:
    uint64_t pc{0};
    uint32_t instruction{0};
    int len{0};

public:
    RecordInstruction() noexcept;
    RecordInstruction(std::uint64_t pc, std::uint32_t instruction, int len);
    ~RecordInstruction();
    [[nodiscard]] uint64_t GetPC() const;
    [[nodiscard]] uint32_t GetInstruction() const;
    [[nodiscard]] int GetLen() const;
    void SetPC(uint64_t InputPC);
    void SetInstruction(uint32_t InputInstruction);
    void SetLen(int InputLen);
};
#endif
