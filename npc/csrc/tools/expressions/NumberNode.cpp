module npc.expressions.NumberNode;
NumberNode::NumberNode(std::uint64_t value)
{
    this->value = value;
}
std::uint64_t NumberNode::Evaluate(const EvaluationContext &context) const
{
    return value;
}
std::string NumberNode::ToString() const
{
    return std::to_string(value);
}
