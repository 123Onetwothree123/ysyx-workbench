#include <cstdint>
#include <string_view>

#include <gtest/gtest.h>

#include "tools/Expressions/token.hpp"

namespace {

TEST(TokenTest, NumberTokenFactoryAndAccessors) {
    const auto t{token::MakeNumber("42", 42, 0)};
    EXPECT_TRUE(t.IsNumber());
    EXPECT_FALSE(t.IsRegister());
    EXPECT_FALSE(t.IsPlus());
    EXPECT_FALSE(t.IsMinus());
    EXPECT_FALSE(t.IsStar());
    EXPECT_FALSE(t.IsSlash());
    EXPECT_FALSE(t.IsEndOfInput());
    EXPECT_FALSE(t.IsLeftParen());
    EXPECT_FALSE(t.IsRightParen());
    EXPECT_FALSE(t.IsOperator());
    EXPECT_FALSE(t.IsParenthesis());
    EXPECT_EQ(t.GetValue(), 42u);
    EXPECT_EQ(t.GetText(), "42");
    EXPECT_EQ(t.GetPosition(), 0u);
}

TEST(TokenTest, NumberTokenWithHex) {
    const auto t{token::MakeNumber("0xff", 255, 5)};
    EXPECT_TRUE(t.IsNumber());
    EXPECT_EQ(t.GetValue(), 255u);
    EXPECT_EQ(t.GetText(), "0xff");
    EXPECT_EQ(t.GetPosition(), 5u);
}

TEST(TokenTest, RegisterTokenFactoryAndAccessors) {
    const auto t{token::MakeRegister("$a0", 10, 3)};
    EXPECT_TRUE(t.IsRegister());
    EXPECT_FALSE(t.IsNumber());
    EXPECT_EQ(t.GetText(), "$a0");
    EXPECT_EQ(t.GetPosition(), 3u);
}

TEST(TokenTest, PlusToken) {
    const auto t{token::MakePlus("+", 1)};
    EXPECT_TRUE(t.IsPlus());
    EXPECT_TRUE(t.IsBinaryOperator());
    EXPECT_EQ(t.GetPrecedence(), 3);
    EXPECT_FALSE(t.IsRightAssociative());
    EXPECT_EQ(t.GetText(), "+");
    EXPECT_EQ(t.GetPosition(), 1u);
}

TEST(TokenTest, MinusToken) {
    const auto t{token::MakeMinus("-", 2)};
    EXPECT_TRUE(t.IsMinus());
    EXPECT_TRUE(t.IsBinaryOperator());
    EXPECT_TRUE(t.IsUnaryOperator());
    EXPECT_EQ(t.GetPrecedence(), 3);
    EXPECT_TRUE(t.IsRightAssociative());
    EXPECT_EQ(t.GetText(), "-");
}

TEST(TokenTest, StarToken) {
    const auto t{token::MakeStar(3)};
    EXPECT_TRUE(t.IsStar());
    EXPECT_TRUE(t.IsBinaryOperator());
    EXPECT_TRUE(t.IsUnaryOperator());
    EXPECT_EQ(t.GetPrecedence(), 4);
    EXPECT_TRUE(t.IsRightAssociative());
}

TEST(TokenTest, SlashToken) {
    const auto t{token::MakeSlash(4)};
    EXPECT_TRUE(t.IsSlash());
    EXPECT_TRUE(t.IsBinaryOperator());
    EXPECT_EQ(t.GetPrecedence(), 4);
    EXPECT_FALSE(t.IsRightAssociative());
}

TEST(TokenTest, EqualToken) {
    const auto t{token::MakeEqual(5)};
    EXPECT_TRUE(t.IsEqual());
    EXPECT_TRUE(t.IsBinaryOperator());
    EXPECT_EQ(t.GetPrecedence(), 2);
    EXPECT_FALSE(t.IsRightAssociative());
}

TEST(TokenTest, NotEqualToken) {
    const auto t{token::MakeNotEqual(6)};
    EXPECT_TRUE(t.IsNotEqual());
    EXPECT_TRUE(t.IsBinaryOperator());
    EXPECT_EQ(t.GetPrecedence(), 2);
}

TEST(TokenTest, LessEqualToken) {
    const auto t{token::MakeLessEqual(7)};
    EXPECT_TRUE(t.IsLessEqual());
    EXPECT_TRUE(t.IsBinaryOperator());
    EXPECT_EQ(t.GetPrecedence(), 2);
}

TEST(TokenTest, LogicalAndToken) {
    const auto t{token::MakeLogicalAnd(8)};
    EXPECT_TRUE(t.IsLogicalAnd());
    EXPECT_TRUE(t.IsBinaryOperator());
    EXPECT_EQ(t.GetPrecedence(), 1);
}

TEST(TokenTest, LeftParenToken) {
    const auto t{token::MakeLeftParen(9)};
    EXPECT_TRUE(t.IsLeftParen());
    EXPECT_TRUE(t.IsParenthesis());
    EXPECT_FALSE(t.IsRightParen());
    EXPECT_FALSE(t.IsOperator());
}

TEST(TokenTest, RightParenToken) {
    const auto t{token::MakeRightParen(10)};
    EXPECT_TRUE(t.IsRightParen());
    EXPECT_TRUE(t.IsParenthesis());
    EXPECT_FALSE(t.IsLeftParen());
    EXPECT_FALSE(t.IsOperator());
}

TEST(TokenTest, EndToken) {
    const auto t{token::MakeEnd(11)};
    EXPECT_TRUE(t.IsEndOfInput());
    EXPECT_FALSE(t.IsNumber());
    EXPECT_FALSE(t.IsRegister());
    EXPECT_FALSE(t.IsOperator());
    EXPECT_EQ(t.GetPosition(), 11u);
}

TEST(TokenTest, ReadMemory8Token) {
    const auto t{token::MakeReadMemory8(12)};
    EXPECT_TRUE(t.IsReadMemory8());
    EXPECT_TRUE(t.IsReadMemory());
    EXPECT_FALSE(t.IsReadMemory16());
    EXPECT_FALSE(t.IsReadMemory32());
    EXPECT_EQ(t.GetPosition(), 12u);
}

TEST(TokenTest, ReadMemory16Token) {
    const auto t{token::MakeReadMemory16(13)};
    EXPECT_TRUE(t.IsReadMemory16());
    EXPECT_TRUE(t.IsReadMemory());
    EXPECT_FALSE(t.IsReadMemory8());
    EXPECT_FALSE(t.IsReadMemory32());
}

TEST(TokenTest, ReadMemory32Token) {
    const auto t{token::MakeReadMemory32(14)};
    EXPECT_TRUE(t.IsReadMemory32());
    EXPECT_TRUE(t.IsReadMemory());
    EXPECT_FALSE(t.IsReadMemory8());
    EXPECT_FALSE(t.IsReadMemory16());
}

TEST(TokenTest, DefaultTokenIsEndOfInput) {
    const token t{};
    EXPECT_TRUE(t.IsEndOfInput());
}

TEST(TokenTest, AllBinaryOperatorsAreOperators) {
    const auto plus{token::MakePlus("+", 0)};
    const auto minus{token::MakeMinus("-", 0)};
    const auto star{token::MakeStar(0)};
    const auto slash{token::MakeSlash(0)};
    const auto eq{token::MakeEqual(0)};
    const auto ne{token::MakeNotEqual(0)};
    const auto le{token::MakeLessEqual(0)};
    const auto land{token::MakeLogicalAnd(0)};

    EXPECT_TRUE(plus.IsOperator());
    EXPECT_TRUE(minus.IsOperator());
    EXPECT_TRUE(star.IsOperator());
    EXPECT_TRUE(slash.IsOperator());
    EXPECT_TRUE(eq.IsOperator());
    EXPECT_TRUE(ne.IsOperator());
    EXPECT_TRUE(le.IsOperator());
    EXPECT_TRUE(land.IsOperator());
}

TEST(TokenTest, ParenthesisAreNotOperators) {
    const auto lp{token::MakeLeftParen(0)};
    const auto rp{token::MakeRightParen(0)};
    EXPECT_FALSE(lp.IsOperator());
    EXPECT_FALSE(rp.IsOperator());
    EXPECT_TRUE(lp.IsParenthesis());
    EXPECT_TRUE(rp.IsParenthesis());
}

}  // namespace
