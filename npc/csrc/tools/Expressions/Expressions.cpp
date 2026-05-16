#include "Expressions.hpp"
#include "Laxer.hpp"
#include "parser.hpp"
#include "ast.hpp"
std::expected<std::unique_ptr<AstNode>, std::string> Expressions::Parse(std::string_view expressions)
{
    Laxer laxer(expressions);
    auto TokensResult{laxer.ScanAll()};// 扫描token
    if (!TokensResult)
    {
        return std::unexpected(TokensResult.error());
    }
    parser Parser(TokensResult.value());// 解析token
    auto AstResult{Parser.parse()};// 生成AST
    if (!AstResult)
    {
        return std::unexpected(AstResult.error());
    }
    return std::move(AstResult.value());// 返回AST
}
bool Expressions::Validate(std::string_view expression)
{
    return static_cast<bool>(Parse(expression));
}
std::expected<std::uint32_t, std::string> Expressions::Evaluate(std::string_view expression, const EvaluationContext &context)
{
    auto AstResult{Parse(expression)};// 先解析成AST
    if (!AstResult)
    {
        return std::unexpected(AstResult.error());
    }
    ast Ast(std::move(AstResult.value()));// 生成AST
    try
    {
        return Ast.Evaluate(context);// 求值
    }
    catch (const std::exception &error)
    {
        return std::unexpected(error.what());
    }
}