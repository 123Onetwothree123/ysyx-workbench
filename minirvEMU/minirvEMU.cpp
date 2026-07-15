#include "minirvEMU.h"
uint8_t minirvEMU::get_rd(uint32_t inst)
{
    return (inst >> 7) & 0x1F;
}
uint8_t minirvEMU::get_rs1(uint32_t inst)
{
    return (inst >> 15) & 0x1F;
}
uint8_t minirvEMU::get_rs2(uint32_t inst)
{
    return (inst >> 20) & 0x1F;
}
void minirvEMU::ensure_memory(uint32_t word_idx)
{
    if (word_idx >= M.size())
    {
        // 每次至少扩容 1KB (256个字)，避免频繁分配
        M.resize(word_idx + 256, 0);
    }
}
minirvEMU::minirvEMU()
{
    R.fill(0);
    M.resize(1024, 0); // 初始分配 4KB
}
void minirvEMU::reset()
{
    PC = 0;
    R.fill(0);
    M.clear();
}
uint32_t minirvEMU::GetPC() const
{
    return PC;
}
void minirvEMU::SetPC(uint32_t value)
{
    PC = value;
}
uint32_t minirvEMU::GetRegister(size_t index) const
{
    if (index >= R.size())
    {
        throw std::out_of_range("Register index out of range");
    }
    return R[index];
}
void minirvEMU::SetRegister(size_t index, uint32_t value)
{
    if (index >= R.size())
    {
        throw std::out_of_range("Register index out of range");
    }
    R[index] = value;
}
uint32_t minirvEMU::GetMemory(size_t address) const
{
    if (address >= M.size())
    {
        throw std::out_of_range("Memory address out of range");
    }
    return M[address];
}
void minirvEMU::SetMemory(size_t address, uint32_t value)
{
    if (address >= M.size())
    {
        throw std::out_of_range("Memory address out of range");
    }
    M[address] = value;
}
size_t minirvEMU::GetMemorySize() const
{
    return M.size();
}
size_t minirvEMU::GetRegisterCount() const
{
    return R.size();
}
void minirvEMU::IncrementPC()
{
    PC++;
}
/*
void minirvEMU::PrintState() const
{
    constexpr size_t BYTES_PER_LINE = 16;
    std::cout << "=== Virtual Machine State ===" << std::endl;
    std::cout << "PC: 0x" << std::hex << std::setw(8) << std::setfill('0')
              << PC << std::dec << std::endl;
    std::ostringstream regs_ss;
    for (size_t i = 0; i < R.size(); ++i)
    {
        regs_ss << "R" << i << "=0x"
                << std::hex << std::setw(8) << std::setfill('0')
                << R[i] << std::dec;
        if (i != R.size() - 1)
            regs_ss << ", ";
        if ((i + 1) % 4 == 0 && i != R.size() - 1)
            regs_ss << "\n           ";
    }
    std::cout << "Registers: " << regs_ss.str() << std::endl;
    std::cout << "Memory (" << M.size() << " bytes):" << std::endl;
    for (size_t i = 0; i < M.size(); i += BYTES_PER_LINE)
    {
        std::cout << "  0x" << std::hex << std::setw(4) << std::setfill('0')
                  << i << ": ";
        for (size_t j = 0; j < BYTES_PER_LINE && i + j < M.size(); ++j)
        {
            std::cout << std::setw(2) << std::setfill('0')
                      << static_cast<int>(M[i + j]) << " ";
        }
        std::cout << std::endl;
    }
    std::cout << std::dec;
    std::cout << "=============================" << std::endl;
}
    */
// Gemini3.0pro写的函数
void minirvEMU::PrintState() const
{
    std::cout << "\n=== Virtual Machine State ===" << std::endl;
    // 打印 PC，使用 hex 后立即恢复 dec
    std::cout << "PC: 0x" << std::hex << std::setw(8) << std::setfill('0') << PC << std::dec << std::endl;

    // 打印寄存器
    for (size_t i = 0; i < R.size(); ++i)
    {
        // 显式用 std::dec 打印索引 i，用 std::hex 打印值
        std::cout << "R" << std::setfill(' ') << std::setw(2) << std::dec << i << "=0x"
                  << std::hex << std::setw(8) << std::setfill('0') << R[i] << "  ";
        if ((i + 1) % 4 == 0)
            std::cout << std::endl;
    }

    // 打印内存的前 128 字节 (按字节读取)
    std::cout << std::dec << "Memory (First 128 bytes):" << std::endl;
    for (size_t i = 0; i < 128; i += 16)
    {
        std::cout << "  0x" << std::hex << std::setw(4) << std::setfill('0') << i << ": ";
        for (size_t j = 0; j < 16; ++j)
        {
            // 使用 read_byte 确保读取的是正确的字节
            uint8_t b = const_cast<minirvEMU *>(this)->read_byte(i + j);
            std::cout << std::setw(2) << std::setfill('0') << (int)b << " ";
        }
        std::cout << std::dec << std::endl;
    }
    std::cout << "=============================\n"
              << std::endl;
}
void minirvEMU::write_word(uint32_t addr, uint32_t value)
{
    if (vga.is_vga_addr(addr))
    {
        vga.write_word(addr, value);
        return;
    }
    uint32_t idx = addr >> 2; // 字节地址转字索引
    ensure_memory(idx);
    M[idx] = value;
}
uint32_t minirvEMU::read_word(uint32_t addr)
{
    uint32_t idx = addr >> 2;
    ensure_memory(idx);
    return M[idx];
}
void minirvEMU::write_byte(uint32_t addr, uint8_t value)
{
    uint32_t idx = addr >> 2;
    uint32_t offset = (addr % 4) * 8; // 计算在 32 位字中的偏移
    ensure_memory(idx);
    // 这里先去用掩码操作，先清除掉旧字节，然后再写入新字节
    uint32_t mask = ~(0xFF << offset);
    M[idx] = (M[idx] & mask) | (static_cast<uint32_t>(value) << offset);
}
uint8_t minirvEMU::read_byte(uint32_t addr)
{
    uint32_t idx = addr >> 2;
    uint32_t offset = (addr % 4) * 8;
    ensure_memory(idx);
    return static_cast<uint8_t>((M[idx] >> offset) & 0xFF);
}
// 将指令流存入内存开头
void minirvEMU::LoadProgram(const std::vector<uint32_t> &program)
{
    for (size_t i = 0; i < program.size(); ++i)
    {
        write_word(i * 4, program[i]);
    }
}
void minirvEMU::LoadProgram(const std::initializer_list<uint32_t> &program)
{
    LoadProgram(std::vector<uint32_t>(program));
}
void minirvEMU::step()
{
    if (halted)
    {
        return;
    };
    auto inst = read_word(PC);
    auto type = decoder.OpDecode(inst);
    auto imm = immGen.Generate(inst);
    auto rd = get_rd(inst);
    auto rs1 = get_rs1(inst);
    auto rs2 = get_rs2(inst);
    uint32_t NEXT_PC = PC + 4;
    switch (type)
    {
    case Decoder::InstrType::INSTR_LUI:
    {
        R[rd] = (uint32_t)imm;
        break;
    }
    case Decoder::InstrType::INSTR_ADD:
    {
        R[rd] = R[rs1] + R[rs2];
        break;
    }
    case Decoder::InstrType::INSTR_ADDI:
    {
        R[rd] = R[rs1] + imm;
        break;
    }
    case Decoder::InstrType::INSTR_LW:
    {
        R[rd] = read_word(R[rs1] + imm);
        break;
    }
    case Decoder::InstrType::INSTR_LBU:
    {
        R[rd] = read_byte(R[rs1] + imm);
        break;
    }
    case Decoder::InstrType::INSTR_SW:
    {
        write_word(R[rs1] + imm, R[rs2]);
        break;
    }
    case Decoder::InstrType::INSTR_SB:
    {
        write_byte(R[rs1] + imm, R[rs2] & 0xFF);
        break;
    }
    case Decoder::InstrType::INSTR_JALR:
    {
        uint32_t target = (R[rs1] + imm) & ~1u;
        R[rd] = PC + 4;
        NEXT_PC = target;
        break;
    }
    case Decoder::InstrType::INSTR_EBREAK:
    {
        halted = true;
        if (R[REG_A0] == 0)
        {
            std::cout << "HIT GOOD TRAP" << std::endl;
        }
        else
        {
            std::cout << "HIT BAD TRAP" << std::endl;
        }
        return;
    }
    case Decoder::InstrType::INSTR_UNKNOWN:
    {
        std::cout << "Invalid instruction! Skipping..." << std::endl;
        break;
    }
    }
    R[0] = 0; // risc-v手册上写的是x0寄存器要恒为 0
    PC = NEXT_PC;
}
bool minirvEMU::IsHalted() const
{
    return halted;
}
void minirvEMU::UpdateVGA()
{
    vga.update_screen();
}