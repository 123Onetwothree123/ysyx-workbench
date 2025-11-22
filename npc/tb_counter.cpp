#include "Vcounter.h"
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <iostream>
#include <iomanip>

class CounterTestbench {
private:
    VerilatedContext* context;
    Vcounter* top;
    VerilatedVcdC* trace;
    uint64_t tick_count;

public:
    CounterTestbench() : tick_count(0) {
        context = new VerilatedContext;
        context->traceEverOn(true);
        top = new Vcounter{context};
        
        trace = new VerilatedVcdC;
        top->trace(trace, 99);
        trace->open("counter_waveform.vcd");
        
        // 初始化输入
        top->clk = 0;
        top->rst_n = 0;
        top->enable = 0;
    }

    ~CounterTestbench() {
        trace->close();
        delete trace;
        delete top;
        delete context;
    }

    void tick() {
        // 下降沿
        top->clk = 0;
        top->eval();
        trace->dump(context->time());
        context->timeInc(5);
        
        // 上升沿
        top->clk = 1;
        top->eval();
        trace->dump(context->time());
        context->timeInc(5);
        
        tick_count++;
    }

    void reset() {
        std::cout << "复位计数器..." << std::endl;
        top->rst_n = 0;
        for (int i = 0; i < 5; i++) {
            tick();
        }
        top->rst_n = 1;
        tick_count = 0;
    }

    void run_test() {
        std::cout << "========================================" << std::endl;
        std::cout << "计数器测试台" << std::endl;
        std::cout << "========================================" << std::endl;
        
        // 复位
        reset();
        
        // 检查复位值
        std::cout << "复位后: count = " << (int)top->count << std::endl;
        
        // 禁用计数，运行几个周期
        std::cout << "\n运行 enable=0..." << std::endl;
        top->enable = 0;
        for (int i = 0; i < 5; i++) {
            tick();
            std::cout << "周期 " << tick_count 
                      << ": count = " << (int)top->count << std::endl;
        }
        
        // 启用计数
        std::cout << "\n运行 enable=1..." << std::endl;
        top->enable = 1;
        for (int i = 0; i < 20; i++) {
            tick();
            std::cout << "周期 " << std::setw(2) << tick_count 
                      << ": count = " << (int)top->count << std::endl;
        }
        
        // 禁用计数
        std::cout << "\n禁用计数..." << std::endl;
        top->enable = 0;
        for (int i = 0; i < 5; i++) {
            tick();
            std::cout << "周期 " << tick_count 
                      << ": count = " << (int)top->count << std::endl;
        }
        
        std::cout << "========================================" << std::endl;
        std::cout << "测试完成!" << std::endl;
        std::cout << "波形文件: counter_waveform.vcd" << std::endl;
        std::cout << "========================================" << std::endl;
    }
};

int main(int argc, char** argv) {
    // 创建全局上下文并处理命令行参数
    VerilatedContext* context = new VerilatedContext;
    context->commandArgs(argc, argv);
    
    CounterTestbench tb;
    tb.run_test();
    
    delete context;
    return 0;
}
