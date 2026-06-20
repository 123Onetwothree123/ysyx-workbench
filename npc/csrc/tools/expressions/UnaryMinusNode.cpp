module npc.expressions.UnaryMinusNode;
UnaryMinusNode::UnaryMinusNode(std::unique_ptr<ASTNode> child)
{
    this->child = std::move(child);
}
std::uint32_t UnaryMinusNode::Evaluate(const EvaluationContext &context) const
{
    return std::uint32_t{0} - child->Evaluate(context);
}
std::string UnaryMinusNode::ToString() const
{
    return std::format("(-{0})", child->ToString());
}
