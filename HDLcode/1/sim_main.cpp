#include "Vtop.h"
#include "verilated.h"
#include <nvboard.h> // 引入库头文件

// 声明刚才 python 生成的函数
void nvboard_bind_all_pins(Vtop* top);

// 定义一个处理复位的辅助函数 (可选)
static void single_cycle(Vtop* top) {
    top->clk = 0; top->eval();
    top->clk = 1; top->eval();
}

static void reset(Vtop* top, int n) {
    top->rst = 1;
    while (n-- > 0) single_cycle(top);
    top->rst = 0;
}

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    Vtop* top = new Vtop;

    // 1. 关键：调用生成的绑定函数
    // 这一步之后，NVBoard 内部就持有了 &top->led 的指针
    nvboard_bind_all_pins(top);

    // 2. 初始化图形界面
    nvboard_init();

    // 3. 复位硬件
    reset(top, 10);

    // 4. 循环
    while (!Verilated::gotFinish()) {
        // 更新硬件逻辑
        single_cycle(top);

        // 更新图形界面
        // 这一步会做两件事：
        // a. 读取 C++ 内存(top->led)，画到屏幕上
        // b. 读取 SDL 事件(键盘/鼠标)，更新 C++ 内存(top->rst 等绑定了 SW 的信号)
        nvboard_update();
    }
    
    // 退出
    nvboard_quit();
    delete top;
    return 0;
}