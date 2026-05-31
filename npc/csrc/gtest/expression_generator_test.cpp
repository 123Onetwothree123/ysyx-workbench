#include <array>
#include <cstddef>
#include <cstdint>
#include <format>
#include <random>
#include <string>
#include <string_view>

#include <gtest/gtest.h>

#include "tools/Expressions/EvaluationContext.hpp"
#include "tools/Expressions/ExpressionError.hpp"
#include "tools/Expressions/Expressions.hpp"
#include "tools/Expressions/RegisterName.hpp"

namespace {

constexpr std::uint32_t kMemoryBase{0x8000'0000u};
constexpr std::uint32_t kMemorySize{256u};
constexpr std::uint32_t kPcValue{0x8000'0040u};

struct GeneratedExpression {
    std::string text;
    std::uint32_t value{};
};

class FixedExpressionContext final : public EvaluationContext {
public:
    FixedExpressionContext() {
        for (std::size_t i{0}; i < registers_.size(); ++i) {
            registers_[i] = 0x1020'3000u + static_cast<std::uint32_t>(i * 0x1111u);
        }
        registers_[0] = 0;
        registers_[1] = kMemoryBase + 0x04u;
        registers_[2] = kMemoryBase + 0x20u;
        registers_[30] = kMemoryBase + 0x80u;
        registers_[31] = kMemoryBase + kMemorySize - 4u;

        for (std::size_t i{0}; i < memory_.size(); ++i) {
            memory_[i] = static_cast<std::uint8_t>((i * 37u + 0x5au) & 0xffu);
        }
    }

    [[nodiscard]] std::uint32_t ReadRegister(std::string_view name) const override {
        if (IsProgramCounterName(name)) {
            return kPcValue;
        }
        const auto index{RegisterNameToIndex(name)};
        if (index && *index < registers_.size()) {
            return registers_[*index];
        }
        throw ExpressionError(std::format("无效的寄存器名: {0}", name));
    }

    [[nodiscard]] std::uint32_t ReadMemory(const std::uint32_t address, const std::size_t size) const override {
        if (address < kMemoryBase || size == 0 || size > 4) {
            return 0;
        }

        const auto offset{static_cast<std::size_t>(address - kMemoryBase)};
        if (offset + size > memory_.size()) {
            return 0;
        }

        std::uint32_t value{0};
        for (std::size_t i{0}; i < size; ++i) {
            value |= static_cast<std::uint32_t>(memory_[offset + i]) << (i * 8u);
        }
        return value;
    }

    [[nodiscard]] std::uint32_t GetProgramCounter() const override {
        return kPcValue;
    }

    [[nodiscard]] std::uint32_t register_value(const std::size_t index) const {
        return registers_.at(index);
    }

    [[nodiscard]] std::uint32_t memory_value(const std::uint32_t address, const std::size_t size) const {
        return ReadMemory(address, size);
    }

private:
    std::array<std::uint32_t, 32> registers_{};
    std::array<std::uint8_t, kMemorySize> memory_{};
};

enum class BinaryOp {
    Add,
    Sub,
    Mul,
    Div,
    Equal,
    NotEqual,
    LessEqual,
    LogicalAnd,
};

class RandomExpressionGenerator {
public:
    explicit RandomExpressionGenerator(const FixedExpressionContext &context, const std::uint32_t seed)
        : context_(context), rng_(seed) {}

    [[nodiscard]] GeneratedExpression generate_nemu_style_expression(const int depth = 0) {
        if (depth >= 7) {
            return generate_number();
        }

        switch (pick(3)) {
        case 0:
            return generate_number();
        case 1:
            return parenthesize(generate_nemu_style_expression(depth + 1));
        default:
            return generate_binary(generate_nemu_style_expression(depth + 1), pick_nemu_style_op(),
                                   generate_nemu_style_expression(depth + 1));
        }
    }

    [[nodiscard]] GeneratedExpression generate_npc_expression(const int depth = 0) {
        if (depth >= 6) {
            return generate_leaf();
        }

        switch (pick(6)) {
        case 0:
            return generate_leaf();
        case 1:
            return parenthesize(generate_npc_expression(depth + 1));
        case 2:
            return generate_unary_minus(generate_npc_expression(depth + 1));
        case 3:
            return generate_memory_read();
        case 4:
            return generate_dereference();
        default:
            return generate_binary(generate_npc_expression(depth + 1), pick_npc_op(),
                                   generate_npc_expression(depth + 1));
        }
    }

private:
    [[nodiscard]] std::uint32_t pick(const std::uint32_t exclusive_upper) {
        std::uniform_int_distribution<std::uint32_t> dist(0, exclusive_upper - 1u);
        return dist(rng_);
    }

    [[nodiscard]] bool coin() {
        return pick(2) == 0;
    }

    [[nodiscard]] std::string maybe_space() {
        return coin() ? " " : "";
    }

    [[nodiscard]] GeneratedExpression generate_number() {
        const auto value{pick(101)};
        if (coin()) {
            return GeneratedExpression{.text = std::format("{0}0x{1:x}{2}", maybe_space(), value, maybe_space()),
                                       .value = value};
        }
        return GeneratedExpression{.text = std::format("{0}{1}{2}", maybe_space(), value, maybe_space()), .value = value};
    }

    [[nodiscard]] GeneratedExpression generate_register() {
        const auto index{pick(32)};
        return GeneratedExpression{.text = std::format("{0}x{1}{2}", maybe_space(), index, maybe_space()),
                                   .value = context_.register_value(index)};
    }

    [[nodiscard]] GeneratedExpression generate_pc() {
        return GeneratedExpression{.text = std::format("{0}pc{1}", maybe_space(), maybe_space()), .value = kPcValue};
    }

    [[nodiscard]] GeneratedExpression generate_leaf() {
        switch (pick(4)) {
        case 0:
        case 1:
            return generate_number();
        case 2:
            return generate_register();
        default:
            return generate_pc();
        }
    }

    [[nodiscard]] GeneratedExpression generate_memory_read() {
        const std::array<std::size_t, 3> sizes{1, 2, 4};
        const auto size{sizes[pick(static_cast<std::uint32_t>(sizes.size()))]};
        const auto address{generate_memory_address(size)};
        const auto value{context_.memory_value(address.value, size)};

        return GeneratedExpression{
            .text = std::format("{0}read{1}({2}{3}){4}", maybe_space(), size * 8u, address.text,
                                maybe_space(), maybe_space()),
            .value = value,
        };
    }

    [[nodiscard]] GeneratedExpression generate_dereference() {
        const auto address{generate_memory_address(4)};
        return GeneratedExpression{
            .text = std::format("{0}*({1}{2}){3}", maybe_space(), address.text, maybe_space(), maybe_space()),
            .value = context_.memory_value(address.value, 4),
        };
    }

    [[nodiscard]] GeneratedExpression generate_memory_address(const std::size_t size) {
        const auto max_offset{static_cast<std::uint32_t>(kMemorySize - size)};

        switch (pick(5)) {
        case 0:
            return generate_absolute_address(max_offset);
        case 1:
            return generate_base_plus_offset_address(max_offset);
        case 2:
            return generate_pc_relative_address(max_offset);
        case 3:
            return generate_register_address(max_offset);
        default:
            return generate_subtraction_address(max_offset);
        }
    }

    [[nodiscard]] GeneratedExpression generate_absolute_address(const std::uint32_t max_offset) {
        const auto offset{pick(max_offset + 1u)};
        const auto address{kMemoryBase + offset};
        return GeneratedExpression{.text = std::format("{0}0x{1:08x}{2}", maybe_space(), address, maybe_space()),
                                   .value = address};
    }

    [[nodiscard]] GeneratedExpression generate_base_plus_offset_address(const std::uint32_t max_offset) {
        const auto offset{pick(max_offset + 1u)};
        const auto address{kMemoryBase + offset};
        return GeneratedExpression{
            .text = std::format("{0}(0x{1:08x} + 0x{2:x}){3}", maybe_space(), kMemoryBase, offset, maybe_space()),
            .value = address,
        };
    }

    [[nodiscard]] GeneratedExpression generate_pc_relative_address(const std::uint32_t max_offset) {
        const auto pc_offset{kPcValue - kMemoryBase};
        if (coin()) {
            const auto max_delta{max_offset >= pc_offset ? max_offset - pc_offset : 0u};
            const auto delta{pick(max_delta + 1u)};
            if (delta == 0) {
                return generate_pc();
            }
            return GeneratedExpression{
                .text = std::format("{0}(pc + 0x{1:x}){2}", maybe_space(), delta, maybe_space()),
                .value = kPcValue + delta,
            };
        }

        const auto max_delta{pc_offset};
        const auto delta{pick(max_delta + 1u)};
        if (delta == 0) {
            return generate_pc();
        }
        return GeneratedExpression{
            .text = std::format("{0}(pc - 0x{1:x}){2}", maybe_space(), delta, maybe_space()),
            .value = kPcValue - delta,
        };
    }

    [[nodiscard]] GeneratedExpression generate_register_address(const std::uint32_t max_offset) {
        struct AddressRegister {
            std::string_view name;
            std::uint32_t value;
        };

        constexpr std::array<AddressRegister, 4> address_registers{{
            {.name = "x1", .value = kMemoryBase + 0x04u},
            {.name = "x2", .value = kMemoryBase + 0x20u},
            {.name = "x30", .value = kMemoryBase + 0x80u},
            {.name = "x31", .value = kMemoryBase + kMemorySize - 4u},
        }};

        const auto selected{address_registers[pick(static_cast<std::uint32_t>(address_registers.size()))]};
        const auto max_delta{kMemoryBase + max_offset - selected.value};
        const auto delta_limit{max_delta < 0x10u ? max_delta : 0x10u};
        const auto delta{pick(delta_limit + 1u)};
        if (delta == 0 || coin()) {
            return GeneratedExpression{
                .text = std::format("{0}{1}{2}", maybe_space(), selected.name, maybe_space()),
                .value = selected.value,
            };
        }

        return GeneratedExpression{
            .text = std::format("{0}({1} + 0x{2:x}){3}", maybe_space(), selected.name, delta, maybe_space()),
            .value = selected.value + delta,
        };
    }

    [[nodiscard]] GeneratedExpression generate_subtraction_address(const std::uint32_t max_offset) {
        const auto offset{pick(max_offset + 1u)};
        const auto extra{pick(0x20u)};
        const auto minuend{kMemoryBase + offset + extra};
        const auto address{kMemoryBase + offset};
        return GeneratedExpression{
            .text = std::format("{0}(0x{1:08x} - 0x{2:x}){3}", maybe_space(), minuend, extra, maybe_space()),
            .value = address,
        };
    }

    [[nodiscard]] GeneratedExpression parenthesize(GeneratedExpression expression) {
        expression.text = std::format("{0}({1}{2}){3}", maybe_space(), expression.text, maybe_space(), maybe_space());
        return expression;
    }

    [[nodiscard]] GeneratedExpression generate_unary_minus(const GeneratedExpression &operand) {
        return GeneratedExpression{.text = std::format("{0}-{1}", maybe_space(), operand.text),
                                   .value = std::uint32_t{0} - operand.value};
    }

    [[nodiscard]] BinaryOp pick_nemu_style_op() {
        switch (pick(4)) {
        case 0:
            return BinaryOp::Add;
        case 1:
            return BinaryOp::Sub;
        case 2:
            return BinaryOp::Mul;
        default:
            return BinaryOp::Div;
        }
    }

    [[nodiscard]] BinaryOp pick_npc_op() {
        switch (pick(8)) {
        case 0:
            return BinaryOp::Add;
        case 1:
            return BinaryOp::Sub;
        case 2:
            return BinaryOp::Mul;
        case 3:
            return BinaryOp::Div;
        case 4:
            return BinaryOp::Equal;
        case 5:
            return BinaryOp::NotEqual;
        case 6:
            return BinaryOp::LessEqual;
        default:
            return BinaryOp::LogicalAnd;
        }
    }

    [[nodiscard]] static std::string_view op_text(const BinaryOp op) {
        switch (op) {
        case BinaryOp::Add:
            return "+";
        case BinaryOp::Sub:
            return "-";
        case BinaryOp::Mul:
            return "*";
        case BinaryOp::Div:
            return "/";
        case BinaryOp::Equal:
            return "==";
        case BinaryOp::NotEqual:
            return "!=";
        case BinaryOp::LessEqual:
            return "<=";
        case BinaryOp::LogicalAnd:
            return "&&";
        }
        return "+";
    }

    [[nodiscard]] static std::uint32_t evaluate_binary(const std::uint32_t left, const BinaryOp op,
                                                       const std::uint32_t right) {
        switch (op) {
        case BinaryOp::Add:
            return left + right;
        case BinaryOp::Sub:
            return left - right;
        case BinaryOp::Mul:
            return left * right;
        case BinaryOp::Div:
            return left / right;
        case BinaryOp::Equal:
            return left == right;
        case BinaryOp::NotEqual:
            return left != right;
        case BinaryOp::LessEqual:
            return left <= right;
        case BinaryOp::LogicalAnd:
            return (left != 0u && right != 0u) ? 1u : 0u;
        }
        return 0;
    }

    [[nodiscard]] GeneratedExpression generate_binary(GeneratedExpression left, const BinaryOp op,
                                                      GeneratedExpression right) {
        if (op == BinaryOp::Div && right.value == 0) {
            right = GeneratedExpression{.text = "1", .value = 1};
        }

        return GeneratedExpression{
            .text = std::format("{0}({1}{2}{3}{4}{5}){6}", maybe_space(), left.text, maybe_space(), op_text(op),
                                maybe_space(), right.text, maybe_space()),
            .value = evaluate_binary(left.value, op, right.value),
        };
    }

    const FixedExpressionContext &context_;
    std::mt19937 rng_;
};

void expect_expression_value(std::string_view expression, const std::uint32_t expected,
                             const EvaluationContext &context) {
    Expressions expressions;
    const auto result{expressions.Evaluate(expression, context)};
    ASSERT_TRUE(result) << "expression: " << expression << "\nerror: " << result.error();
    EXPECT_EQ(*result, expected) << "expression: " << expression;
}

TEST(ExpressionGeneratorTest, NemuStyleRandomArithmeticMatchesReferenceValues) {
    FixedExpressionContext context;
    RandomExpressionGenerator generator(context, 0x4e454d55u);

    for (int i{0}; i < 512; ++i) {
        const auto expression{generator.generate_nemu_style_expression()};
        expect_expression_value(expression.text, expression.value, context);
    }
}

TEST(ExpressionGeneratorTest, NpcRandomExpressionsMatchReferenceValues) {
    FixedExpressionContext context;
    RandomExpressionGenerator generator(context, 0x4e504345u);

    for (int i{0}; i < 1024; ++i) {
        const auto expression{generator.generate_npc_expression()};
        expect_expression_value(expression.text, expression.value, context);
    }
}

TEST(ExpressionGeneratorTest, OperatorPrecedenceAssociativityAndShortCircuitingWork) {
    FixedExpressionContext context;

    expect_expression_value("1 + 2 * 3", 7, context);
    expect_expression_value("(1 + 2) * 3", 9, context);
    expect_expression_value("10 - 3 - 2", 5, context);
    expect_expression_value("20 / 5 / 2", 2, context);
    expect_expression_value("1 - -2 + 3", 6, context);
    expect_expression_value("1 == 1 && 2 <= 3", 1, context);
    expect_expression_value("0 && (1 / (1 - 1))", 0, context);
    expect_expression_value("-0x80000000", 0x8000'0000u, context);
}

TEST(ExpressionGeneratorTest, RegisterAndMemoryAtomsUseEvaluationContext) {
    FixedExpressionContext context;

    for (std::size_t index{0}; index < 32; ++index) {
        expect_expression_value(std::format("x{0}", index), context.register_value(index), context);
    }
    expect_expression_value("x3 + pc", context.register_value(3) + kPcValue, context);
    expect_expression_value("read8(0x80000003)", context.memory_value(kMemoryBase + 3, 1), context);
    expect_expression_value("read16(0x80000004)", context.memory_value(kMemoryBase + 4, 2), context);
    expect_expression_value("read32(0x80000008)", context.memory_value(kMemoryBase + 8, 4), context);
    expect_expression_value("*(0x80000008)", context.memory_value(kMemoryBase + 8, 4), context);
    expect_expression_value("read32(x1 + 4)", context.memory_value(context.register_value(1) + 4, 4), context);
    expect_expression_value("read16(pc - 0x20)", context.memory_value(kPcValue - 0x20u, 2), context);
    expect_expression_value("read8(0x80000000 + 5)", context.memory_value(kMemoryBase + 5, 1), context);
    expect_expression_value("*(x2 + 4)", context.memory_value(context.register_value(2) + 4, 4), context);
}

TEST(ExpressionGeneratorTest, RegisterAliasesAndDollarPrefixWork) {
    FixedExpressionContext context;

    expect_expression_value("ra", context.register_value(1), context);
    expect_expression_value("sp + 4", context.register_value(2) + 4, context);
    expect_expression_value("a0 == x10", 1, context);
    expect_expression_value("fp == s0", 1, context);
    expect_expression_value("$pc", kPcValue, context);
    expect_expression_value("$sp + $a0", context.register_value(2) + context.register_value(10), context);
    expect_expression_value("$x2", context.register_value(2), context);
    expect_expression_value("$0", context.register_value(0), context);
}

TEST(ExpressionGeneratorTest, InvalidRegisterNamesAreReported) {
    FixedExpressionContext context;
    Expressions expressions;

    EXPECT_FALSE(expressions.Evaluate("not_a_reg + 1", context));
    EXPECT_FALSE(expressions.Evaluate("$", context));
    EXPECT_FALSE(expressions.Evaluate("$x32", context));
}

TEST(ExpressionGeneratorTest, DivisionByZeroIsReportedAsEvaluationFailure) {
    FixedExpressionContext context;
    Expressions expressions;

    const auto result{expressions.Evaluate("1 / (2 - 2)", context)};

    EXPECT_FALSE(result);
}

TEST(ExpressionGeneratorTest, EqualityOperatorsWorkCorrectly) {
    FixedExpressionContext context;

    expect_expression_value("1 == 1", 1, context);
    expect_expression_value("1 == 0", 0, context);
    expect_expression_value("1 != 0", 1, context);
    expect_expression_value("1 != 1", 0, context);
    expect_expression_value("42 == 42", 1, context);
}

TEST(ExpressionGeneratorTest, LessThanOrEqualWorksCorrectly) {
    FixedExpressionContext context;

    expect_expression_value("1 <= 2", 1, context);
    expect_expression_value("2 <= 1", 0, context);
    expect_expression_value("5 <= 5", 1, context);
}

TEST(ExpressionGeneratorTest, LogicalAndShortCircuitsCorrectly) {
    FixedExpressionContext context;

    expect_expression_value("1 && 1", 1, context);
    expect_expression_value("1 && 0", 0, context);
    expect_expression_value("0 && 1", 0, context);
    expect_expression_value("0 && 0", 0, context);
    expect_expression_value("2 && 3", 1, context);
    expect_expression_value("0 && 0x5000", 0, context);
}

TEST(ExpressionGeneratorTest, ChainedBinaryOperatorsMaintainPrecedence) {
    FixedExpressionContext context;

    expect_expression_value("2 + 3 * 4", 14, context);
    expect_expression_value("2 * 3 + 4", 10, context);
    expect_expression_value("10 - 3 - 2", 5, context);
    expect_expression_value("20 / 5 / 2", 2, context);
    expect_expression_value("1 + 2 * 3 + 4 * 5", 27, context);
}

TEST(ExpressionGeneratorTest, ParenthesizedExpressionOverridesPrecedence) {
    FixedExpressionContext context;

    expect_expression_value("(1 + 2) * 3", 9, context);
    expect_expression_value("1 + (2 * 3)", 7, context);
    expect_expression_value("((1 + 2)) * (3 + 4)", 21, context);
    expect_expression_value("(10 - 5) * (3 + 1)", 20, context);
    expect_expression_value("(100 / (10 - 5))", 20, context);
}

TEST(ExpressionGeneratorTest, UnaryMinusHandlesComplexExpressions) {
    FixedExpressionContext context;

    expect_expression_value("-5", 0xffff'fffbu, context);
    expect_expression_value("--5", 5, context);
    expect_expression_value("---5", 0xffff'fffbu, context);
    expect_expression_value("-(-5)", 5, context);
    expect_expression_value("1 + -2", 0xffff'ffffu, context);
    expect_expression_value("-1 * -2", 2, context);
}

TEST(ExpressionGeneratorTest, DereferenceReadsMemoryByAddress) {
    FixedExpressionContext context;

    expect_expression_value("*(0x80000000)", context.memory_value(0x80000000, 4), context);
    expect_expression_value("*(0x80000008)", context.memory_value(0x80000008, 4), context);
    expect_expression_value("*(0x80000000 + 4)", context.memory_value(0x80000004, 4), context);
}

TEST(ExpressionGeneratorTest, ReadMemoryFunctionsHandleAllWidths) {
    FixedExpressionContext context;

    expect_expression_value("read8(0x80000005)", context.memory_value(0x80000005, 1), context);
    expect_expression_value("read16(0x80000006)", context.memory_value(0x80000006, 2), context);
    expect_expression_value("read32(0x80000008)", context.memory_value(0x80000008, 4), context);
}

TEST(ExpressionGeneratorTest, ReadMemoryWithComplexAddressExpression) {
    FixedExpressionContext context;

    expect_expression_value("read32(0x80000000 + 16)", context.memory_value(0x80000010, 4), context);
    expect_expression_value("read32(0x80000020 - 8)", context.memory_value(0x80000018, 4), context);
    expect_expression_value("read16(pc + 4)", context.memory_value(kPcValue + 4, 2), context);
}

TEST(ExpressionGeneratorTest, ExpressionsInterleavesRegistersNumbersAndMemory) {
    FixedExpressionContext context;

    expect_expression_value("x1 + 10", context.register_value(1) + 10, context);
    expect_expression_value("x2 + x3", context.register_value(2) + context.register_value(3), context);
    expect_expression_value("x1 + read32(0x80000000)", context.register_value(1) + context.memory_value(0x80000000, 4), context);
}

TEST(ExpressionGeneratorTest, LargeNumberHandling) {
    FixedExpressionContext context;

    expect_expression_value("0xFFFFFFFF", 0xFFFFFFFFu, context);
    expect_expression_value("0x80000000", 0x80000000u, context);
    expect_expression_value("-0x80000000", 0x80000000u, context);
}

TEST(ExpressionGeneratorTest, ExpressionValidationReportsInvalidSyntax) {
    Expressions expressions;
    FixedExpressionContext context;

    EXPECT_FALSE(expressions.Evaluate("", context));
    EXPECT_FALSE(expressions.Evaluate("1 +", context));
}

TEST(ExpressionGeneratorTest, ValidateMethodDetectsBalancedParentheses) {
    Expressions expressions;

    EXPECT_TRUE(expressions.Validate("(1 + 2)"));
    EXPECT_TRUE(expressions.Validate("1 + 2"));
    EXPECT_FALSE(expressions.Validate("(1 + 2"));
    EXPECT_FALSE(expressions.Validate("1 + 2)"));
    EXPECT_FALSE(expressions.Validate(""));
}

TEST(ExpressionGeneratorTest, MixComparisonAndArithmeticOperators) {
    FixedExpressionContext context;

    expect_expression_value("(1 == 1) * 42", 42, context);
    expect_expression_value("(5 <= 3) + (7 == 7)", 1, context);
    expect_expression_value("(10 <= 20) && (30 == 30)", 1, context);
}

TEST(ExpressionGeneratorTest, MemoryReadAtExtremeBoundaries) {
    FixedExpressionContext context;

    expect_expression_value("read8(0x80000000)", context.memory_value(0x80000000, 1), context);
    expect_expression_value("read32(0x800000fc)", context.memory_value(0x800000fc, 4), context);
}

}  // namespace
