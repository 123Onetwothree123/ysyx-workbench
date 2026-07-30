# Verilator C++ 测试代码教程

## 目录
1. [简介](#简介)
2. [基本结构](#基本结构)
3. [核心API](#核心api)
4. [常用模式](#常用模式)
5. [完整示例](#完整示例)
6. [编译与运行](#编译与运行)

---

## 简介

Verilator 是一个高性能的 Verilog/SystemVerilog 仿真器，它将 Verilog 代码转换为 C++ 模型。与传统仿真器不同，Verilator 需要编写 C++ 测试台来驱动仿真。

### Verilator 的特点
- **高性能**：编译为 C++，执行速度快
- **可移植**：生成的 C++ 代码可在任何平台上编译
- **适合回归测试**：适合大量自动化测试
- **不支持 SystemVerilog 测试台**：需要用 C++ 编写测试逻辑

---

## 基本结构

### 最小模板

```cpp
#include "V模块名.h"          // Verilator生成的头文件
#include "verilated.h"        // Verilator核心头文件

int main(int argc, char** argv) {
    // 1. 创建上下文
    VerilatedContext* context = new VerilatedContext;
    context->commandArgs(argc, argv);  // 处理命令行参数
    
    // 2. 实例化模块
    V模块名* top = new V模块名{context};
    
    // 3. 测试逻辑
    top->输入端口 = 值;
    top->eval();  // 评估模块
    
    // 4. 清理
    delete top;
    delete context;
    return 0;
}
```

---

## 核心API

### 1. VerilatedContext

仿真上下文，管理仿真环境。

```cpp
VerilatedContext* context = new VerilatedContext;

// 设置时间精度
context->timeunit(1);      // 时间单位
context->timeprecision(1); // 时间精度

// 获取仿真时间
uint64_t time = context->time();

// 追踪功能
context->traceEverOn(true);  // 启用追踪

// 清理
delete context;
```

### 2. 模块实例化

```cpp
// 模块名前缀为 'V'
Vadder* top = new Vadder{context};

// 访问端口
top->a = 1;        // 输入端口
top->b = 0;
uint8_t sum = top->sum;  // 读取输出端口
```

### 3. 评估模块

```cpp
top->eval();  // 评估组合逻辑

// 对于有时钟的模块，需要配合时钟周期
while (!context->gotFinish()) {
    top->clk = 0;
    top->eval();
    context->timeInc(5);  // 时间前进
    
    top->clk = 1;
    top->eval();
    context->timeInc(5);
}
```

### 4. 时间控制

```cpp
// 前进时间
context->timeInc(10);  // 前进10个时间单位

// 获取当前时间
uint64_t now = context->time();
```

---

## 常用模式

### 模式1：组合逻辑测试

```cpp
#include "Vadder.h"
#include "verilated.h"
#include <iostream>

int main() {
    VerilatedContext* context = new VerilatedContext;
    Vadder* top = new Vadder{context};
    
    // 测试所有输入组合
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
    
    delete top;
    delete context;
    return 0;
}
```

### 模式2：时序逻辑测试（带时钟）

```cpp
#include "Vcounter.h"
#include "verilated.h"
#include <iostream>

int main() {
    VerilatedContext* context = new VerilatedContext;
    Vcounter* top = new Vcounter{context};
    
    // 复位
    top->rst_n = 0;
    for (int i = 0; i < 5; i++) {
        top->clk = 0; top->eval(); context->timeInc(5);
        top->clk = 1; top->eval(); context->timeInc(5);
    }
    top->rst_n = 1;
    
    // 运行100个时钟周期
    for (int cycle = 0; cycle < 100; cycle++) {
        // 上升沿
        top->clk = 0; top->eval(); context->timeInc(5);
        top->clk = 1; top->eval(); context->timeInc(5);
        
        std::cout << "Cycle " << cycle 
                  << ": count = " << top->count << std::endl;
    }
    
    delete top;
    delete context;
    return 0;
}
```

### 模式3：使用波形追踪

```cpp
#include "Vadder.h"
#include "verilated.h"
#include "verilated_vcd_c.h"

int main() {
    VerilatedContext* context = new VerilatedContext;
    context->traceEverOn(true);  // 启用追踪
    
    Vadder* top = new Vadder{context};
    
    // 创建VCD追踪
    VerilatedVcdC* trace = new VerilatedVcdC;
    top->trace(trace, 99);  // 追踪深度
    trace->open("waveform.vcd");
    
    // 测试逻辑
    for (int a = 0; a <= 1; a++) {
        for (int b = 0; b <= 1; b++) {
            top->a = a;
            top->b = b;
            top->eval();
            trace->dump(context->time());  // 记录波形
            context->timeInc(10);
        }
    }
    
    trace->close();
    delete trace;
    delete top;
    delete context;
    return 0;
}
```

### 模式4：断言检查

```cpp
#include "Vadder.h"
#include "verilated.h"
#include <cassert>
#include <iostream>

void test_adder(int a, int b, int expected_sum, int expected_cout) {
    VerilatedContext* context = new VerilatedContext;
    Vadder* top = new Vadder{context};
    
    top->a = a;
    top->b = b;
    top->eval();
    
    // 断言检查
    assert(top->sum == expected_sum && "Sum mismatch!");
    assert(top->cout == expected_cout && "Carry mismatch!");
    
    std::cout << "PASS: a=" << a << ", b=" << b 
              << ", sum=" << (int)top->sum 
              << ", cout=" << (int)top->cout << std::endl;
    
    delete top;
    delete context;
}

int main() {
    test_adder(0, 0, 0, 0);
    test_adder(0, 1, 1, 0);
    test_adder(1, 0, 1, 0);
    test_adder(1, 1, 0, 1);
    
    std::cout << "All tests passed!" << std::endl;
    return 0;
}
```

### 模式5：文件输入测试

```cpp
#include "Vadder.h"
#include "verilated.h"
#include <fstream>
#include <sstream>

int main() {
    VerilatedContext* context = new VerilatedContext;
    Vadder* top = new Vadder{context};
    
    std::ifstream testfile("test_vectors.txt");
    std::string line;
    
    while (std::getline(testfile, line)) {
        int a, b, expected_sum, expected_cout;
        std::istringstream iss(line);
        iss >> a >> b >> expected_sum >> expected_cout;
        
        top->a = a;
        top->b = b;
        top->eval();
        
        if (top->sum == expected_sum && top->cout == expected_cout) {
            std::cout << "PASS: " << line << std::endl;
        } else {
            std::cout << "FAIL: " << line 
                      << " Got sum=" << (int)top->sum 
                      << ", cout=" << (int)top->cout << std::endl;
        }
    }
    
    testfile.close();
    delete top;
    delete context;
    return 0;
}
```

---

## 完整示例

### Verilog模块 (counter.v)

```verilog
module counter(
    input wire clk,
    input wire rst_n,
    input wire enable,
    output reg [3:0] count
);

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            count <= 4'b0000;
        end else if (enable) begin
            count <= count + 1;
        end
    end

endmodule
```

### C++测试台 (main.cpp)

```cpp
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
        std::cout << "Resetting counter..." << std::endl;
        top->rst_n = 0;
        for (int i = 0; i < 5; i++) {
            tick();
        }
        top->rst_n = 1;
        tick_count = 0;
    }

    void run_test() {
        std::cout << "========================================" << std::endl;
        std::cout << "Counter Testbench" << std::endl;
        std::cout << "========================================" << std::endl;
        
        // 复位
        reset();
        
        // 检查复位值
        std::cout << "After reset: count = " << (int)top->count << std::endl;
        
        // 禁用计数，运行几个周期
        std::cout << "\nRunning with enable=0..." << std::endl;
        top->enable = 0;
        for (int i = 0; i < 5; i++) {
            tick();
            std::cout << "Cycle " << tick_count 
                      << ": count = " << (int)top->count << std::endl;
        }
        
        // 启用计数
        std::cout << "\nRunning with enable=1..." << std::endl;
        top->enable = 1;
        for (int i = 0; i < 20; i++) {
            tick();
            std::cout << "Cycle " << std::setw(2) << tick_count 
                      << ": count = " << (int)top->count << std::endl;
        }
        
        // 禁用计数
        std::cout << "\nDisabling counter..." << std::endl;
        top->enable = 0;
        for (int i = 0; i < 5; i++) {
            tick();
            std::cout << "Cycle " << tick_count 
                      << ": count = " << (int)top->count << std::endl;
        }
        
        std::cout << "========================================" << std::endl;
        std::cout << "Test completed!" << std::endl;
        std::cout << "========================================" << std::endl;
    }
};

int main(int argc, char** argv) {
    VerilatedContext::commandArgs(argc, argv);
    
    CounterTestbench tb;
    tb.run_test();
    
    return 0;
}
```

---

## 编译与运行

### 步骤1：编译Verilog模块

```bash
verilator --cc --exe counter.v main.cpp
```

参数说明：
- `--cc`：生成C++代码
- `--exe`：生成可执行文件

### 步骤2：编译C++代码

```bash
cd obj_dir
make -f Vcounter.mk -j$(nproc)
```

### 步骤3：运行测试

```bash
./Vcounter
```

### 一步编译（推荐）

```bash
verilator --cc --exe counter.v main.cpp --make obj_dir/Vcounter
cd obj_dir && make -j$(nproc) && ./Vcounter
```

---

## 常用编译选项

```bash
# 启用波形追踪
verilator --trace counter.v main.cpp

# 设置时间精度
verilator --timescale 1ns/1ps counter.v main.cpp

# 显示警告
verilator -Wall counter.v main.cpp

# 生成覆盖率报告
verilator --coverage counter.v main.cpp

# 指定输出目录
verilator --Mdir build counter.v main.cpp
```

---

## 调试技巧

### 1. 打印信号值

```cpp
std::cout << "count = " << top->count << std::endl;
```

### 2. 查看波形

使用 GTKWave 查看生成的 VCD 文件：

```bash
gtkwave counter_waveform.vcd
```

### 3. 断言

```cpp
assert(top->count == expected && "Count mismatch!");
```

### 4. Verilator调试模式

```bash
verilator --debug counter.v main.cpp
```

---

## 参考资源

- [Verilator官方文档](https://verilator.org/guide/latest/)
- [Verilator GitHub](https://github.com/verilator/verilator)
- [示例代码](https://github.com/verilator/verilator/tree/master/examples)
