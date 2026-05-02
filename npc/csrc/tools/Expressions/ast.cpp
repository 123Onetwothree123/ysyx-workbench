#include "ast.hpp"
#include "ExpressionError.hpp"
#include "Expressions.hpp"
ast::ast(std::unique_ptr<AstNode> InputRoot)
{
    root = std::move(InputRoot);
}
bool ast::IsEmpty() const noexcept
{
    return root == nullptr;
}
const AstNode *ast::GetRoot() const noexcept
{
    return root.get();
}
std::string ast::ToString() const
{
    if (IsEmpty())
    {
        return "empty";
    }
    return root->ToString();
}
std::uint32_t ast::Evaluate(const EvaluationContext &context) const
{
    if(IsEmpty()){
        throw ExpressionError("表达式都是空的，跑个卵子");
    }
    return root->Evaluate(context);
}
std::unique_ptr<AstNode> ast::ReleaseRoot() noexcept
{
    return std::move(root);
}