#include <gtest/gtest.h>
#include <verilated.h>

int main(int argc, char **argv) {
    ::testing::InitGoogleTest(&argc, argv);
    Verilated::commandArgs(argc, argv);
    return RUN_ALL_TESTS();
}
