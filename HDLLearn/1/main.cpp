#include "Vadder.h"
#include "verilated.h"
#include <iostream>

int main(int argc, char** argv) {
    VerilatedContext* context = new VerilatedContext;
    context->commandArgs(argc, argv);
    
    // 实例化模块
    Vadder* top = new Vadder{context};
    
    std::cout << "========================================" << std::endl;
    std::cout << "加法器测试开始" << std::endl;
    std::cout << "========================================" << std::endl;
    
    // 测试所有可能的输入组合
    for (int a = 0; a <= 1; a++) {
        for (int b = 0; b <= 1; b++) {
            top->a = a;
            top->b = b;
            top->eval();
            
            std::cout << "a=" << a << ", b=" << b 
                      << ", sum=" << (int)top->sum 
                      << ", cout=" << (int)top->cout << std::endl;
        }
    }
    
    std::cout << "========================================" << std::endl;
    std::cout << "加法器测试完成" << std::endl;
    std::cout << "========================================" << std::endl;
    
    delete top;
    delete context;
    return 0;
}
