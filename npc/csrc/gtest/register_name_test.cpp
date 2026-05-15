#include <cstdint>
#include <optional>
#include <string_view>

#include <gtest/gtest.h>

#include "tools/Expressions/RegisterName.hpp"

namespace {

TEST(RegisterNameTest, StripPrefixRemovesLeadingDollarSign) {
    EXPECT_EQ(StripRegisterPrefix("$pc"), "pc");
    EXPECT_EQ(StripRegisterPrefix("$x0"), "x0");
    EXPECT_EQ(StripRegisterPrefix("$ra"), "ra");
    EXPECT_EQ(StripRegisterPrefix("$sp"), "sp");
    EXPECT_EQ(StripRegisterPrefix("$a0"), "a0");
    EXPECT_EQ(StripRegisterPrefix("$t6"), "t6");
}

TEST(RegisterNameTest, StripPrefixPreservesNameWithoutDollar) {
    EXPECT_EQ(StripRegisterPrefix("pc"), "pc");
    EXPECT_EQ(StripRegisterPrefix("x0"), "x0");
    EXPECT_EQ(StripRegisterPrefix("ra"), "ra");
    EXPECT_EQ(StripRegisterPrefix("zero"), "zero");
}

TEST(RegisterNameTest, StripPrefixHandlesEmptyString) {
    EXPECT_EQ(StripRegisterPrefix(""), "");
}

TEST(RegisterNameTest, IsProgramCounterNameDetectsPc) {
    EXPECT_TRUE(IsProgramCounterName("pc"));
    EXPECT_TRUE(IsProgramCounterName("$pc"));
    EXPECT_FALSE(IsProgramCounterName("x0"));
    EXPECT_FALSE(IsProgramCounterName("ra"));
    EXPECT_FALSE(IsProgramCounterName(""));
    EXPECT_FALSE(IsProgramCounterName("$ra"));
}

TEST(RegisterNameTest, RegisterNameToIndexZeroRegister) {
    EXPECT_EQ(RegisterNameToIndex("zero"), std::optional<std::uint32_t>{0u});
    EXPECT_EQ(RegisterNameToIndex("x0"), std::optional<std::uint32_t>{0u});
    EXPECT_EQ(RegisterNameToIndex("$x0"), std::optional<std::uint32_t>{0u});
    EXPECT_EQ(RegisterNameToIndex("$zero"), std::optional<std::uint32_t>{0u});
    EXPECT_EQ(RegisterNameToIndex("0"), std::optional<std::uint32_t>{0u});
    EXPECT_EQ(RegisterNameToIndex("$0"), std::optional<std::uint32_t>{0u});
}

TEST(RegisterNameTest, RegisterNameToIndexStandardRegisters) {
    EXPECT_EQ(RegisterNameToIndex("ra"), std::optional<std::uint32_t>{1u});
    EXPECT_EQ(RegisterNameToIndex("sp"), std::optional<std::uint32_t>{2u});
    EXPECT_EQ(RegisterNameToIndex("gp"), std::optional<std::uint32_t>{3u});
    EXPECT_EQ(RegisterNameToIndex("tp"), std::optional<std::uint32_t>{4u});
    EXPECT_EQ(RegisterNameToIndex("t0"), std::optional<std::uint32_t>{5u});
    EXPECT_EQ(RegisterNameToIndex("t1"), std::optional<std::uint32_t>{6u});
    EXPECT_EQ(RegisterNameToIndex("t2"), std::optional<std::uint32_t>{7u});
    EXPECT_EQ(RegisterNameToIndex("s0"), std::optional<std::uint32_t>{8u});
    EXPECT_EQ(RegisterNameToIndex("fp"), std::optional<std::uint32_t>{8u});
    EXPECT_EQ(RegisterNameToIndex("s1"), std::optional<std::uint32_t>{9u});
    EXPECT_EQ(RegisterNameToIndex("a0"), std::optional<std::uint32_t>{10u});
    EXPECT_EQ(RegisterNameToIndex("a7"), std::optional<std::uint32_t>{17u});
    EXPECT_EQ(RegisterNameToIndex("s2"), std::optional<std::uint32_t>{18u});
    EXPECT_EQ(RegisterNameToIndex("s11"), std::optional<std::uint32_t>{27u});
    EXPECT_EQ(RegisterNameToIndex("t3"), std::optional<std::uint32_t>{28u});
    EXPECT_EQ(RegisterNameToIndex("t6"), std::optional<std::uint32_t>{31u});
}

TEST(RegisterNameTest, RegisterNameToIndexXFormat) {
    EXPECT_EQ(RegisterNameToIndex("x1"), std::optional<std::uint32_t>{1u});
    EXPECT_EQ(RegisterNameToIndex("x10"), std::optional<std::uint32_t>{10u});
    EXPECT_EQ(RegisterNameToIndex("x31"), std::optional<std::uint32_t>{31u});
    EXPECT_EQ(RegisterNameToIndex("$x5"), std::optional<std::uint32_t>{5u});
    EXPECT_EQ(RegisterNameToIndex("$x25"), std::optional<std::uint32_t>{25u});
}

TEST(RegisterNameTest, RegisterNameToIndexDollarABI) {
    EXPECT_EQ(RegisterNameToIndex("$ra"), std::optional<std::uint32_t>{1u});
    EXPECT_EQ(RegisterNameToIndex("$sp"), std::optional<std::uint32_t>{2u});
    EXPECT_EQ(RegisterNameToIndex("$a0"), std::optional<std::uint32_t>{10u});
    EXPECT_EQ(RegisterNameToIndex("$t6"), std::optional<std::uint32_t>{31u});
    EXPECT_EQ(RegisterNameToIndex("$fp"), std::optional<std::uint32_t>{8u});
}

TEST(RegisterNameTest, RegisterNameToIndexInvalidNames) {
    EXPECT_FALSE(RegisterNameToIndex("").has_value());
    EXPECT_FALSE(RegisterNameToIndex("pc").has_value());
    EXPECT_FALSE(RegisterNameToIndex("x32").has_value());
    EXPECT_FALSE(RegisterNameToIndex("x99").has_value());
    EXPECT_FALSE(RegisterNameToIndex("abc").has_value());
    EXPECT_FALSE(RegisterNameToIndex("$abc").has_value());
    EXPECT_FALSE(RegisterNameToIndex("$x32").has_value());
    EXPECT_FALSE(RegisterNameToIndex("x1a").has_value());
    EXPECT_FALSE(RegisterNameToIndex("$").has_value());
    EXPECT_FALSE(RegisterNameToIndex("raa").has_value());
}

TEST(RegisterNameTest, RegisterNameToIndexAllXRegisters) {
    for (std::uint32_t i = 0; i < 32; ++i) {
        const auto result = RegisterNameToIndex(std::string(1, 'x') + std::to_string(i));
        ASSERT_TRUE(result.has_value()) << "x" << i << " should be valid";
        EXPECT_EQ(*result, i) << "x" << i << " should map to index " << i;
    }
}

TEST(RegisterNameTest, PcIsNotAValidRegisterIndex) {
    EXPECT_FALSE(RegisterNameToIndex("pc").has_value());
    EXPECT_FALSE(RegisterNameToIndex("$pc").has_value());
    EXPECT_TRUE(IsProgramCounterName("pc"));
    EXPECT_TRUE(IsProgramCounterName("$pc"));
}

}  // namespace
