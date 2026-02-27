#include <iostream>
#include <format>
#include <memory>
#include <verilated.h>
#include <nvboard.h>
#include "Valu.h"

#ifdef TEST_MODE
#include <gtest/gtest.h>
class ALUTest : public ::testing::Test
{
protected:
    std::unique_ptr<Valu> top;
    void SetUp() override
    {
        top = std::make_unique<Valu>();
    }
    void TearDown() override
    {
        top->final();
    }
    void check_alu(int a, int b, int sel, int exp_res, bool exp_z)
    {
        top->A = a & 0xF;
        top->B = b & 0xF;
        top->ALU_Sel = sel;
        top->eval();
        EXPECT_EQ(exp_res & 0xF, top->Result) << "Result mismatch at Sel=" << sel;
        EXPECT_EQ(exp_z, top->Zero) << "Zero flag mismatch at Sel=" << sel;
    }
};
TEST_F(ALUTest, BasicArithmetic)
{
    check_alu(5, 3, 0, 8, false);
    check_alu(5, 5, 1, 0, true);
}
TEST_F(ALUTest, OverflowLogic)
{
    top->A = 7;
    top->B = 1;
    top->ALU_Sel = 0;
    top->eval();
    EXPECT_EQ(1, top->Overflow);
    EXPECT_EQ(8, top->Result);
    top->A = 8;
    top->B = 1;
    top->ALU_Sel = 1;
    top->eval();
    EXPECT_EQ(1, top->Overflow);
}
TEST_F(ALUTest, SignedComparison)
{
    top->A = 0xF;
    top->B = 2;
    top->ALU_Sel = 6;
    top->eval();
    EXPECT_EQ(1, top->Result);
}
#endif
int main(int argc, char *argv[])
{
    std::cout << std::format("开始仿真") << std::endl;
#ifdef TEST_MODE
    std::cout << std::format("Running GTest Mode...") << std::endl;
    testing::InitGoogleTest(&argc, argv);
    return RUN_ALL_TESTS();
#else
    Verilated::commandArgs(argc, argv);
    auto top = std::make_unique<Valu>();
    nvboard_bind_all_pins(top.get());
    nvboard_init();
    std::cout << std::format("ALU Simulator Running... Close the window to exit.") << std::endl;
    while (!Verilated::gotFinish)
    {
        top->eval();
        nvboard_update();
    }
    nvborad_quit();
    return 0;
#endif
}
