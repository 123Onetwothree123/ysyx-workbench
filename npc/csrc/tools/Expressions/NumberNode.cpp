#include"NumberNode.hpp"
NumberNode::NumberNode(std::uint32_t value)
{
    this->value = value;
}
std::uint32_t NumberNode::Evaluate(const EvaluationContext &context) const
{
    return value;
}
std::string NumberNode::ToString() const
{
    return std::to_string(value);
}