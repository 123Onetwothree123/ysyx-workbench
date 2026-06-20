module npc.expressions.expressions;
import npc.expressions.lexer;
import npc.expressions.parser;
std::expected<std::uint32_t, std::string> expressions::evaluate(std::string_view expression, const EvaluationContext &context)
{
    lexer lex{expression};
    auto tokensResult{lex.ScanAll()};
    if (!tokensResult)
    {
        return std::unexpected(tokensResult.error());
    }
    parser p{std::move(*tokensResult)};
    auto astResult{p.parse()};
    if (!astResult)
    {
        return std::unexpected(astResult.error());
    }
    try
    {
        return (*astResult)->Evaluate(context);
    }
    catch (const std::exception &e)
    {
        return std::unexpected(std::string{e.what()});
    }
}
bool expressions::validate(std::string_view expression)
{
    lexer lex{expression};
    auto tokensResult{lex.ScanAll()};
    if (!tokensResult)
    {
        return false;
    }
    parser p{std::move(*tokensResult)};
    auto astResult{p.parse()};
    return static_cast<bool>(astResult);
}
