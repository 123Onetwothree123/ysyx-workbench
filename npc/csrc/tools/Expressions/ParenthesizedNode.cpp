#include "ParenthesizedNode.hpp"
ParenthesizedNode::ParenthesizedNode(std::unique_ptr<AstNode> inner)
{
    this->inner = std::move(inner);
}
std::uint32_t ParenthesizedNode::Evaluate(const EvaluationContext &context) const
{
    return inner->Evaluate(context);
}
std::string ParenthesizedNode::ToString() const
{
    return std::format("({})", inner->ToString());
}