#ifndef AST_HPP
#define AST_HPP
#include "AstNode.hpp"
#include "BinaryOpNode.hpp"
#include "DereferenceNode.hpp"
#include "NumberNode.hpp"
#include "ParenthesizedNode.hpp"
#include "RegisterNode.hpp"
#include "UnaryMinusNode.hpp"
#include<memory>
#include<string>
class ast
{
private:
    std::unique_ptr<AstNode> root;
public:
    explicit ast(std::unique_ptr<AstNode> InputRoot);
    [[nodiscard]] std::uint32_t Evaluate(const EvaluationContext &context) const;
    [[nodiscard]] std::string ToString() const;
    [[nodiscard]] bool IsEmpty() const noexcept;
    //获取根节点
    [[nodiscard]] const AstNode* GetRoot() const noexcept;
    // 转移根节点所有权（移动语义）
    [[nodiscard]] std::unique_ptr<AstNode> ReleaseRoot() noexcept;
};
#endif