#include <iostream>
#include <verilated.h>
#include "Vdecode24.h"

int run_unit_test(Vdecode24 *dut) {
    int32_t pass_count = 0;
    int32_t fail_count = 0;
    std::cout << "\n=== 2-4译码器单元测试 ===" << std::endl;
    
    for (int en = 0; en <= 1; en++) {
        for (int x = 0; x <= 3; x++) {
            dut->en = en;
            dut->x = x;
            dut->eval();
            
            uint8_t expected = en ? (1 << x) : 0;
            
            if (dut->y == expected) {
                std::cout << "[PASS] en=" << en << ", x=" << x
                          << ", y=" << static_cast<int>(dut->y) << std::endl;
                pass_count++;
            } else {
                std::cout << "[FAIL] en=" << en << ", x=" << x
                          << ", y=" << static_cast<int>(dut->y)
                          << ", expected=" << static_cast<int>(expected) << std::endl;
                fail_count++;
            }
        }
    }
    
    std::cout << "\n=== 测试结果 ===" << std::endl;
    std::cout << "通过: " << pass_count << std::endl;
    std::cout << "失败: " << fail_count << std::endl;
    
    return (fail_count == 0) ? 0 : 1;
}

int main(int argc, char **argv) {
    VerilatedContext context;
    context.commandArgs(argc, argv);
    Vdecode24 dut(&context);
    
    std::cout << "运行单元测试模式..." << std::endl;
    int test_result = run_unit_test(&dut);
    dut.final();
    
    return test_result;
}
