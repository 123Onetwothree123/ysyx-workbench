#ifndef TOKEN_HPP
#define TOKEN_HPP
#include <cstddef>
#include <cstdint>
#include <string_view>
class token
{
private:
    enum class Kind
    {
        EndOfInput,   // EOF
        Number,       // 数字
        Register,     // 寄存器
        Plus,         // +
        Minus,        // -
        Star,         // *
        Slash,        // /
        Equal,        // ==
        NotEqual,     // !=
        LessEqual,    // <=
        LogicalAnd,   // &&
        LeftParen,    // (
        RightParen,   // )
        ReadMemory8,  // read8
        ReadMemory16, // read16
        ReadMemory32, // read32
    };
    token(Kind kind, std::string_view text, std::uint32_t value, std::size_t position); // 构造函数
    Kind kind{Kind::EndOfInput};                                                        // 词法单元类型
    std::string_view text;                                                              // 原始文本
    std::uint32_t value{0};                                                             // 只有Number是有效的
    std::size_t position{0};                                                            // 报错定位
public:
    token() = default;                                                                                   // 默认构造函数
    ~token() = default;                                                                                  // 析构函数
    static token MakeNumber(std::string_view text, std::uint32_t value, std::size_t position);           // 创建数字
    static token MakeRegister(std::string_view text, std::uint32_t RegisterIndex, std::size_t position); // 创建寄存器
    static token MakePlus(std::string_view text, std::size_t position);                                  // 创建加号
    static token MakeMinus(std::string_view text, std::size_t position);                                 // 创建减号
    static token MakeStar(std::size_t position);                                                         // 创建乘号
    static token MakeSlash(std::size_t position);                                                        // 创建除号
    static token MakeEqual(std::size_t position);                                                        // 创建等于
    static token MakeNotEqual(std::size_t position);                                                     // 创建不等于
    static token MakeLessEqual(std::size_t position);                                                    // 创建小于等于
    static token MakeLogicalAnd(std::size_t position);                                                   // 创建逻辑与
    static token MakeLeftParen(std::size_t position);                                                    // 创建左括号
    static token MakeRightParen(std::size_t position);                                                   // 创建右括号
    static token MakeEnd(std::size_t position);                                                          // 创建结束
    static token MakeReadMemory8(std::size_t position);                                                  // 创建read8
    static token MakeReadMemory16(std::size_t position);                                                 // 创建read16
    static token MakeReadMemory32(std::size_t position);                                                 // 创建read32
    [[nodiscard]] bool IsEndOfInput() const noexcept;                                                    // 是否为结束输入
    [[nodiscard]] bool IsNumber() const noexcept;                                                        // 是否为数字
    [[nodiscard]] bool IsRegister() const noexcept;                                                      // 是否为寄存器
    [[nodiscard]] bool IsPlus() const noexcept;                                                          // 是否为加号
    [[nodiscard]] bool IsMinus() const noexcept;                                                         // 是否为减号
    [[nodiscard]] bool IsStar() const noexcept;                                                          // 是否为乘号
    [[nodiscard]] bool IsSlash() const noexcept;                                                         // 是否为除号
    [[nodiscard]] bool IsEqual() const noexcept;                                                         // 是否为等于
    [[nodiscard]] bool IsNotEqual() const noexcept;                                                      // 是否为不等于
    [[nodiscard]] bool IsLessEqual() const noexcept;                                                     // 是否为小于等于
    [[nodiscard]] bool IsLogicalAnd() const noexcept;                                                    // 是否为逻辑与
    [[nodiscard]] bool IsLeftParen() const noexcept;                                                     // 是否为左括号
    [[nodiscard]] bool IsRightParen() const noexcept;                                                    // 是否为右括号
    [[nodiscard]] bool IsReadMemory8() const noexcept;                                                   // 是否为read8
    [[nodiscard]] bool IsReadMemory16() const noexcept;                                                  // 是否为read16
    [[nodiscard]] bool IsReadMemory32() const noexcept;                                                  // 是否为read32
    [[nodiscard]] bool IsReadMemory() const noexcept;                                                    // 是否为read8/read16/read32
    [[nodiscard]] bool IsBinaryOperator() const noexcept;                                                // 是否为二元运算符+ - * / == != <= &&
    [[nodiscard]] bool IsUnaryOperator() const noexcept;                                                 // 是否为一元运算符- *
    [[nodiscard]] bool IsOperator() const noexcept;                                                      // 是否为运算符，以上所有
    [[nodiscard]] bool IsParenthesis() const noexcept;                                                   // 是否为括号
    [[nodiscard]] int GetPrecedence() const noexcept;                                                    // 获取优先级，非运算符返回0
    [[nodiscard]] bool IsRightAssociative() const noexcept;                                              // 是否为右结合，仅一元- *为true
    [[nodiscard]] std::string_view GetText() const noexcept;                                             // 获取原始子串
    [[nodiscard]] std::uint32_t GetValue() const noexcept;                                               // 获取值，仅Number有效
    [[nodiscard]] std::size_t GetPosition() const noexcept;                                              // 获取报错定位
};
#endif
