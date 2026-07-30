export module RAS;
import std;
// 返回地址栈(Return Address Stack): call压入pc+4, ret弹出
// 循环缓冲, 满了覆盖最旧(深度溢出只影响超深嵌套), 空栈弹出返回空(此时无法预测ret)
export class RAS
{
private:
    std::vector<std::uint32_t> buf;
    std::size_t top{0};   // 下一个写入位置
    std::size_t count{0};
public:
    RAS() = default;
    explicit RAS(std::size_t bits) : buf(std::size_t{1} << bits) {}
    void push(std::uint32_t addr)
    {
        buf[top] = addr;
        top = (top + 1) % buf.size();
        if (count < buf.size())
        {
            ++count;
        } // 已满则覆盖最旧, count不变
    }
    std::optional<std::uint32_t> pop()
    {
        if (count == 0)
        {
            return std::nullopt;
        }
        top = (top + buf.size() - 1) % buf.size();
        --count;
        return buf[top];
    }
    [[nodiscard]] std::optional<std::uint32_t> top_addr() const
    {
        if (count == 0)
        {
            return std::nullopt;
        }
        return buf[(top + buf.size() - 1) % buf.size()];
    }
};
