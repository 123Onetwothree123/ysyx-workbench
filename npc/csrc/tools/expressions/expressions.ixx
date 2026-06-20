export module npc.expressions.expressions;
import std;
import npc.sdb.EvaluationContext;

export class expressions
{
public:
    expressions() = default;
    [[nodiscard]] std::expected<std::uint32_t, std::string> evaluate(std::string_view expression, const EvaluationContext &context);
    [[nodiscard]] bool validate(std::string_view expression);
};
