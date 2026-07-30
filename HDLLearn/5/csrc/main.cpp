#include <iostream>
#include <vector>
#include <list>
#include <set>
#include <map>
#include <format>
#include <verilated.h>
#include <nvboard.h>
#include <memory>
#include <cstdlib>
#include "Vdecode24.h"
void nvboard_bind_all_pins(Vdecode24 *decode24);
int run_unit_test(Vdecode24 *dut);
int main(int argc, char **argv)
{
    std::cout << std::format("program begin run") << std::endl;
    // 检查是否有 --test 参数
    bool run_test = false;
    for (int i = 1; i < argc; i++)
    {
        if (std::string(argv[i]) == "--test")
        {
            run_test = true;
            break;
        }
    }
    auto contextp = std::make_unique<VerilatedContext>();
    contextp->commandArgs(argc, argv);
    auto dut = std::make_unique<Vdecode24>(contextp.get());
    if (run_test)
    {
        std::cout << std::format("run unit test mode...") << std::endl;
        auto test_result = run_unit_test(dut.get());
        dut->final();
        return test_result;
    }
    else
    {
        nvboard_bind_all_pins(dut.get());
        nvboard_init();
        nvboard_update();
        while (!contextp->gotFinish())
        {
            nvboard_update();
            dut->eval();
        }
        dut->final();
        nvboard_quit();
        return 0;
    }
}
int run_unit_test(Vdecode24 *dut)
{
    int32_t pass_count = 0;
    int32_t fail_count = 0;
    std::cout << std::format("\n2-4decoder unit test\n");
    for (int en = 0; en <= 1; en++)
    {
        for (int x = 0; x <= 3; x++)
        {
            dut->en = en;
            dut->x = x;
            dut->eval();

            uint8_t expected = en ? (1 << x) : 0;

            if (dut->y == expected)
            {
                std::cout << "[PASS] en=" << en << ", x=" << x
                          << ", y=" << static_cast<int>(dut->y) << std::endl;
                pass_count++;
            }
            else
            {
                std::cout << "[FAIL] en=" << en << ", x=" << x
                          << ", y=" << static_cast<int>(dut->y)
                          << ", expected=" << static_cast<int>(expected) << std::endl;
                fail_count++;
            }
        }
    }
    std::cout << "pass: " << pass_count << std::endl;
    std::cout << "fail: " << fail_count << std::endl;
    return (fail_count == 0) ? 0 : 1;
}