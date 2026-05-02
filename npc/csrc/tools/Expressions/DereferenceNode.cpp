#include "DereferenceNode.hpp"
#include <format>
DereferenceNode::DereferenceNode(std::unique_ptr<AstNode> address, std::size_t size)
{
    this->address = std::move(address);
    this->size = size;
}
std::uint32_t DereferenceNode::Evaluate(const EvaluationContext &context) const
{
    auto Address {address->Evaluate(context)};
    return context.ReadMemory(Address, size);// 从内存中读取值
}
std::string DereferenceNode::ToString() const
{
    if (size == 1)
    {
        return std::format("read8({})", address->ToString());
    }
    if (size == 2)
    {
        return std::format("read16({})", address->ToString());
    }
    return std::format("read32({})", address->ToString());
}