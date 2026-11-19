export module RAS;
import std;
import BranchRecord;
// call 压入 pc+4，ret 弹出；循环缓冲，满覆盖最旧，空栈返回 nullopt。
export class RAS
{
private:
    std::vector<Addr> buf;
    std::size_t top{0};
    std::size_t count{0};
public:
    RAS() = default;
    explicit RAS(std::size_t bits) : buf(std::size_t{1} << bits) {}
    void push(Addr addr)
    {
        buf[top] = addr;
        top = (top + 1) % buf.size();
        if (count < buf.size())
        {
            ++count;
        }
    }
    std::optional<Addr> pop()
    {
        if (count == 0)
        {
            return std::nullopt;
        }
        top = (top + buf.size() - 1) % buf.size();
        --count;
        return buf[top];
    }
    [[nodiscard]] std::optional<Addr> top_addr() const
    {
        if (count == 0)
        {
            return std::nullopt;
        }
        return buf[(top + buf.size() - 1) % buf.size()];
    }
};
