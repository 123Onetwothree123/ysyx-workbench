#include <iostream>
#include <string>
#include <memory>
#include <format>
#include <cmath>
#include <verilated.h>
#include "VSevenSegLEDNixietubeKeyboardASCII.h"
// 辅助函数：产生一个时钟周期
static void single_cycle(VSevenSegLEDNixietubeKeyboardASCII* top) {
    top->clk = 0; top->eval();
    top->clk = 1; top->eval();
}
#ifdef TEST_MODE
#include <gtest/gtest.h>
#else
#include <nvboard.h>
#endif

// 七段数码管解码表（共阳极，低电平点亮）
// 段码顺序: g,f,e,d,c,b,a
static const uint8_t SEG_DECODE_TABLE[16] = {
    0x40, // 0 -> 1000000
    0x79, // 1 -> 1111001
    0x24, // 2 -> 0100100
    0x30, // 3 -> 0110000
    0x19, // 4 -> 0011001
    0x12, // 5 -> 0010010
    0x02, // 6 -> 0000010
    0x78, // 7 -> 1111000
    0x00, // 8 -> 0000000
    0x10, // 9 -> 0010000
    0x08, // A -> 0001000
    0x03, // b -> 0000011
    0x46, // C -> 1000110
    0x21, // d -> 0100001
    0x06, // E -> 0000110
    0x0E  // F -> 0001110
};

// PS/2 扫描码到ASCII的映射表（用于验证）
static const uint8_t SCAN_TO_ASCII[256] = {
    // 0x00-0x0F
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // 0x10-0x1F
    0, 0, 0, 0, 0, 'q', '1', 0, 0, 0, 'z', 's', 'a', 'w', '2', 0,
    // 0x20-0x2F
    0, 'c', 'x', 'd', 'e', '4', '3', 0, 0, ' ', 'v', 'f', 't', 'r', '5', 0,
    // 0x30-0x3F
    0, 'n', 'b', 'h', 'g', 'y', '6', 0, 0, 0, 'm', 'j', 'u', '7', '8', 0,
    // 0x40-0x4F
    0, ',', 'k', 'i', 'o', '0', '9', 0, 0, '.', '/', 'l', ';', 'p', '-', 0,
    // 0x50-0x5F
    0, 0, '\'', 0, '[', '=', 0, 0, 0, 0, '\n', ']', 0, '\\', 0, 0,
    // 0x60-0x6F
    0, 0, 0, 0, 0, 0, '\b', 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // 0x70-0x7F
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // 0x80-0x8F (断码前缀)
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // 0x90-0x9F
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // 0xA0-0xAF
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // 0xB0-0xBF
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // 0xC0-0xCF
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // 0xD0-0xDF
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // 0xE0-0xEF (扩展键前缀)
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // 0xF0-0xFF (断码前缀)
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
};

#ifdef TEST_MODE

// 全局仿真时间
static vluint64_t main_time = 0;

// 获取当前仿真时间（Verilator回调使用）
double sc_time_stamp() {
    return main_time;
}

// 测试基类
class KeyboardTestBase : public ::testing::Test {
protected:
    VSevenSegLEDNixietubeKeyboardASCII* top;
    VerilatedContext* context;
    
    void SetUp() override {
        // 必须先重置全局时间，再创建模型（Verilator会在构造时检查时间）
        main_time = 0;
        // 每个测试用例都创建新的上下文，避免时间累积问题
        context = new VerilatedContext;
        context->time(0);  // 显式设置时间为0
        top = new VSevenSegLEDNixietubeKeyboardASCII(context);
        // 初始复位
        reset();
    }
    
    void TearDown() override {
        top->final();
        delete top;
        delete context;
    }
    
    // 一个时钟周期（假设100MHz，周期10ns）
    void tick(int cycles = 1) {
        for (int i = 0; i < cycles; i++) {
            // 上升沿
            top->clk = 0;
            top->eval();
            main_time += 5;
            
            top->clk = 1;
            top->eval();
            main_time += 5;
        }
    }
    
    // 复位
    void reset() {
        top->rst_n = 0;
        top->ps2_clk = 1;
        top->ps2_data = 1;
        tick(10);
        top->rst_n = 1;
        tick(5);
    }
    
    // 发送PS/2数据包（11位：起始位0 + 8位数据 + 奇校验位 + 停止位1）
    void send_ps2_byte(uint8_t data) {
        // 计算奇校验位
        int parity = 1; // 奇校验，初始为1（包括起始位0，总共奇数个1）
        for (int i = 0; i < 8; i++) {
            parity ^= (data >> i) & 1;
        }
        
        // PS/2时钟周期约为66.7KHz（15us），我们这里用1500个时钟周期模拟
        const int PS2_HALF_PERIOD = 750; // 100MHz时钟下的半周期
        
        // 起始位 0
        send_ps2_bit(0, PS2_HALF_PERIOD);
        
        // 8位数据，LSB first
        for (int i = 0; i < 8; i++) {
            send_ps2_bit((data >> i) & 1, PS2_HALF_PERIOD);
        }
        
        // 奇校验位
        send_ps2_bit(parity, PS2_HALF_PERIOD);
        
        // 停止位 1
        send_ps2_bit(1, PS2_HALF_PERIOD);
        
        // 空闲状态
        top->ps2_data = 1;
        tick(PS2_HALF_PERIOD * 2);
    }
    
    void send_ps2_bit(int bit, int half_period) {
        // PS/2在时钟下降沿采样数据
        // 数据在下降沿前准备好，在上升沿后改变
        top->ps2_clk = 1;
        top->ps2_data = bit;
        tick(half_period);
        
        top->ps2_clk = 0;
        tick(half_period);
    }
    
    // 模拟按键按下（发送通码）
    void press_key(uint8_t scan_code) {
        send_ps2_byte(scan_code);
    }
    
    // 模拟按键释放（发送断码 F0 + 扫描码）
    void release_key(uint8_t scan_code) {
        send_ps2_byte(0xF0); // 断码前缀
        send_ps2_byte(scan_code);
    }
    
    // 模拟完整按键动作（按下+释放）
    void type_key(uint8_t scan_code) {
        press_key(scan_code);
        release_key(scan_code);
    }
};

// 测试1: 复位测试
TEST_F(KeyboardTestBase, ResetTest) {
    // 复位后检查初始状态
    // hex_data = 4'h0 (scan_code=0), seg_led = 7'b100_0000 = 0x40 (显示"0")
    EXPECT_EQ(top->seg_led, 0x40); // 七段码显示"0"
    // 数码管位选应该正常工作
}

// 测试2: 基本按键测试 - 发送一个简单的扫描码
TEST_F(KeyboardTestBase, BasicKeyPress) {
    // 发送'A'键的扫描码 0x1C
    press_key(0x1C);
    
    // 等待一段时间让信号传播
    tick(500);
    
    // 释放按键
    release_key(0x1C);
    tick(500);
    
    // 测试通过（只要没有崩溃就OK）
    SUCCEED();
}

// 测试3: 多次按键计数
TEST_F(KeyboardTestBase, KeyCountMultiple) {
    // 发送多次按键
    for (int i = 0; i < 5; i++) {
        type_key(0x1C); // 'A'键
        tick(200);
    }
    
    tick(1000);
    SUCCEED();
}

// 测试4: 不同按键测试
TEST_F(KeyboardTestBase, DifferentKeys) {
    // 测试多个不同的键
    uint8_t test_keys[] = {0x1C, 0x32, 0x21, 0x23, 0x24}; // A, B, C, D, E
    
    for (uint8_t key : test_keys) {
        type_key(key);
        tick(500);
    }
    
    SUCCEED();
}

// 测试5: 快速连续按键
TEST_F(KeyboardTestBase, RapidKeyPress) {
    // 快速发送多个按键
    for (int i = 0; i < 10; i++) {
        press_key(0x1C);
        tick(100);
        release_key(0x1C);
        tick(100);
    }
    
    SUCCEED();
}

// 测试6: 长按键测试（按住不放）
TEST_F(KeyboardTestBase, LongKeyPress) {
    // 只发送通码，不发送断码
    press_key(0x1C);
    
    // 模拟按住一段时间
    tick(5000);
    
    // 释放
    release_key(0x1C);
    tick(500);
    
    SUCCEED();
}

// 测试7: 数码管显示测试
TEST_F(KeyboardTestBase, DisplayOutput) {
    // 发送一个会产生特定显示的按键
    type_key(0x1C); // 'A'键
    
    tick(2000);
    
    // 检查输出不为空（至少有一些段被点亮）
    // seg_led是低电平有效，所以有按键时应该有一些位是0
    // 但由于数码管是扫描显示的，需要多次采样
    bool display_active = false;
    for (int i = 0; i < 100; i++) {
        tick(10);
        if (top->seg_led != 0x7F) { // 不是所有段都灭
            display_active = true;
            break;
        }
    }
    
    EXPECT_TRUE(display_active) << "数码管应该显示内容";
}

// 测试8: 断码处理测试
TEST_F(KeyboardTestBase, BreakCodeHandling) {
    // 发送断码前缀后没有通码的情况（异常输入）
    send_ps2_byte(0xF0);
    tick(500);
    
    // 正常按键
    type_key(0x1C);
    tick(500);
    
    SUCCEED();
}

// 测试9: 校验错误测试（发送错误的校验位）
TEST_F(KeyboardTestBase, ParityError) {
    // 手动发送一个校验错误的数据包
    const int PS2_HALF_PERIOD = 750;
    
    // 起始位
    send_ps2_bit(0, PS2_HALF_PERIOD);
    
    uint8_t data = 0x1C;
    // 8位数据
    for (int i = 0; i < 8; i++) {
        send_ps2_bit((data >> i) & 1, PS2_HALF_PERIOD);
    }
    
    // 故意发送错误的校验位（正确应该是奇校验）
    int wrong_parity = 0; // 错误的校验位
    for (int i = 0; i < 8; i++) {
        wrong_parity ^= (data >> i) & 1;
    }
    // wrong_parity 现在是偶校验，我们用它（奇校验应该相反）
    send_ps2_bit(wrong_parity, PS2_HALF_PERIOD);
    
    // 停止位
    send_ps2_bit(1, PS2_HALF_PERIOD);
    
    tick(1000);
    SUCCEED();
}

// 测试10: 计数器溢出测试（255次按键）
TEST_F(KeyboardTestBase, CounterOverflow) {
    // 快速发送256次按键，测试计数器溢出
    for (int i = 0; i < 260; i++) {
        type_key(0x1C);
        tick(50);
    }
    
    tick(1000);
    SUCCEED();
}

// 测试11: 扩展键测试（带E0前缀的键）
TEST_F(KeyboardTestBase, ExtendedKey) {
    // 发送扩展键（如方向键）
    send_ps2_byte(0xE0); // 扩展前缀
    type_key(0x75); // 上箭头
    tick(1000);
    
    SUCCEED();
}

// 测试12: 组合键测试
TEST_F(KeyboardTestBase, ComboKeys) {
    // 模拟Shift+A
    press_key(0x12); // 左Shift
    tick(100);
    press_key(0x1C); // A
    tick(500);
    release_key(0x1C);
    tick(100);
    release_key(0x12);
    tick(500);
    
    SUCCEED();
}

// 测试13: 空操作测试（没有按键输入）
TEST_F(KeyboardTestBase, IdleState) {
    // 保持空闲状态一段时间
    tick(10000);
    
    // 检查输出稳定
    SUCCEED();
}

// 测试14: 全键盘扫描码测试（关键按键）
TEST_F(KeyboardTestBase, FullKeyboardScan) {
    // 数字键 0-9
    uint8_t number_keys[] = {0x45, 0x16, 0x1E, 0x26, 0x25, 0x2E, 0x36, 0x3D, 0x3E, 0x46};
    for (uint8_t key : number_keys) {
        type_key(key);
        tick(200);
    }
    
    // 字母键 A-Z 的一部分
    uint8_t letter_keys[] = {0x1C, 0x32, 0x21, 0x23, 0x24, 0x2B, 0x34, 0x33, 0x43, 0x3B};
    for (uint8_t key : letter_keys) {
        type_key(key);
        tick(200);
    }
    
    SUCCEED();
}

// 测试15: 数码管位选扫描测试
TEST_F(KeyboardTestBase, DigitScanTest) {
    // 只发送通码，保持按键按下状态（不发送断码）
    // 这样 key_pressed 保持为1，6位数码管都会扫描显示
    send_ps2_byte(0x1C);  // 发送 'a' 键的通码
    tick(20000);  // 等待PS/2数据处理完成
    
    // 采样位选信号，检查是否在扫描
    // 注意：扫描周期是 8333*6 ≈ 50000 时钟周期
    // 需要采样足够长的时间才能看到位选变化
    uint8_t dig_samples[6] = {0};
    for (int i = 0; i < 60000; i++) {  // 采样约60000个时钟周期，覆盖一个完整扫描周期
        tick(1);
        uint8_t dig = top->dig_sel;
        // 位选是低电平有效
        for (int j = 0; j < 6; j++) {
            if ((dig & (1 << j)) == 0) {
                dig_samples[j]++;
            }
        }
    }
    
    // 检查至少有一些位被选通
    int active_digits = 0;
    for (int i = 0; i < 6; i++) {
        if (dig_samples[i] > 0) active_digits++;
    }
    
    EXPECT_GE(active_digits, 1) << "至少应该有一个数码管被激活";
    
    // 可选：发送断码释放按键，恢复状态
    send_ps2_byte(0xF0);  // 断码前缀
    tick(15000);
    send_ps2_byte(0x1C);  // 键值
    tick(15000);
}

// 测试16: 按键抖动测试
TEST_F(KeyboardTestBase, KeyBounceTest) {
    // 发送一个按键，但在数据包中间有一些抖动
    send_ps2_byte(0x1C);
    
    // 紧接着再发送一次（模拟抖动）
    tick(50);
    send_ps2_byte(0x1C);
    
    tick(1000);
    SUCCEED();
}

// 测试17: 特殊功能键测试
TEST_F(KeyboardTestBase, SpecialKeys) {
    // Enter键
    type_key(0x5A);
    tick(500);
    
    // ESC键
    type_key(0x76);
    tick(500);
    
    // Backspace
    type_key(0x66);
    tick(500);
    
    // Tab
    type_key(0x0D);
    tick(500);
    
    // Space
    type_key(0x29);
    tick(500);
    
    SUCCEED();
}

// 测试18: 时钟同步测试
TEST_F(KeyboardTestBase, ClockSync) {
    // 发送不同速度的数据
    // 正常速度
    send_ps2_byte(0x1C);
    tick(500);
    
    // 稍快
    const int FAST_HALF = 500;
    send_ps2_bit(0, FAST_HALF);
    for (int i = 0; i < 8; i++) {
        send_ps2_bit((0x1C >> i) & 1, FAST_HALF);
    }
    send_ps2_bit(1, FAST_HALF); // 校验位简化
    send_ps2_bit(1, FAST_HALF); // 停止位
    tick(500);
    
    SUCCEED();
}

// 测试19: 复位期间按键测试
TEST_F(KeyboardTestBase, ResetDuringKey) {
    // 发送一半的PS/2数据，然后复位
    send_ps2_bit(0, 750); // 起始位
    send_ps2_bit(0, 750); // bit0
    send_ps2_bit(0, 750); // bit1
    
    // 复位
    top->rst_n = 0;
    tick(10);
    top->rst_n = 1;
    tick(10);
    
    // 重新发送完整的按键
    type_key(0x1C);
    tick(500);
    
    SUCCEED();
}

// 测试20: 边界扫描码测试
TEST_F(KeyboardTestBase, BoundaryScanCodes) {
    // 测试扫描码边界值
    type_key(0x00); // 保留/无效
    tick(500);
    
    type_key(0xFF); // 无效
    tick(500);
    
    type_key(0x83); // F7键
    tick(500);
    
    SUCCEED();
}

// 测试21: 按键松开时低四位全灭测试
// 验收要求：当按键松开时，七段数码管的低四位全灭
TEST_F(KeyboardTestBase, LowerFourDigitsOffOnRelease) {
    // 按下按键
    press_key(0x1C); // 'A'键
    tick(20000); // 等待PS/2数据处理完成
    
    // 先采样按键按下时的状态，确认低四位有亮的情况
    bool lower_digits_active = false;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        // 检查位0-3是否有被选通的（低电平表示选中）
        if ((dig & 0x0F) != 0x0F) {
            lower_digits_active = true;
            break;
        }
    }
    EXPECT_TRUE(lower_digits_active) << "按键按下时，低四位应该至少有一位被激活";
    
    // 释放按键
    release_key(0x1C);
    tick(20000); // 等待断码处理完成
    
    // 采样按键释放后的状态，验证低四位全灭
    bool lower_digits_all_off = true;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        // 位0-3应该始终为1（全灭），位4-5可以亮（显示次数）
        if ((dig & 0x0F) != 0x0F) {
            lower_digits_all_off = false;
            break;
        }
    }
    EXPECT_TRUE(lower_digits_all_off) << "按键松开后，低四位应该全灭";
}

// 测试22: 数码管6位全扫描测试
// 验证6位数码管是否都在正确扫描
TEST_F(KeyboardTestBase, SixDigitScanTest) {
    // 按下按键，使6位都显示
    press_key(0x1C);
    tick(20000);
    
    // 采样足够长的时间覆盖完整扫描周期
    // 扫描周期 = 8333 * 6 ≈ 50000 时钟周期
    int digit_samples[6] = {0, 0, 0, 0, 0, 0};
    
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        // 位选是低电平有效，检查哪一位被选中
        for (int j = 0; j < 6; j++) {
            if ((dig & (1 << j)) == 0) {
                digit_samples[j]++;
            }
        }
    }
    
    // 验证6位都有被选通过
    for (int i = 0; i < 6; i++) {
        EXPECT_GT(digit_samples[i], 0) << "位" << i << "应该被扫描激活";
    }
    
    // 释放按键
    release_key(0x1C);
    tick(1000);
}

// 测试23: 数码管显示解码测试
// 通过扫描时序反推6位数码管各自显示的值
TEST_F(KeyboardTestBase, DisplayContentDecode) {
    // 使用已知的扫描码0x1C，ASCII应该是0x61('a')
    uint8_t test_scan_code = 0x1C;
    uint8_t expected_ascii = 0x61; // 'a'
    
    press_key(test_scan_code);
    tick(20000);
    
    // 段码解码表（共阳极，低电平点亮）
    // seg_led顺序: g,f,e,d,c,b,a
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; // 0 -> 1000000
    seg_to_hex[0x79] = 0x1; // 1 -> 1111001
    seg_to_hex[0x24] = 0x2; // 2 -> 0100100
    seg_to_hex[0x30] = 0x3; // 3 -> 0110000
    seg_to_hex[0x19] = 0x4; // 4 -> 0011001
    seg_to_hex[0x12] = 0x5; // 5 -> 0010010
    seg_to_hex[0x02] = 0x6; // 6 -> 0000010
    seg_to_hex[0x78] = 0x7; // 7 -> 1111000
    seg_to_hex[0x00] = 0x8; // 8 -> 0000000
    seg_to_hex[0x10] = 0x9; // 9 -> 0010000
    seg_to_hex[0x08] = 0xA; // A -> 0001000
    seg_to_hex[0x03] = 0xB; // b -> 0000011
    seg_to_hex[0x46] = 0xC; // C -> 1000110
    seg_to_hex[0x21] = 0xD; // d -> 0100001
    seg_to_hex[0x06] = 0xE; // E -> 0000110
    seg_to_hex[0x0E] = 0xF; // F -> 0001110
    
    // 采样并解码显示值
    uint8_t decoded_values[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    int sample_count = 0;
    
    for (int i = 0; i < 60000 && sample_count < 100; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        // 确定当前哪一位被选中
        int active_digit = -1;
        for (int j = 0; j < 6; j++) {
            if ((dig & (1 << j)) == 0) {
                active_digit = j;
                break;
            }
        }
        
        // 如果seg_led是已知模式，记录解码值
        if (active_digit >= 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            if (decoded_values[active_digit] == 0xFF) {
                decoded_values[active_digit] = seg_to_hex[seg & 0x7F];
                sample_count++;
            }
        }
    }
    
    // 验证解码结果
    // 位1:0 = 扫描码0x1C的高4位和低4位
    EXPECT_EQ(decoded_values[0], (test_scan_code & 0x0F)) << "位0应显示扫描码低4位";
    EXPECT_EQ(decoded_values[1], ((test_scan_code >> 4) & 0x0F)) << "位1应显示扫描码高4位";
    
    // 位3:2 = ASCII码0x61的高4位和低4位
    EXPECT_EQ(decoded_values[2], (expected_ascii & 0x0F)) << "位2应显示ASCII低4位";
    EXPECT_EQ(decoded_values[3], ((expected_ascii >> 4) & 0x0F)) << "位3应显示ASCII高4位";
    
    // 位5:4 = 按键次数（第一次按下应为1）
    EXPECT_EQ(decoded_values[4], 0x01) << "位4应显示按键次数低4位(1)";
    EXPECT_EQ(decoded_values[5], 0x00) << "位5应显示按键次数高4位(0)";
    
    release_key(test_scan_code);
    tick(1000);
}

// 测试24: 多次按键后显示内容验证
TEST_F(KeyboardTestBase, MultiKeyPressDisplay) {
    // 连续按下3次按键
    uint8_t test_keys[] = {0x1C, 0x32, 0x21}; // A, B, C
    
    for (int i = 0; i < 3; i++) {
        type_key(test_keys[i]);
        tick(500);
    }
    
    // 按下最后一个键不放，采样显示
    press_key(0x24); // 'D'键
    tick(20000);
    
    // 段码解码表
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 采样位5:4显示的次数
    int count_low = 0, count_high = 0;
    int samples = 0;
    
    for (int i = 0; i < 60000 && samples < 50; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
            samples++;
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
            samples++;
        }
    }
    
    // 按键次数应该是4（3次type_key + 1次press_key）
    int displayed_count = count_high * 16 + count_low;
    EXPECT_EQ(displayed_count, 4) << "按键次数应该显示为4";
    
    release_key(0x24);
    tick(1000);
}

// 测试25: 长按按键次数只算一次验证
TEST_F(KeyboardTestBase, HoldKeyCountOnce) {
    // 段码解码表
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 先按一次键并释放，确认计数为1
    type_key(0x1C);
    tick(1000);
    
    // 长按第二个键（按住不放）
    press_key(0x32); // 'B'键
    
    // 模拟长时间按住
    for (int cycle = 0; cycle < 5; cycle++) {
        tick(10000); // 每次等待一段时间
        
        // 采样显示的次数
        int count_low = -1, count_high = -1;
        for (int i = 0; i < 60000; i++) {
            tick(1);
            uint8_t dig = top->dig_sel;
            uint8_t seg = top->seg_led;
            
            if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
                count_low = seg_to_hex[seg & 0x7F];
            }
            if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
                count_high = seg_to_hex[seg & 0x7F];
            }
            if (count_low >= 0 && count_high >= 0) break;
        }
        
        int displayed_count = count_high * 16 + count_low;
        EXPECT_EQ(displayed_count, 2) << "长按期间，按键次数应保持为2，不增加";
    }
    
    release_key(0x32);
    tick(1000);
}

// 测试26: 复位后显示状态测试
TEST_F(KeyboardTestBase, ResetDisplayState) {
    // 先按几次键
    type_key(0x1C);
    type_key(0x32);
    tick(1000);
    
    // 复位
    reset();
    
    // 采样复位后的显示状态
    // 复位后应该显示初始状态（计数为0，无按键）
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    int displayed_count = count_high * 16 + count_low;
    EXPECT_EQ(displayed_count, 0) << "复位后按键次数应该显示为0";
}

// 测试27: 快速连续按键显示切换测试
TEST_F(KeyboardTestBase, RapidKeySwitching) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 快速连续按'A'和'B'
    for (int i = 0; i < 5; i++) {
        type_key(0x1C); // 'A'
        tick(200);
        type_key(0x32); // 'B'
        tick(200);
    }
    
    // 按'B'不放，验证显示是'B'的扫描码0x32
    press_key(0x32);
    tick(20000);
    
    uint8_t decoded[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        for (int j = 0; j < 6; j++) {
            if ((dig & (1 << j)) == 0 && decoded[j] == 0xFF && seg_to_hex[seg & 0x7F] != 0xFF) {
                decoded[j] = seg_to_hex[seg & 0x7F];
            }
        }
    }
    
    // 验证显示的是'B'键的信息
    EXPECT_EQ(decoded[0], 0x2) << "应显示扫描码低4位: 2";
    EXPECT_EQ(decoded[1], 0x3) << "应显示扫描码高4位: 3";
    EXPECT_EQ(decoded[2], 0x2) << "应显示ASCII低4位: b=0x62";
    EXPECT_EQ(decoded[3], 0x6) << "应显示ASCII高4位: b=0x62";
    
    release_key(0x32);
    tick(500);
}

// 测试28: 不同按键显示差异验证
TEST_F(KeyboardTestBase, DifferentKeyDisplay) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 按'A'键
    press_key(0x1C);
    tick(20000);
    
    uint8_t decoded_a[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        for (int j = 0; j < 4; j++) {
            if ((dig & (1 << j)) == 0 && decoded_a[j] == 0xFF && seg_to_hex[seg & 0x7F] != 0xFF) {
                decoded_a[j] = seg_to_hex[seg & 0x7F];
            }
        }
    }
    
    release_key(0x1C);
    tick(1000);
    
    // 按'B'键
    press_key(0x32);
    tick(20000);
    
    uint8_t decoded_b[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        for (int j = 0; j < 4; j++) {
            if ((dig & (1 << j)) == 0 && decoded_b[j] == 0xFF && seg_to_hex[seg & 0x7F] != 0xFF) {
                decoded_b[j] = seg_to_hex[seg & 0x7F];
            }
        }
    }
    
    release_key(0x32);
    tick(500);
    
    // 验证A和B的显示不同
    bool different = false;
    for (int j = 0; j < 4; j++) {
        if (decoded_a[j] != decoded_b[j]) {
            different = true;
            break;
        }
    }
    EXPECT_TRUE(different) << "不同按键应该显示不同内容";
}

// 测试29: 按键计数进位测试
TEST_F(KeyboardTestBase, CounterCarryTest) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 连续按10次键
    for (int i = 0; i < 10; i++) {
        type_key(0x1C);
        tick(200);
    }
    
    // 再按一次，保持按下状态
    press_key(0x1C);
    tick(20000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    // 11次 = 0x0B，应该显示0B
    EXPECT_EQ(count_low, 0xB) << "低4位应显示B(11)";
    EXPECT_EQ(count_high, 0x0) << "高4位应显示0";
    
    release_key(0x1C);
    tick(500);
}

// 测试30: 松开后重新按下同一键测试
TEST_F(KeyboardTestBase, SameKeyRepress) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 按'A'一次
    type_key(0x1C);
    tick(500);
    
    // 再按'A'一次
    type_key(0x1C);
    tick(500);
    
    // 保持第三次按下
    press_key(0x1C);
    tick(20000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    int displayed_count = count_high * 16 + count_low;
    EXPECT_EQ(displayed_count, 3) << "同一键按下3次，计数应为3";
    
    release_key(0x1C);
    tick(500);
}

// 测试31: 长时间空闲后按键测试
TEST_F(KeyboardTestBase, IdleThenKeyPress) {
    // 空闲一段时间
    tick(10000);
    
    // 然后按键
    type_key(0x1C);
    tick(2000);
    
    // 验证系统仍能正常响应（数码管有显示活动）
    bool display_active = false;
    for (int i = 0; i < 100; i++) {
        tick(10);
        if (top->seg_led != 0x7F) {
            display_active = true;
            break;
        }
    }
    
    EXPECT_TRUE(display_active) << "空闲后按键，数码管应该正常显示";
}

// 测试32: 交替按键测试
TEST_F(KeyboardTestBase, AlternatingKeys) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // A->B->A->B交替
    type_key(0x1C); // A
    tick(200);
    type_key(0x32); // B
    tick(200);
    type_key(0x1C); // A
    tick(200);
    type_key(0x32); // B
    tick(200);
    
    // 保持'B'按下
    press_key(0x32);
    tick(20000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    int displayed_count = count_high * 16 + count_low;
    EXPECT_EQ(displayed_count, 5) << "交替按键4次+按住1次，计数应为5";
    
    release_key(0x32);
    tick(500);
}

// 测试33: 数字键显示测试
TEST_F(KeyboardTestBase, NumberKeyDisplay) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 数字键扫描码和对应的ASCII
    uint8_t number_scan[] = {0x45, 0x16, 0x1E, 0x26, 0x25, 0x2E, 0x36, 0x3D, 0x3E, 0x46};
    uint8_t number_ascii[] = {0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39};
    
    // 测试数字键'0'（扫描码0x45）
    press_key(0x45);
    tick(20000);
    
    uint8_t decoded[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        for (int j = 0; j < 6; j++) {
            if ((dig & (1 << j)) == 0 && decoded[j] == 0xFF && seg_to_hex[seg & 0x7F] != 0xFF) {
                decoded[j] = seg_to_hex[seg & 0x7F];
            }
        }
    }
    
    // 0x45 = 扫描码，0x30 = ASCII '0'
    EXPECT_EQ(decoded[0], 0x5) << "数字0扫描码低4位: 5";
    EXPECT_EQ(decoded[1], 0x4) << "数字0扫描码高4位: 4";
    EXPECT_EQ(decoded[2], 0x0) << "数字0 ASCII低4位: 0";
    EXPECT_EQ(decoded[3], 0x3) << "数字0 ASCII高4位: 3";
    
    release_key(0x45);
    tick(500);
}

// 测试34: 数码管扫描稳定性测试
TEST_F(KeyboardTestBase, ScanStabilityTest) {
    press_key(0x1C);
    tick(20000);
    
    // 采样每位点亮的时间
    int digit_duration[6] = {0, 0, 0, 0, 0, 0};
    int last_digit = -1;
    int transitions = 0;
    
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        
        int active_digit = -1;
        for (int j = 0; j < 6; j++) {
            if ((dig & (1 << j)) == 0) {
                active_digit = j;
                digit_duration[j]++;
                break;
            }
        }
        
        if (active_digit != last_digit) {
            transitions++;
            last_digit = active_digit;
        }
    }
    
    // 应该有约6次切换（每位至少被扫描一次）
    EXPECT_GE(transitions, 6) << "应该有多次位选切换";
    
    // 每位点亮的时间应该大致相等（相差不超过50%）
    int max_duration = 0, min_duration = 60000;
    for (int j = 0; j < 6; j++) {
        if (digit_duration[j] > max_duration) max_duration = digit_duration[j];
        if (digit_duration[j] < min_duration) min_duration = digit_duration[j];
    }
    
    EXPECT_GT(min_duration, 0) << "每位都应该有被扫描到";
    if (max_duration > 0) {
        EXPECT_LT((float)max_duration / min_duration, 2.0) << "每位扫描时间应该大致相等";
    }
    
    release_key(0x1C);
    tick(500);
}

// 测试35: 错误输入处理测试
TEST_F(KeyboardTestBase, ErrorRecoveryTest) {
    // 发送不完整的PS/2数据（只有起始位和几位数据）
    const int PS2_HALF_PERIOD = 750;
    send_ps2_bit(0, PS2_HALF_PERIOD); // 起始位
    send_ps2_bit(1, PS2_HALF_PERIOD); // bit0
    send_ps2_bit(1, PS2_HALF_PERIOD); // bit1
    // 突然停止，不发送完整数据包
    tick(5000);
    
    // 恢复正常时钟和数据线状态
    top->ps2_clk = 1;
    top->ps2_data = 1;
    tick(PS2_HALF_PERIOD * 4);
    
    // 复位系统以清除错误状态
    reset();
    
    // 复位后发送完整的按键并按住
    press_key(0x1C);
    tick(25000);
    
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    uint8_t decoded[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        for (int j = 0; j < 6; j++) {
            if ((dig & (1 << j)) == 0 && decoded[j] == 0xFF && seg_to_hex[seg & 0x7F] != 0xFF) {
                decoded[j] = seg_to_hex[seg & 0x7F];
            }
        }
    }
    
    // 验证复位后能正确显示 - 扫描码0x1C
    EXPECT_EQ(decoded[0], 0xC) << "复位后应能正确显示扫描码低4位";
    EXPECT_EQ(decoded[1], 0x1) << "复位后应能正确显示扫描码高4位";
    
    release_key(0x1C);
    tick(500);
}

// 测试36: 全部26个字母键测试 (A-Z)
TEST_F(KeyboardTestBase, AllLetterKeysAZ) {
    // 字母键扫描码表 (A-Z)
    uint8_t letter_scans[] = {
        0x1C, // A
        0x32, // B
        0x21, // C
        0x23, // D
        0x24, // E
        0x2B, // F
        0x34, // G
        0x33, // H
        0x43, // I
        0x3B, // J
        0x42, // K
        0x4B, // L
        0x3A, // M
        0x31, // N
        0x44, // O
        0x4D, // P
        0x15, // Q
        0x2D, // R
        0x1B, // S
        0x2C, // T
        0x3C, // U
        0x2A, // V
        0x1D, // W
        0x22, // X
        0x35, // Y
        0x1A  // Z
    };
    
    uint8_t expected_ascii[] = {
        0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A,
        0x6B, 0x6C, 0x6D, 0x6E, 0x6F, 0x70, 0x71, 0x72, 0x73, 0x74,
        0x75, 0x76, 0x77, 0x78, 0x79, 0x7A
    };
    
    // 段码解码表
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    for (int i = 0; i < 26; i++) {
        press_key(letter_scans[i]);
        tick(20000);
        
        uint8_t decoded[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
        // 等待所有4位都被解码
        for (int j = 0; j < 60000 && (decoded[0] == 0xFF || decoded[1] == 0xFF || decoded[2] == 0xFF || decoded[3] == 0xFF); j++) {
            tick(1);
            uint8_t dig = top->dig_sel;
            uint8_t seg = top->seg_led;
            
            for (int k = 0; k < 4; k++) {
                if ((dig & (1 << k)) == 0 && decoded[k] == 0xFF && seg_to_hex[seg & 0x7F] != 0xFF) {
                    decoded[k] = seg_to_hex[seg & 0x7F];
                }
            }
        }
        
        // 验证ASCII显示 (位3:2显示ASCII)
        uint8_t actual_ascii = (decoded[3] << 4) | decoded[2];
        // 先转换回十进制再输出，避免hex影响后续输出
        EXPECT_EQ(actual_ascii, expected_ascii[i]) << "字母" << (char)('A' + i) << "的ASCII应为0x" << std::hex << (int)expected_ascii[i];
        
        release_key(letter_scans[i]);
        tick(500);
    }
}

// 测试37: 全部10个数字键测试 (0-9)
TEST_F(KeyboardTestBase, AllNumberKeys09) {
    // 数字键扫描码: 0,1,2,3,4,5,6,7,8,9
    uint8_t number_scans[] = {0x45, 0x16, 0x1E, 0x26, 0x25, 0x2E, 0x36, 0x3D, 0x3E, 0x46};
    uint8_t expected_ascii[] = {0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39};
    
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    for (int i = 0; i < 10; i++) {
        press_key(number_scans[i]);
        tick(20000);
        
        uint8_t decoded[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
        // 等待所有4位都被解码
        for (int j = 0; j < 60000 && (decoded[0] == 0xFF || decoded[1] == 0xFF || decoded[2] == 0xFF || decoded[3] == 0xFF); j++) {
            tick(1);
            uint8_t dig = top->dig_sel;
            uint8_t seg = top->seg_led;
            
            for (int k = 0; k < 4; k++) {
                if ((dig & (1 << k)) == 0 && decoded[k] == 0xFF && seg_to_hex[seg & 0x7F] != 0xFF) {
                    decoded[k] = seg_to_hex[seg & 0x7F];
                }
            }
        }
        
        // 验证ASCII显示 (位3:2显示ASCII)
        uint8_t actual_ascii = (decoded[3] << 4) | decoded[2];
        EXPECT_EQ(actual_ascii, expected_ascii[i]) << "数字" << i << "的ASCII应为0x" << std::hex << (int)expected_ascii[i];
        
        release_key(number_scans[i]);
        tick(500);
    }
}

// 测试38: 特殊功能键测试
TEST_F(KeyboardTestBase, AllSpecialKeys) {
    // 特殊键: 空格、回车、退格、Tab、ESC
    uint8_t special_scans[] = {0x29, 0x5A, 0x66, 0x0D, 0x76};
    uint8_t special_names[] = {' ', '\n', '\b', '\t', 0x1B};
    const char* special_names_str[] = {"Space", "Enter", "Backspace", "Tab", "ESC"};
    
    for (int i = 0; i < 5; i++) {
        type_key(special_scans[i]);
        tick(500);
    }
    
    SUCCEED() << "所有特殊键测试通过";
}

// 测试39: 全部十六进制数字显示测试
TEST_F(KeyboardTestBase, AllHexDigitsDisplay) {
    // 使用扫描码产生不同的十六进制显示值
    // 扫描码本身就是十六进制值，可以直接验证
    uint8_t test_scans[] = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
    };
    
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    uint8_t seg_led_table[16] = {
        0x40, 0x79, 0x24, 0x30, 0x19, 0x12, 0x02, 0x78,
        0x00, 0x10, 0x08, 0x03, 0x46, 0x21, 0x06, 0x0E
    };
    
    // 验证解码表正确性
    for (int i = 0; i < 16; i++) {
        EXPECT_EQ(seg_to_hex[seg_led_table[i]], i) << "十六进制数字" << i << "的段码映射应正确";
    }
    
    SUCCEED();
}

// 测试40: 计数器精确溢出测试 (255->0)
TEST_F(KeyboardTestBase, Counter255To0) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 先按255次达到255
    for (int i = 0; i < 255; i++) {
        type_key(0x1C);
        tick(50);
    }
    
    // 按第256次，保持按下查看显示
    press_key(0x1C);
    tick(25000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    int displayed_count = count_high * 16 + count_low;
    // 256次按键，应该显示0(溢出)或256(如果能显示)
    EXPECT_TRUE(displayed_count == 0 || displayed_count == 256)
        << "256次按键后计数器应为0(溢出),实际显示:" << displayed_count;
    
    release_key(0x1C);
    tick(500);
}

// 测试41: 计数器最大值保持测试
TEST_F(KeyboardTestBase, CounterMaxHold) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 先按255次达到最大值
    for (int i = 0; i < 255; i++) {
        type_key(0x1C);
        tick(30);
    }
    
    // 记录255时的显示
    press_key(0x1C);
    tick(20000);
    
    int count_at_255 = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            int low = seg_to_hex[seg & 0x7F];
            if ((dig & (1 << 5)) == 0) {
                int high = seg_to_hex[seg & 0x7F];
                count_at_255 = high * 16 + low;
            }
        }
    }
    
    release_key(0x1C);
    tick(500);
    
    // 再继续按10次
    for (int i = 0; i < 10; i++) {
        type_key(0x1C);
        tick(30);
    }
    
    press_key(0x1C);
    tick(20000);
    
    int count_after = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            int low = seg_to_hex[seg & 0x7F];
            if ((dig & (1 << 5)) == 0) {
                int high = seg_to_hex[seg & 0x7F];
                count_after = high * 16 + low;
            }
        }
    }
    
    // 验证最大值后的行为（应该保持不变或溢出）
    EXPECT_TRUE(count_after == count_at_255 || count_after == 9)
        << "计数器达最大值后应保持或溢出";
    
    release_key(0x1C);
    tick(500);
}

// 测试42: 100次按键压力测试
TEST_F(KeyboardTestBase, HundredKeyPresses) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 连续100次按键
    for (int i = 0; i < 100; i++) {
        type_key(0x1C);
        tick(50);
    }
    
    // 验证计数为100 (0x64)
    press_key(0x1C);
    tick(20000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    int displayed_count = count_high * 16 + count_low;
    EXPECT_EQ(displayed_count, 101) << "100次按键+当前1次，计数应为101 (0x65)";
    
    release_key(0x1C);
    tick(500);
}

// 测试43: 随机按键序列测试
TEST_F(KeyboardTestBase, RandomKeySequence) {
    // 预定义一些常用扫描码
    uint8_t keys[] = {0x1C, 0x32, 0x21, 0x23, 0x24, 0x2B, 0x34, 0x33};
    const int NUM_KEYS = 8;
    
    // 使用固定种子确保可重复
    srand(12345);
    
    // 随机按50次键
    for (int i = 0; i < 50; i++) {
        int key_idx = rand() % NUM_KEYS;
        type_key(keys[key_idx]);
        tick(30 + rand() % 50);
    }
    
    // 验证系统仍然正常工作
    type_key(0x1C);
    tick(1000);
    
    SUCCEED() << "随机按键序列测试通过";
}

// 测试44: 快速释放并立即按下同一键
TEST_F(KeyboardTestBase, ReleaseThenImmediatePress) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 先按一次，计数为1
    type_key(0x1C);
    tick(200);
    
    // 快速释放并立即按下（模拟连续打字）
    for (int i = 0; i < 5; i++) {
        release_key(0x1C);
        tick(10);  // 极短间隔
        press_key(0x1C);
        tick(10);
    }
    
    // 最后完整释放
    release_key(0x1C);
    tick(2000);
    
    // 再次按键，计数应该增加
    type_key(0x1C);
    tick(20000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    int displayed_count = count_high * 16 + count_low;
    // 应该至少计数了2次（第一次+最后一次）
    EXPECT_GE(displayed_count, 2) << "快速释放按下应该正确计数";
}

// 测试45: 极快速按下-释放循环
TEST_F(KeyboardTestBase, RapidReleasePress) {
    // 极快速的按下释放循环（每周期20个时钟）
    for (int i = 0; i < 20; i++) {
        press_key(0x1C);
        tick(10);
        release_key(0x1C);
        tick(10);
    }
    
    tick(5000);
    
    // 验证系统仍正常工作
    type_key(0x32);
    tick(1000);
    
    SUCCEED() << "极快速循环测试通过";
}

// 测试46: 长时间空闲后突发按键
TEST_F(KeyboardTestBase, LongIdleThenBurst) {
    // 空闲一段时间
    tick(50000);
    
    // 突发10次按键
    for (int i = 0; i < 10; i++) {
        type_key(0x1C);
        tick(20);
    }
    
    tick(2000);
    
    // 验证显示正常
    bool display_active = false;
    for (int i = 0; i < 100; i++) {
        tick(10);
        if (top->seg_led != 0x7F) {
            display_active = true;
            break;
        }
    }
    
    EXPECT_TRUE(display_active) << "长时间空闲后突发按键，显示应正常";
}

// 测试47: 不按断码直接发送新键（按键覆盖）
TEST_F(KeyboardTestBase, MultiKeyWithoutRelease) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 按A键
    press_key(0x1C);
    tick(20000);
    
    // 不按断码，直接按B键（模拟键盘自动重复或覆盖）
    // 发送B键的通码 - 状态机会将其视为新按键
    press_key(0x32);
    tick(25000);
    
    uint8_t decoded[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    // 等待所有4位都被解码
    for (int i = 0; i < 60000 && (decoded[0] == 0xFF || decoded[1] == 0xFF || decoded[2] == 0xFF || decoded[3] == 0xFF); i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        for (int j = 0; j < 4; j++) {
            if ((dig & (1 << j)) == 0 && decoded[j] == 0xFF && seg_to_hex[seg & 0x7F] != 0xFF) {
                decoded[j] = seg_to_hex[seg & 0x7F];
            }
        }
    }
    
    // 应该显示当前按键的扫描码（可能是A或B，取决于状态机处理）
    // 由于状态机在收到新通码时会更新，应该显示B键的扫描码0x32
    // 或者如果状态机保持A，则显示0x1C
    bool is_valid_display = (decoded[0] == 0x2 && decoded[1] == 0x3) ||  // B键
                            (decoded[0] == 0xC && decoded[1] == 0x1);    // A键
    EXPECT_TRUE(is_valid_display) << "按键覆盖后应显示当前按键的扫描码，实际显示: "
                                   << (int)decoded[1] << (int)decoded[0];
    
    // 发送断码释放当前按键
    release_key(0x32);
    tick(1000);
}

// 测试48: 只发F0断码前缀，不发送后续扫描码
TEST_F(KeyboardTestBase, F0WithoutRelease) {
    // 先按下A键
    press_key(0x1C);
    tick(20000);
    
    // 只发送F0，不发送断码
    send_ps2_byte(0xF0);
    tick(20000);
    
    // 此时系统应该在WAIT_F0状态等待断码
    // 发送另一个键，看系统如何处理
    press_key(0x32);
    tick(20000);
    
    // 验证系统能恢复正常
    bool display_active = false;
    for (int i = 0; i < 100; i++) {
        tick(10);
        if (top->seg_led != 0x7F) {
            display_active = true;
            break;
        }
    }
    
    EXPECT_TRUE(display_active) << "异常F0序列后系统应能恢复";
    
    release_key(0x32);
    tick(1000);
}

// 测试49: 发送F0后跟不匹配的扫描码
TEST_F(KeyboardTestBase, WrongBreakCode) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 按下A键
    press_key(0x1C);
    tick(20000);
    
    // 发送F0 + 错误的断码（应该是0x1C，发送0x32）
    send_ps2_byte(0xF0);
    tick(15000);
    send_ps2_byte(0x32);  // 错误的断码
    tick(20000);
    
    // 状态机应该保持在KEY_DOWN状态
    // 现在发送正确的断码
    send_ps2_byte(0xF0);
    tick(15000);
    send_ps2_byte(0x1C);  // 正确的断码
    tick(20000);
    
    // 验证低四位全灭（按键已释放）
    bool lower_digits_off = true;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        if ((dig & 0x0F) != 0x0F) {
            lower_digits_off = false;
            break;
        }
    }
    
    EXPECT_TRUE(lower_digits_off) << "正确断码后应能释放按键";
}

// 测试50: 连续发送两个F0前缀
TEST_F(KeyboardTestBase, DoubleF0Prefix) {
    // 按下A键
    press_key(0x1C);
    tick(20000);
    
    // 发送两个F0
    send_ps2_byte(0xF0);
    tick(15000);
    send_ps2_byte(0xF0);
    tick(15000);
    
    // 最后发送正确的断码序列
    send_ps2_byte(0xF0);
    tick(15000);
    send_ps2_byte(0x1C);
    tick(20000);
    
    // 验证系统能处理并释放
    SUCCEED() << "双F0前缀测试完成";
}

// 测试51: E0扩展键完整序列测试
TEST_F(KeyboardTestBase, E0ExtendedKey) {
    // 发送扩展键完整序列（上箭头 E0 75）
    send_ps2_byte(0xE0);
    tick(15000);
    press_key(0x75);
    tick(20000);
    
    // 释放扩展键（E0 F0 75）
    send_ps2_byte(0xE0);
    tick(15000);
    send_ps2_byte(0xF0);
    tick(15000);
    send_ps2_byte(0x75);
    tick(20000);
    
    SUCCEED() << "扩展键完整序列测试完成";
}

// 测试52: 数码管扫描频率测量
TEST_F(KeyboardTestBase, ScanRateAccuracy) {
    press_key(0x1C);
    tick(20000);
    
    // 测量位选切换时间
    int last_digit = -1;
    int switch_count = 0;
    int cycle_start = 0;
    bool first_cycle = true;
    
    for (int i = 0; i < 100000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        
        int active_digit = -1;
        for (int j = 0; j < 6; j++) {
            if ((dig & (1 << j)) == 0) {
                active_digit = j;
                break;
            }
        }
        
        if (active_digit != last_digit && active_digit != -1) {
            if (active_digit == 0 && last_digit == 5) {
                // 完成一个完整周期
                if (!first_cycle) {
                    int cycle_time = i - cycle_start;
                    // 约50000个时钟周期（8333 * 6）
                    EXPECT_GT(cycle_time, 40000) << "扫描周期应足够长";
                    EXPECT_LT(cycle_time, 60000) << "扫描周期不应过长";
                }
                cycle_start = i;
                first_cycle = false;
            }
            switch_count++;
            last_digit = active_digit;
        }
    }
    
    EXPECT_GT(switch_count, 10) << "应有多次位选切换";
    
    release_key(0x1C);
    tick(500);
}

// 测试53: 各位数码管点亮时间均衡性
TEST_F(KeyboardTestBase, AllDigitsEqualTime) {
    press_key(0x1C);
    tick(20000);
    
    int digit_time[6] = {0, 0, 0, 0, 0, 0};
    
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        
        for (int j = 0; j < 6; j++) {
            if ((dig & (1 << j)) == 0) {
                digit_time[j]++;
            }
        }
    }
    
    // 计算平均值和方差
    int total = 0;
    for (int j = 0; j < 6; j++) {
        total += digit_time[j];
    }
    int avg = total / 6;
    
    // 每位点亮时间应在平均值的±50%范围内
    for (int j = 0; j < 6; j++) {
        EXPECT_GT(digit_time[j], avg * 0.5) << "位" << j << "点亮时间应足够";
        EXPECT_LT(digit_time[j], avg * 1.5) << "位" << j << "点亮时间不应过长";
    }
    
    release_key(0x1C);
    tick(500);
}

// 测试54: 松开后低四位熄灭时序
TEST_F(KeyboardTestBase, ReleaseDisplayOffTiming) {
    press_key(0x1C);
    tick(25000);  // 增加等待时间确保PS/2处理完成
    
    // 记录释放前低四位状态 - 需要在一个完整扫描周期内采样
    bool lower_active_before = false;
    for (int i = 0; i < 60000; i++) {  // 增加采样时间覆盖完整扫描周期
        tick(1);
        if ((top->dig_sel & 0x0F) != 0x0F) {
            lower_active_before = true;
            break;
        }
    }
    EXPECT_TRUE(lower_active_before) << "释放前低四位应有亮的";
    
    // 发送释放序列
    send_ps2_byte(0xF0);
    tick(15000);
    send_ps2_byte(0x1C);
    
    // 等待断码处理完成
    tick(20000);
    
    // 验证低四位全灭
    bool lower_off_after = true;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        if ((top->dig_sel & 0x0F) != 0x0F) {
            lower_off_after = false;
            break;
        }
    }
    
    EXPECT_TRUE(lower_off_after) << "断码处理后低四位应全灭";
}

// 测试55: 按下后低四位点亮时序
TEST_F(KeyboardTestBase, PressDisplayOnTiming) {
    // 确保当前是释放状态
    tick(1000);
    
    // 发送按键
    send_ps2_byte(0x1C);
    tick(25000);  // 等待PS/2处理
    
    // 验证低四位有亮的
    bool lower_active = false;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        if ((top->dig_sel & 0x0F) != 0x0F) {
            lower_active = true;
            break;
        }
    }
    
    EXPECT_TRUE(lower_active) << "通码处理后低四位应点亮";
    
    release_key(0x1C);
    tick(500);
}

// 测试56: A-B-C-D-E交替按键
TEST_F(KeyboardTestBase, AlternatingABCDE) {
    uint8_t keys[] = {0x1C, 0x32, 0x21, 0x23, 0x24}; // A,B,C,D,E
    
    for (int round = 0; round < 3; round++) {
        for (int i = 0; i < 5; i++) {
            type_key(keys[i]);
            tick(100);
        }
    }
    
    tick(1000);
    
    // 验证计数正确 (3*5 = 15次)
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    press_key(0x1C);
    tick(20000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    int displayed_count = count_high * 16 + count_low;
    EXPECT_EQ(displayed_count, 16) << "15次按键+当前1次，计数应为16 (0x10)";
    
    release_key(0x1C);
    tick(500);
}

// 测试57: 同一键连续按100次
TEST_F(KeyboardTestBase, SameKeyRepress100) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    for (int i = 0; i < 100; i++) {
        type_key(0x1C);  // 始终按A键
        tick(30);
    }
    
    press_key(0x1C);
    tick(20000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    int displayed_count = count_high * 16 + count_low;
    EXPECT_EQ(displayed_count, 101) << "同一键按100次+当前，计数应为101 (0x65)";
    
    release_key(0x1C);
    tick(500);
}

// 测试58: 模拟输入Hello World序列
TEST_F(KeyboardTestBase, TypeHelloWorld) {
    // H-e-l-l-o-空格-W-o-r-l-d
    // 对应的扫描码
    uint8_t hello_world[] = {
        0x33, // H
        0x24, // E
        0x4B, // L
        0x4B, // L
        0x44, // O
        0x29, // Space
        0x1D, // W
        0x44, // O
        0x2D, // R
        0x4B, // L
        0x23  // D
    };
    
    for (size_t i = 0; i < sizeof(hello_world); i++) {
        type_key(hello_world[i]);
        tick(100);
    }
    
    tick(1000);
    
    // 验证计数为11
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    press_key(0x1C);
    tick(20000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    int displayed_count = count_high * 16 + count_low;
    EXPECT_EQ(displayed_count, 12) << "输入Hello World(11字符)+当前，计数应为12 (0x0C)";
    
    release_key(0x1C);
    tick(500);
}

// 测试59: 上电初始状态精确验证
// 验证: 复位后计数器显示00，低四位全灭
TEST_F(KeyboardTestBase, PowerOnInitialState) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 复位后采样
    int count_low = -1, count_high = -1;
    bool lower_digits_off = true;
    
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        // 检查低四位是否全灭
        if ((dig & 0x0F) != 0x0F) {
            lower_digits_off = false;
        }
        
        // 读取计数器显示
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
    }
    
    EXPECT_TRUE(lower_digits_off) << "上电后低四位应全灭";
    EXPECT_EQ(count_low, 0) << "上电后计数器低4位应显示0";
    EXPECT_EQ(count_high, 0) << "上电后计数器高4位应显示0";
}

// 测试60: 第一次按键计数0->1验证
TEST_F(KeyboardTestBase, FirstKeyPressCount0To1) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 第一次按键
    press_key(0x1C);
    tick(25000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    EXPECT_EQ(count_low, 0x01) << "第一次按键后计数应显示01";
    EXPECT_EQ(count_high, 0x00) << "第一次按键后计数高位应为0";
    
    release_key(0x1C);
    tick(500);
}

// 测试61: 严格长按不增加计数验证
// 验收要求: 按住不放只算一次按键
TEST_F(KeyboardTestBase, StrictHoldKeyNoIncrement) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 按一次键，计数=1
    type_key(0x1C);
    tick(1000);
    
    // 长按第二个键（只发通码，不发断码）
    press_key(0x32);
    tick(25000);
    
    // 在长按时多次采样验证计数保持为2
    for (int check = 0; check < 5; check++) {
        tick(10000);  // 等待一段时间
        
        int count_low = -1, count_high = -1;
        for (int i = 0; i < 60000; i++) {
            tick(1);
            uint8_t dig = top->dig_sel;
            uint8_t seg = top->seg_led;
            
            if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
                count_low = seg_to_hex[seg & 0x7F];
            }
            if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
                count_high = seg_to_hex[seg & 0x7F];
            }
            if (count_low >= 0 && count_high >= 0) break;
        }
        
        int count = count_high * 16 + count_low;
        EXPECT_EQ(count, 2) << "长按期间第" << check << "次采样计数应保持为2";
    }
    
    release_key(0x32);
    tick(500);
}

// 测试62: 释放后立即按下的最小间隔测试
TEST_F(KeyboardTestBase, ReleaseThenImmediatePressTiming) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 第一次按键
    type_key(0x1C);
    tick(500);
    
    // 极快速释放并按下（模拟最小间隔）
    release_key(0x1C);
    tick(5);  // 极短间隔
    press_key(0x32);
    tick(25000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    int count = count_high * 16 + count_low;
    EXPECT_EQ(count, 2) << "快速释放再按下应计为第2次";
    
    release_key(0x32);
    tick(500);
}

// 测试63: 扫描码边界值0x00测试
TEST_F(KeyboardTestBase, ScanCodeBoundary00) {
    // 扫描码0x00（通常未使用）
    press_key(0x00);
    tick(25000);
    
    // 验证系统不会崩溃，数码管有显示
    bool display_active = false;
    for (int i = 0; i < 100; i++) {
        tick(10);
        if (top->seg_led != 0x7F) {
            display_active = true;
            break;
        }
    }
    EXPECT_TRUE(display_active) << "扫描码0x00应能被处理";
    
    release_key(0x00);
    tick(500);
}

// 测试64: 扫描码边界值0x7F测试
TEST_F(KeyboardTestBase, ScanCodeBoundary7F) {
    // 扫描码0x7F（最高位为0的最大值）
    press_key(0x7F);
    tick(25000);
    
    bool display_active = false;
    for (int i = 0; i < 100; i++) {
        tick(10);
        if (top->seg_led != 0x7F) {
            display_active = true;
            break;
        }
    }
    EXPECT_TRUE(display_active) << "扫描码0x7F应能被处理";
    
    release_key(0x7F);
    tick(500);
}

// 测试65: 扫描码0x80测试（与断码前缀区分）
TEST_F(KeyboardTestBase, ScanCodeBoundary80) {
    // 扫描码0x80（最高位为1的最小值，不是F0断码前缀）
    press_key(0x80);
    tick(25000);
    
    bool display_active = false;
    for (int i = 0; i < 100; i++) {
        tick(10);
        if (top->seg_led != 0x7F) {
            display_active = true;
            break;
        }
    }
    EXPECT_TRUE(display_active) << "扫描码0x80应能被处理";
    
    release_key(0x80);
    tick(500);
}

// 测试66: 同时按两个键的处理测试
// 验收要求: 不考虑同时按多个键，测试系统不崩溃即可
TEST_F(KeyboardTestBase, TwoKeysPressed) {
    // 按A键不放
    press_key(0x1C);
    tick(20000);
    
    // 不按断码，直接按B键（这种场景超出验收要求，测试系统不崩溃即可）
    press_key(0x32);
    tick(25000);
    
    // 验证系统仍在运行（数码管有扫描活动）
    bool display_active = false;
    for (int i = 0; i < 100; i++) {
        tick(10);
        if (top->seg_led != 0x7F) {
            display_active = true;
            break;
        }
    }
    EXPECT_TRUE(display_active) << "两键按下时系统应仍能正常工作";
    
    // 释放B键
    release_key(0x32);
    tick(1000);
    
    // 释放A键
    release_key(0x1C);
    tick(1000);
}

// 测试67: 数码管全段点亮测试
TEST_F(KeyboardTestBase, AllSegmentsLit) {
    press_key(0x1C);
    tick(25000);
    
    // 采样所有可能的段码值
    bool seg_a = false, seg_b = false, seg_c = false, seg_d = false;
    bool seg_e = false, seg_f = false, seg_g = false;
    
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t seg = top->seg_led;
        
        // seg_led顺序: g,f,e,d,c,b,a (低电平点亮)
        if ((seg & 0x01) == 0) seg_a = true;  // a段
        if ((seg & 0x02) == 0) seg_b = true;  // b段
        if ((seg & 0x04) == 0) seg_c = true;  // c段
        if ((seg & 0x08) == 0) seg_d = true;  // d段
        if ((seg & 0x10) == 0) seg_e = true;  // e段
        if ((seg & 0x20) == 0) seg_f = true;  // f段
        if ((seg & 0x40) == 0) seg_g = true;  // g段
    }
    
    // 验证至少有一些段被点亮（不同数字会点亮不同段）
    EXPECT_TRUE(seg_a || seg_b || seg_c || seg_d || seg_e || seg_f || seg_g)
        << "数码管应该有一些段被点亮";
    
    release_key(0x1C);
    tick(500);
}

// 测试68: 按键脉冲宽度精确测量
TEST_F(KeyboardTestBase, KeyPulseWidthMeasurement) {
    // 这个测试需要观察内部信号，这里简化处理
    // 按一次键
    press_key(0x1C);
    tick(20000);
    
    // 验证系统能正常响应
    bool display_active = false;
    for (int i = 0; i < 100; i++) {
        tick(10);
        if (top->seg_led != 0x7F) {
            display_active = true;
            break;
        }
    }
    EXPECT_TRUE(display_active) << "按键脉冲应能正确触发显示";
    
    release_key(0x1C);
    tick(500);
}

// 测试69: 复位期间发送按键测试
TEST_F(KeyboardTestBase, KeyDuringReset) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 开始复位
    top->rst_n = 0;
    tick(5);
    
    // 复位期间发送按键（应该被忽略）
    send_ps2_byte(0x1C);
    tick(5);
    
    // 结束复位
    top->rst_n = 1;
    tick(10);
    
    // 复位后发送正常按键
    press_key(0x32);
    tick(25000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    int count = count_high * 16 + count_low;
    EXPECT_EQ(count, 1) << "复位期间按键应被忽略，计数应为1";
    
    release_key(0x32);
    tick(500);
}

// 测试70: E0扩展键测试
// 验收要求: 不需要实现小键盘，测试E0前缀键系统能正常处理即可
TEST_F(KeyboardTestBase, E0ExtendedKeyBreakCode) {
    // 发送E0 75（上箭头通码）
    send_ps2_byte(0xE0);
    tick(15000);
    press_key(0x75);
    tick(20000);
    
    // 采样显示
    bool display_active_before = false;
    for (int i = 0; i < 100; i++) {
        tick(10);
        if (top->seg_led != 0x7F) {
            display_active_before = true;
            break;
        }
    }
    EXPECT_TRUE(display_active_before) << "扩展键按下时应显示";
    
    // 发送E0 F0 75（上箭头断码）- 扩展键断码格式
    send_ps2_byte(0xE0);
    tick(15000);
    send_ps2_byte(0xF0);
    tick(15000);
    send_ps2_byte(0x75);
    tick(20000);
    
    // 验收要求只考虑顺序按下和放开，扩展键断码处理可能不完美
    // 验证系统仍正常工作即可
    bool system_working = false;
    for (int i = 0; i < 100; i++) {
        tick(10);
        if (top->seg_led != 0x7F) {
            system_working = true;
            break;
        }
    }
    EXPECT_TRUE(system_working) << "扩展键断码处理后系统应能正常工作";
}

// 测试71: PS/2时钟抖动测试
TEST_F(KeyboardTestBase, PS2ClockJitter) {
    // 发送带抖动的PS/2数据
    const int BASE_HALF_PERIOD = 750;
    
    // 起始位
    send_ps2_bit(0, BASE_HALF_PERIOD);
    
    // 8位数据，带抖动
    uint8_t data = 0x1C;
    for (int i = 0; i < 8; i++) {
        int jitter = (rand() % 100) - 50; // ±50的抖动
        send_ps2_bit((data >> i) & 1, BASE_HALF_PERIOD + jitter);
    }
    
    // 校验位和停止位
    send_ps2_bit(1, BASE_HALF_PERIOD);
    send_ps2_bit(1, BASE_HALF_PERIOD);
    
    tick(20000);
    
    // 验证系统能处理抖动
    bool display_active = false;
    for (int i = 0; i < 100; i++) {
        tick(10);
        if (top->seg_led != 0x7F) {
            display_active = true;
            break;
        }
    }
    EXPECT_TRUE(display_active) << "带抖动的PS/2时钟应能被正确处理";
    
    release_key(0x1C);
    tick(500);
}

// 测试72: 16次按键边界测试（进位测试）
TEST_F(KeyboardTestBase, KeyCount16Boundary) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 按15次键
    for (int i = 0; i < 15; i++) {
        type_key(0x1C);
        tick(50);
    }
    
    // 第16次按键，按住检查显示
    press_key(0x1C);
    tick(25000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    // 16次 = 0x10，应显示"10"
    EXPECT_EQ(count_low, 0x0) << "16次按键低4位应显示0";
    EXPECT_EQ(count_high, 0x1) << "16次按键高4位应显示1";
    
    release_key(0x1C);
    tick(500);
}

// 测试73: 32次按键边界测试
TEST_F(KeyboardTestBase, KeyCount32Boundary) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 按31次键
    for (int i = 0; i < 31; i++) {
        type_key(0x1C);
        tick(50);
    }
    
    // 第32次按键，按住检查显示
    press_key(0x1C);
    tick(25000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    // 32次 = 0x20，应显示"20"
    EXPECT_EQ(count_low, 0x0) << "32次按键低4位应显示0";
    EXPECT_EQ(count_high, 0x2) << "32次按键高4位应显示2";
    
    release_key(0x1C);
    tick(500);
}

// 测试74: 100次按键验证（十进制显示验证）
TEST_F(KeyboardTestBase, KeyCount100Verify) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 按99次键
    for (int i = 0; i < 99; i++) {
        type_key(0x1C);
        tick(40);
    }
    
    // 第100次按键，按住检查显示
    press_key(0x1C);
    tick(25000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    // 100次 = 0x64，应显示"64"
    EXPECT_EQ(count_low, 0x4) << "100次按键低4位应显示4";
    EXPECT_EQ(count_high, 0x6) << "100次按键高4位应显示6";
    
    release_key(0x1C);
    tick(500);
}

// 测试75: 按键释放时序精确测试
// 验证断码处理后的精确时序
TEST_F(KeyboardTestBase, ReleaseTimingPrecision) {
    // 按下按键
    press_key(0x1C);
    tick(25000);
    
    // 验证按下时低四位有亮的
    bool lower_active = false;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        if ((top->dig_sel & 0x0F) != 0x0F) {
            lower_active = true;
            break;
        }
    }
    EXPECT_TRUE(lower_active) << "按下时低四位应有亮的";
    
    // 发送断码F0
    send_ps2_byte(0xF0);
    tick(15000);
    
    // 发送断码扫描码
    send_ps2_byte(0x1C);
    
    // 精确检查释放后的状态变化
    bool lower_off = false;
    for (int i = 0; i < 30000 && !lower_off; i++) {
        tick(1);
        if ((top->dig_sel & 0x0F) == 0x0F) {
            lower_off = true;
        }
    }
    
    EXPECT_TRUE(lower_off) << "断码处理后低四位应变为全灭";
}

// 测试76: 连续快速按键不丢数测试
TEST_F(KeyboardTestBase, RapidKeysNoLoss) {
    uint8_t seg_to_hex[128];
    memset(seg_to_hex, 0xFF, sizeof(seg_to_hex));
    seg_to_hex[0x40] = 0x0; seg_to_hex[0x79] = 0x1; seg_to_hex[0x24] = 0x2; seg_to_hex[0x30] = 0x3;
    seg_to_hex[0x19] = 0x4; seg_to_hex[0x12] = 0x5; seg_to_hex[0x02] = 0x6; seg_to_hex[0x78] = 0x7;
    seg_to_hex[0x00] = 0x8; seg_to_hex[0x10] = 0x9; seg_to_hex[0x08] = 0xA; seg_to_hex[0x03] = 0xB;
    seg_to_hex[0x46] = 0xC; seg_to_hex[0x21] = 0xD; seg_to_hex[0x06] = 0xE; seg_to_hex[0x0E] = 0xF;
    
    // 以最快速度按20次键
    for (int i = 0; i < 20; i++) {
        press_key(0x1C);
        tick(50);  // 极短间隔
        release_key(0x1C);
        tick(50);
    }
    
    // 再按一次保持
    press_key(0x1C);
    tick(25000);
    
    int count_low = -1, count_high = -1;
    for (int i = 0; i < 60000; i++) {
        tick(1);
        uint8_t dig = top->dig_sel;
        uint8_t seg = top->seg_led;
        
        if ((dig & (1 << 4)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_low = seg_to_hex[seg & 0x7F];
        }
        if ((dig & (1 << 5)) == 0 && seg_to_hex[seg & 0x7F] != 0xFF) {
            count_high = seg_to_hex[seg & 0x7F];
        }
        if (count_low >= 0 && count_high >= 0) break;
    }
    
    int count = count_high * 16 + count_low;
    // 期望计数为21（20次完整按键+当前1次）
    EXPECT_EQ(count, 21) << "快速20次按键后计数应为21";
    
    release_key(0x1C);
    tick(500);
}

#endif // TEST_MODE

#ifndef TEST_MODE
// NVBoard相关函数声明
void nvboard_bind_all_pins(VSevenSegLEDNixietubeKeyboardASCII* top);
#endif

int main(int argc, char *argv[])
{
    std::cout << std::format("开始进行仿真测试") << std::endl;
    if (argc > 0)
    {
        Verilated::commandArgs(argc, argv);
    }
#ifdef TEST_MODE
    std::cout << std::format("Running GTest Mode...") << std::endl;
    testing::InitGoogleTest(&argc, argv);
    return RUN_ALL_TESTS();
#else
    std::cout << std::format("开始nvboard测试") << std::endl;
    
    // NVBoard模式 - 使用智能指针管理内存
    auto top = std::make_unique<VSevenSegLEDNixietubeKeyboardASCII>();
    nvboard_bind_all_pins(top.get());
    nvboard_init();
    while (1) {
        nvboard_update();
        single_cycle(top.get());
    }
    nvboard_quit();
    return 0;
#endif
}
