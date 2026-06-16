#include "BinaryOpNode.hpp"
#include "ExpressionError.hpp"
BinaryOpNode::BinaryOpNode(token Token, std::unique_ptr<ASTNode> Left, std::unique_ptr<ASTNode> Right)
{
    this->Token = Token;
    this->Left = std::move(Left);
    this->Right = std::move(Right);
}
std::uint32_t BinaryOpNode::Evaluate(const EvaluationContext &context) const
{
    // 逻辑与
    if (Token.IsLogicalAnd())
    {
        const auto LeftValue{Left->Evaluate(context)};
        if (LeftValue == 0)
        {
            return 0;
        }
        // 右边不需要求值了，直接看结果就行了
        return Right->Evaluate(context) != 0;
    }
    const auto LeftValue{Left->Evaluate(context)};
    const auto RightValue{Right->Evaluate(context)};
    if (Token.IsPlus())
    {
        return LeftValue + RightValue;
    }
    if (Token.IsMinus())
    {
        return LeftValue - RightValue;
    }
    if (Token.IsStar())
    {
        return LeftValue * RightValue;
    }
    if (Token.IsSlash())
    {
        if (RightValue == 0)
        {
            throw ExpressionError("除数不能为零");
        }
        return LeftValue / RightValue;
    }
    if (Token.IsEqual())
    {
        return LeftValue == RightValue;
    }
    if (Token.IsNotEqual())
    {
        return LeftValue != RightValue;
    }
    if (Token.IsLessEqual())
    {
        return static_cast<std::uint32_t>(LeftValue) <= static_cast<std::uint32_t>(RightValue);
    }
    throw ExpressionError("不支持的二元运算符");
}
std::string BinaryOpNode::ToString() const
{
    return std::format("({0} {1} {2})", Left->ToString(), Token.GetText(), Right->ToString());
}
