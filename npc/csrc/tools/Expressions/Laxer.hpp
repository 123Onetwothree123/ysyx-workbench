#ifndef LEXER_HPP
#define LEXER_HPP
#include "token.hpp"
#include <string_view>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>
#include <expected>
class Laxer
{
private:
    std::string_view input;
    std::size_t position{0}; // 现在扫描到的字符的位置
    std::string error;
    void SkipWhitespace();              // 跳空白
    bool IsAtEnd() const noexcept;      // 扫描完了
    char Peek() const noexcept;         // 看当前字符
    char Advance() noexcept;            // 读现在的字符并且position向前进一位
    bool Match(char expected) noexcept; // 如果当前字符是expected就读，并且返回true，否则返回false
    token ScanNumber();                 // 10
    token ScanHexNumber();              // 16
    token ScanRegister();
    token ScanOperator();

public:
    Laxer() = default;
    ~Laxer() = default;
    [[nodiscard]] bool HasError() const noexcept;        // 是否有错误
    [[nodiscard]] std::string GetError() const noexcept; // 获取错误信息
    // 构造函数，本来是直接写的，然后让ai也检查过了，然后建议改成explicit，可以避免意外用string构造Lexer
    explicit Laxer(std::string_view input);
    [[nodiscard]] std::expected<token, std::string> scan();                 // 扫描单个token，如果成功返回token，错了就是失败的字符串
    [[nodiscard]] std::expected<std::vector<token>, std::string> ScanAll(); // 扫描全部token，返回token列表
    token next();                                                           // 一个token一个token的拉，每次都是返回下一个token，然后指针向前走一点
};
#endif
