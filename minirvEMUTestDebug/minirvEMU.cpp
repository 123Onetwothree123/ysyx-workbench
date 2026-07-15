#include "minirvEMU.h"
#include <sstream>
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
    state_signature_history.clear();
}
void minirvEMU::reset()
{
    PC = 0;
    R.fill(0);
    M.clear();
    halted = false;
    state_signature_history.clear();
}
uint64_t minirvEMU::build_state_signature() const
{
    // FNV-1a 64-bit hash over (PC + 16 registers)
    uint64_t hash = 1469598103934665603ULL;
    auto mix_u32 = [&hash](uint32_t value) {
        for (int i = 0; i < 4; ++i)
        {
            uint8_t b = static_cast<uint8_t>((value >> (i * 8)) & 0xFF);
            hash ^= b;
            hash *= 1099511628211ULL;
        }
    };
    mix_u32(PC);
    for (uint32_t reg : R)
    {
        mix_u32(reg);
    }
    return hash;
}
bool minirvEMU::detect_state_cycle(size_t &period)
{
    const uint64_t signature = build_state_signature();
    state_signature_history.push_back(signature);
    if (state_signature_history.size() > LOOP_HISTORY_LIMIT)
    {
        state_signature_history.pop_front();
    }

    for (size_t p = 1; p <= LOOP_MAX_PERIOD; ++p)
    {
        const size_t need = p * LOOP_REPEAT_TIMES;
        if (state_signature_history.size() < need)
        {
            continue;
        }
        const size_t last = state_signature_history.size() - 1;
        bool repeated = true;
        for (size_t k = 1; k < LOOP_REPEAT_TIMES; ++k)
        {
            if (state_signature_history[last] != state_signature_history[last - p * k])
            {
                repeated = false;
                break;
            }
        }
        if (repeated)
        {
            period = p;
            return true;
        }
    }
    return false;
}
bool minirvEMU::EnableTrace(const std::filesystem::path &trace_file_path)
{
    DisableTrace();
    trace_stream.open(trace_file_path, std::ios::out | std::ios::trunc);
    if (!trace_stream.is_open())
    {
        return false;
    }
    trace_enabled = true;
    trace_step_active = false;
    trace_step_counter = 0;
    trace_step_pc = 0;
    trace_step_inst = 0;
    trace_memory_entries.clear();
    trace_stream << "record_type,step,pc,inst,"
                 << "x0,x1,x2,x3,x4,x5,x6,x7,x8,x9,x10,x11,x12,x13,x14,x15,"
                 << "mem_access,mem_width,pa_shifted,va_raw,value" << std::endl;
    trace_stream.flush();
    return true;
}
void minirvEMU::DisableTrace()
{
    trace_enabled = false;
    trace_step_active = false;
    trace_step_counter = 0;
    trace_step_pc = 0;
    trace_step_inst = 0;
    trace_memory_entries.clear();
    if (trace_stream.is_open())
    {
        trace_stream.close();
    }
}
void minirvEMU::trace_step_begin()
{
    if (!trace_enabled || !trace_stream.is_open())
    {
        return;
    }
    trace_step_active = true;
    ++trace_step_counter;
    trace_step_pc = PC;
    trace_step_inst = 0;
    trace_memory_entries.clear();
}
void minirvEMU::trace_memory_access(const char *access, const char *width, uint32_t virtual_addr_raw, uint32_t value)
{
    if (!trace_enabled || !trace_step_active)
    {
        return;
    }
    trace_memory_entries.push_back(MemoryTraceEntry{
        access,
        width,
        virtual_addr_raw >> 2,
        virtual_addr_raw,
        value,
    });
}
void minirvEMU::trace_step_end()
{
    if (!trace_enabled || !trace_step_active || !trace_stream.is_open())
    {
        return;
    }
    auto to_hex32 = [](uint32_t value) {
        std::ostringstream os;
        os << "0x" << std::hex << std::setw(8) << std::setfill('0') << value;
        return os.str();
    };

    trace_stream << "STEP,"
                 << std::dec << trace_step_counter << ","
                 << to_hex32(trace_step_pc) << ","
                 << to_hex32(trace_step_inst);
    for (size_t i = 0; i < R.size(); ++i)
    {
        trace_stream << "," << to_hex32(R[i]);
    }
    trace_stream << ",,,,," << std::endl;

    for (const auto &entry : trace_memory_entries)
    {
        trace_stream << "MEM,"
                     << std::dec << trace_step_counter << ",,";
        for (size_t i = 0; i < R.size(); ++i)
        {
            trace_stream << ",";
        }
        trace_stream << ","
                     << entry.access << ","
                     << entry.width << ","
                     << to_hex32(entry.physical_addr_shifted) << ","
                     << to_hex32(entry.virtual_addr_raw) << ","
                     << to_hex32(entry.value) << std::endl;
    }
    trace_stream.flush();
    trace_step_active = false;
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
    // TODO: VGA support
    uint32_t idx = addr >> 2; // 字节地址转字索引
    ensure_memory(idx);
    M[idx] = value;
    trace_memory_access("WRITE", "WORD", addr, value);
}
uint32_t minirvEMU::read_word(uint32_t addr)
{
    uint32_t idx = addr >> 2;
    ensure_memory(idx);
    uint32_t value = M[idx];
    trace_memory_access("READ", "WORD", addr, value);
    return value;
}
void minirvEMU::write_byte(uint32_t addr, uint8_t value)
{
    uint32_t idx = addr >> 2;
    uint32_t offset = (addr % 4) * 8; // 计算在 32 位字中的偏移
    ensure_memory(idx);
    // 这里先去用掩码操作，先清除掉旧字节，然后再写入新字节
    uint32_t mask = ~(0xFF << offset);
    M[idx] = (M[idx] & mask) | (static_cast<uint32_t>(value) << offset);
    trace_memory_access("WRITE", "BYTE", addr, value);
}
uint8_t minirvEMU::read_byte(uint32_t addr)
{
    uint32_t idx = addr >> 2;
    uint32_t offset = (addr % 4) * 8;
    ensure_memory(idx);
    uint8_t value = static_cast<uint8_t>((M[idx] >> offset) & 0xFF);
    trace_memory_access("READ", "BYTE", addr, value);
    return value;
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
    size_t loop_period = 0;
    if (detect_state_cycle(loop_period))
    {
        halted = true;
        std::cout << "Detected locked PC/state loop (period=" << loop_period
                  << "), halting at PC=0x" << std::hex << std::setw(8)
                  << std::setfill('0') << PC << std::dec << std::setfill(' ')
                  << std::endl;
        return;
    }
    trace_step_begin();
    auto inst = read_word(PC);
    trace_step_inst = inst;
    auto type = decoder.OpDecode(inst);
    auto imm = immGen.Generate(inst);
    auto rd = get_rd(inst);
    auto rs1 = get_rs1(inst);
    auto rs2 = get_rs2(inst);
    uint32_t NEXT_PC = PC + 4;
    bool should_advance_pc = true;
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
        should_advance_pc = false;
        if (R[REG_A0] == 0)
        {
            std::cout << "HIT GOOD TRAP" << std::endl;
        }
        else
        {
            std::cout << "HIT BAD TRAP" << std::endl;
        }
        break;
    }
    case Decoder::InstrType::INSTR_UNKNOWN:
    {
        std::cout << "Invalid instruction! Skipping..." << std::endl;
        break;
    }
    }
    R[0] = 0; // risc-v手册上写的是x0寄存器要恒为 0
    if (should_advance_pc)
    {
        PC = NEXT_PC;
    }
    trace_step_end();
}
bool minirvEMU::IsHalted() const
{
    return halted;
}
void minirvEMU::UpdateVGA()
{
    // TODO: VGA support
}
