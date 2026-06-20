module npc.expressions.RegisterNode;
RegisterNode::RegisterNode(std::string name)
{
    this->name = std::move(name);
}
std::uint32_t RegisterNode::Evaluate(const EvaluationContext &context) const
{
    return context.ReadRegister(name);
}
std::string RegisterNode::ToString() const
{
    return name;
}
