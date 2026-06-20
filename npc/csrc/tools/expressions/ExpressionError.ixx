export module npc.expressions.ExpressionError;
import std;

export class ExpressionError final : public std::exception
{
public:
    ExpressionError(std::string InputMessage);
    [[nodiscard]] const char *what() const noexcept override;
private:
    std::string message;
};
