module npc.expressions.ParenthesizedNode;
ParenthesizedNode::ParenthesizedNode(std::unique_ptr<ASTNode> inner)
{
    this->inner = std::move(inner);
}
std::uint32_t ParenthesizedNode::Evaluate(const EvaluationContext &context) const
{
    return inner->Evaluate(context);
}
std::string ParenthesizedNode::ToString() const
{
    return std::format("({0})", inner->ToString());
}
