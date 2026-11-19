module;
export module npc.UartController;
import std;
export namespace npc
{
struct UartInput
{
    bool valid{false};
    std::uint8_t data{0};
};
class UartController
{
public:
    // 仅 SDB c 模式或非 SDB 主循环启用。
    void EnableStdin(bool enable);
    void Reset();
    // 返回本拍驱动到 uart_rx 的字节；非 direct-NPC 构建为空。
    [[nodiscard]] UartInput PumpInput(bool fifo_ready);
    // 记录 TX 字节并与 marker 比对；命中返回 true。
    bool FeedTx(std::uint8_t byte, std::string_view marker);
    [[nodiscard]] std::string_view TxBuffer() const noexcept;
    void ClearTxBuffer();
private:
    bool stdin_enabled_{false};
    bool stdin_eof_{false};
    bool input_pending_{false};
    std::uint8_t input_byte_{0};
    std::size_t poll_countdown_{0};
    std::string tx_buffer_;
};
}
