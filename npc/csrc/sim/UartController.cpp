module;
#ifdef VRISCV32E_NPC
#include <cerrno>
#include <poll.h>
#include <unistd.h>
#endif
module npc.UartController;
import std;
namespace npc
{
void UartController::EnableStdin(bool enable)
{
    stdin_enabled_ = enable;
    stdin_eof_ = false;
}
void UartController::Reset()
{
    input_pending_ = false;
    poll_countdown_ = 0;
}
UartInput UartController::PumpInput(bool fifo_ready)
{
#ifdef VRISCV32E_NPC
    UartInput input{};
    if (!stdin_enabled_ || stdin_eof_)
    {
        return input;
    }
    // FIFO 满时不读 stdin，字节留在管道，不丢字符且遵守流控。
    if (!fifo_ready)
    {
        return input;
    }
    if (!input_pending_)
    {
        // 每 256 拍 poll 一次，避免逐拍系统调用。
        if (poll_countdown_ != 0)
        {
            --poll_countdown_;
            return input;
        }
        poll_countdown_ = 255;
        pollfd descriptor{.fd = STDIN_FILENO, .events = POLLIN, .revents = 0};
        const int ready = ::poll(&descriptor, 1, 0);
        if (ready <= 0)
        {
            return input;
        }
        if ((descriptor.revents & (POLLHUP | POLLERR | POLLNVAL)) != 0 &&
            (descriptor.revents & POLLIN) == 0)
        {
            stdin_eof_ = true;
            return input;
        }
        char byte{};
        const ssize_t count = ::read(STDIN_FILENO, &byte, 1);
        if (count == 0)
        {
            stdin_eof_ = true;
            return input;
        }
        if (count < 0)
        {
            if (errno == EAGAIN || errno == EINTR)
            {
                return input;
            }
            stdin_eof_ = true;
            return input;
        }
        input_byte_ = static_cast<std::uint8_t>(byte);
        input_pending_ = true;
    }
    input.valid = true;
    input.data = input_byte_;
    input_pending_ = false;
    return input;
#else
    static_cast<void>(fifo_ready);
    return {};
#endif
}
bool UartController::FeedTx(std::uint8_t byte, std::string_view marker)
{
#ifdef VRISCV32E_NPC
    if (marker.empty())
    {
        return false;
    }
    // 仅保留 marker 长度的字符。
    tx_buffer_.push_back(static_cast<char>(byte));
    if (tx_buffer_.size() > marker.size())
    {
        tx_buffer_.erase(0, 1);
    }
    if (tx_buffer_ == marker)
    {
        tx_buffer_.clear();
        return true;
    }
    return false;
#else
    static_cast<void>(byte);
    static_cast<void>(marker);
    return false;
#endif
}
std::string_view UartController::TxBuffer() const noexcept
{
    return tx_buffer_;
}
void UartController::ClearTxBuffer()
{
    tx_buffer_.clear();
}
}
