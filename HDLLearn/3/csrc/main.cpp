#include <nvboard.h>
#include "Vtop.h"
#include "verilated.h"

// 1. 声明由 auto_pin_bind.py 自动生成的绑定函数
// 注意：参数类型必须与你的顶层模块实例类型一致 (Vtop*)
void nvboard_bind_all_pins(Vtop* top);

int main(int argc, char** argv) {
    // 2. 准备 Verilator 环境
    VerilatedContext* contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    Vtop* dut = new Vtop{contextp};

    // 3. 初始化 NVBoard
    nvboard_init();

    // 4. 建立 Verilog 信号与 NVBoard 虚拟硬件的连接
    // 此函数会根据 top.nvc 的配置将 a, b 连到开关，f 连到 LED
    nvboard_bind_all_pins(dut);

    // 5. 进入实时仿真主循环
    while (!contextp->gotFinish()) {
        // 更新 NVBoard 的状态（读取鼠标点击开关的状态等）
        nvboard_update();

        // 执行电路的逻辑计算
        // 当 a, b 发生变化时，dut->f 会在这里被更新
        dut->eval();

        // 可以在这里添加打印语句调试（可选）
        // printf("a=%d, b=%d, f=%d\n", dut->a, dut->b, dut->f);
    }

    // 6. 退出清理
    nvboard_quit();
    delete dut;
    delete contextp;
    return 0;
}