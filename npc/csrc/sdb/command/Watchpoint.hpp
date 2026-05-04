#ifndef WATCH_POINT_HPP
#define WATCH_POINT_HPP
#include <stdint.h>
#include <string>
#include <print>
class Watchpoint
{
private:
    std::size_t NO {0};       // 编号
    bool enabled {false};     // 是否启用
    std::size_t PC {0};       // 监视点的CPU地址
    bool HasPC {false};       // 表示目前PC是否有效
    std::string expression;   // 监视点的表达式字符串
    std::size_t OldValue {0}; // 上一次监视点被触发时的值
public:
    Watchpoint() = default;
    Watchpoint(std::size_t NO, bool enabled, std::size_t PC, bool HasPC);
    ~Watchpoint() = default;
    std::size_t GetNO() const noexcept;
    bool IsEnabled() const noexcept;
    std::size_t GetPC() const noexcept;
    bool HasValidPC() const noexcept;
    void SetNO(std::size_t NO) noexcept;
    void SetEnabled(bool enabled) noexcept;
    void SetPC(std::size_t PC) noexcept;
    void SetHasPC(bool HasPC) noexcept;
    const std::string &GetExpression() const noexcept;
    void SetExpression(const std::string &expression) noexcept;
    std::size_t GetOldValue() const noexcept;
    void SetOldValue(std::size_t OldValue) noexcept;
};
#endif
