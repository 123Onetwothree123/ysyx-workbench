#ifndef DREREFERENCE_NODE_HPP
#define DREREFERENCE_NODE_HPP
#include "AstNode.hpp"
#include <cstddef>
#include <memory>
class DereferenceNode : public AstNode
{
private:
    std::unique_ptr<AstNode> address;
    std::size_t size{4}; // 默认4字节
public:
    DereferenceNode(std::unique_ptr<AstNode> address, std::size_t size);
    [[nodiscard]] std::uint32_t Evaluate(const EvaluationContext &context) const override;
    [[nodiscard]] std::string ToString() const override;
};
#endif