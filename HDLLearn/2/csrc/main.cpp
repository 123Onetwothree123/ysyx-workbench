#include "Vtop.h"
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <iostream>

int main(int argc, char const *argv[]) {
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);
    Vtop* dut = new Vtop;
    VerilatedVcdC* vcd = new VerilatedVcdC;
    dut->trace(vcd, 0);
    vcd->open("wave.vcd");
    vluint64_t sim_time = 0;
    
    std::cout << "开始测试异或门..." << std::endl;

    // 定义多组测试向量：{a, b}
    int test_vectors[][2] = {
        {0, 0},
        {0, 1},
        {1, 0},
        {1, 1}
    };

    for (int i = 0; i < 4; ++i) {
        dut->a = test_vectors[i][0];
        dut->b = test_vectors[i][1];
        dut->eval();                // 计算输出f
        
        // 关键：在每个输入组合上“保持”一段时间（例如10个时间单位）
        for (int stay = 0; stay < 10; ++stay) {
            vcd->dump(sim_time);
            sim_time++;             // 时间前进
        }
        
        // 打印结果到终端
        std::cout << "a=" << dut->a << ", b=" << dut->b 
                  << ", f=" << dut->f << std::endl;
    }

    // 最后再记录一点时间，方便观察结束状态
    vcd->dump(sim_time);
    
    std::cout << "仿真结束，波形已保存至 wave.vcd" << std::endl;
    
    vcd->close();
    delete dut;
    delete vcd;
    return 0;
}