export module npc.trace.iringbuf;
import std;
import npc.config;
import npc.trace.RecordInstruction;

export class iringbuf
{
public:
    static constexpr std::size_t IRINGBUF_SIZE{npc::IRingBufSize};
private:
    std::array<RecordInstruction, IRINGBUF_SIZE> buffer{};
    std::size_t head{0};
    std::size_t count{0};
public:
    iringbuf() noexcept;
    ~iringbuf() noexcept;
    [[nodiscard]] bool empty() const;
    [[nodiscard]] bool full() const;
    [[nodiscard]] std::size_t size() const;
    [[nodiscard]] std::size_t capacity() const;
    void push(std::uint64_t pc, std::uint32_t instruction, int len);
    void print(std::uint64_t ErrorPC) const;
};
