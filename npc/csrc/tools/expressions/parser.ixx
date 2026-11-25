export module npc.expressions.parser;
import std;
import npc.expressions.token;
import npc.expressions.ASTNode;

export class parser
{
private:
    std::vector<token> tokens;
    std::size_t position{0};
    std::size_t depth{0}; // 递归深度，防止栈溢出
    static constexpr std::size_t MaxDepth{256};
    [[nodiscard]] const token &Current() const;
    const token &Advance() noexcept;
    // 返回二元运算符优先级；非运算符返回 0。
    [[nodiscard]] int GetCurrentPrecedence() const noexcept;
    // MinPrecedence 为当前表达式允许的最小优先级。
    std::expected<std::unique_ptr<ASTNode>, std::string> ParseExpression(int MinPrecedence);
    std::expected<std::unique_ptr<ASTNode>, std::string> ParseUnary();
    std::expected<std::unique_ptr<ASTNode>, std::string> ParsePrimary();
public:
    explicit parser(std::vector<token> tokens);
    [[nodiscard]] std::expected<std::unique_ptr<ASTNode>, std::string> parse();
};
