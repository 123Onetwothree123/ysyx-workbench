export module minirvemu.emu;
import std;
import minirvemu.Decoder;
import minirvemu.ImmGen;

export class minirvEMU
{
private:
    std::uint32_t PC{0};
    std::array<std::uint32_t, 16> R{};
    std::vector<std::uint32_t> M;
    Decoder decoder;
    ImmGen immGen;
    bool halted{false};
    struct MemoryTraceEntry
    {
        const char *access;
        const char *width;
        std::uint32_t physical_addr_shifted;
        std::uint32_t virtual_addr_raw;
        std::uint32_t value;
    };
    bool trace_enabled{false};
    bool trace_step_active{false};
    std::uint64_t trace_step_counter{0};
    std::uint32_t trace_step_pc{0};
    std::uint32_t trace_step_inst{0};
    std::ofstream trace_stream;
    std::vector<MemoryTraceEntry> trace_memory_entries;
    std::deque<std::uint64_t> state_signature_history;
    void ensure_memory(std::uint32_t word_idx);
    static std::uint8_t get_rd(std::uint32_t inst);
    static std::uint8_t get_rs1(std::uint32_t inst);
    static std::uint8_t get_rs2(std::uint32_t inst);
    void trace_memory_access(const char *access, const char *width, std::uint32_t virtual_addr_raw, std::uint32_t value);
    void trace_step_begin();
    void trace_step_end();
    std::uint64_t build_state_signature() const;
    bool detect_state_cycle(std::size_t &period);
    static constexpr std::size_t LOOP_MAX_PERIOD = 8;
    static constexpr std::size_t LOOP_REPEAT_TIMES = 4;
    static constexpr std::size_t LOOP_HISTORY_LIMIT = LOOP_MAX_PERIOD * LOOP_REPEAT_TIMES * 4;

public:
    minirvEMU();
    ~minirvEMU() = default;
    void reset();
    std::uint32_t GetPC() const;
    void SetPC(std::uint32_t value);
    std::uint32_t GetRegister(std::size_t index) const;
    void SetRegister(std::size_t index, std::uint32_t value);
    std::uint32_t GetMemory(std::size_t address) const;
    void SetMemory(std::size_t address, std::uint32_t value);
    std::size_t GetMemorySize() const;
    std::size_t GetRegisterCount() const;
    void IncrementPC();
    void LoadProgram(const std::vector<std::uint32_t> &program);
    void LoadProgram(const std::initializer_list<std::uint32_t> &program);
    bool EnableTrace(const std::filesystem::path &trace_file_path);
    void DisableTrace();
    void PrintState() const;
    minirvEMU(const minirvEMU &) = delete;
    minirvEMU &operator=(const minirvEMU &) = delete;
    void write_word(std::uint32_t addr, std::uint32_t value);
    std::uint32_t read_word(std::uint32_t addr);
    void write_byte(std::uint32_t addr, std::uint8_t value);
    std::uint8_t read_byte(std::uint32_t addr);
    void step();
    bool IsHalted() const;
    static constexpr int REG_A0 = 10;
    void UpdateVGA();
};
