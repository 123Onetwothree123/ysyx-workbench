#include <iostream>
#include <format>
#include <memory>
#include <set>
#include <verilated.h>
#include "Vlfsr_random_generator_top.h"
#ifdef TEST_MODE
#include <gtest/gtest.h>
class lfsr_test : public ::testing::Test
{
protected:
    std::unique_ptr<Vlfsr_random_generator_top> top;
    void SetUp() override
    {
        top = std::make_unique<Vlfsr_random_generator_top>();
        // 先释放复位（rst_n=1），让寄存器初始化
        top->KEY = 0b01; // rst_n=1（释放复位）, btn_in=0
        step_clk();
        // 再激活复位，产生下降沿触发异步复位
        top->KEY = 0; // rst_n=0（激活复位）
        step_clk();
        step_clk();
    }
    void step_clk()
    {
        top->CLOCK_50 = 0;
        top->eval();
        top->CLOCK_50 = 1;
        top->eval();
    }
    void TearDown() override
    {
        top->final();
    }
};

TEST_F(lfsr_test, LFSR_Reset)
{
    top->KEY = 0b00;
    step_clk();
    step_clk();
    EXPECT_EQ(top->random_val, 0x01);
}

TEST_F(lfsr_test, LFSR_Sequence_Correctness)
{
    // LFSR 反馈多项式: q[4]^q[3]^q[2]^q[0], 右移实现
    // 序列: 0x01 -> 0x80 -> 0x40 -> 0x20 -> 0x10 -> ...
    uint8_t expected_sequence[] = {
        0x80, 0x40, 0x20, 0x10, 0x88, 0xC4, 0xE2, 0x71,
        0x38, 0x1C, 0x8E, 0x47, 0x23, 0x91, 0x48, 0xA4
    };
    // 先释放复位
    top->KEY = 0b01;  // KEY[0]=1 (rst_n=1, 复位释放), KEY[1]=0
    step_clk();
    
    for (int i = 0; i < 16; i++)
    {
        // 按键未按下（高电平）
        top->KEY = 0b11;  // KEY[0]=1 (rst_n=1, 复位释放), KEY[1]=1 (按键未按下)
        step_clk();
        // 按键按下（从高到低）
        top->KEY = 0b01;  // KEY[0]=1 (rst_n=1, 复位释放), KEY[1]=0 (按键按下)
        for (int j = 0; j < 1048577; j++) {
            step_clk();
        }
        // 按键释放（从低到高，产生脉冲）
        top->KEY = 0b11;  // KEY[0]=1 (rst_n=1, 复位释放), KEY[1]=1 (按键释放)
        step_clk();
        
        EXPECT_EQ(top->random_val, expected_sequence[i]);
    }
}

TEST_F(lfsr_test, Debounce_Filter_Glitch)
{
    // 记录测试前的LFSR值
    uint8_t val_before = top->random_val;
    
    // 模拟按键抖动：快速翻转（模拟机械抖动，每个状态持续很短）
    // 抖动期间计数器无法计满，不应触发LFSR更新
    for (int i = 0; i < 5; i++) {
        // 按下（低电平）- 持续100个周期，远小于消抖所需的100万个周期
        top->KEY = 0b01;
        for (int j = 0; j < 100; j++) step_clk();
        
        // 释放（高电平）- 持续100个周期
        top->KEY = 0b11;
        for (int j = 0; j < 100; j++) step_clk();
    }
    
    // 验证抖动期间LFSR未更新（保持初始值0x01）
    EXPECT_EQ(top->random_val, val_before)
        << "LFSR should not update during button glitch";
    
    // 现在稳定按下按键足够长时间（超过消抖阈值）
    top->KEY = 0b01;  // 按下，KEY[1]=0
    for (int i = 0; i < 1100000; i++) {  // > 1M周期，确保消抖计数器溢出
        step_clk();
    }
    
    // 释放按键
    top->KEY = 0b11;  // 释放，KEY[1]=1
    step_clk();
    
    // 验证LFSR已更新一次（变为0x80）
    EXPECT_EQ(top->random_val, 0x80)
        << "LFSR should update to 0x80 after valid debounced press";
    
    // 再次模拟抖动，验证不会二次触发
    uint8_t val_now = top->random_val;
    for (int i = 0; i < 5; i++) {
        top->KEY = 0b01;
        for (int j = 0; j < 100; j++) step_clk();
        top->KEY = 0b11;
        for (int j = 0; j < 100; j++) step_clk();
    }
    EXPECT_EQ(top->random_val, val_now)
        << "LFSR should not update again from glitch after valid press";
}

TEST_F(lfsr_test, Debounce_Single_Pulse_Per_Press)
{
    // 验证每次完整按键操作只触发一次LFSR更新
    // 先释放复位，让系统进入工作状态
    top->KEY = 0b11;  // rst_n=1, btn_in=1(未按下)
    step_clk();
    
    uint8_t expected_sequence[] = {
        0x80, 0x40, 0x20, 0x10
    };
    
    for (int press = 0; press < 4; press++) {
        uint8_t before = top->random_val;
        
        // 模拟完整按键：按下->保持足够长->释放
        top->KEY = 0b01;  // 按下（KEY[1]=0）
        for (int i = 0; i < 1100000; i++) {
            step_clk();
        }
        top->KEY = 0b11;  // 释放（KEY[1]=1）
        step_clk();
        
        // 等待消抖模块的 triggered 信号复位（需要连续2个周期高电平）
        step_clk();
        
        // 验证只更新了一次
        EXPECT_EQ(top->random_val, expected_sequence[press])
            << "Press #" << press << " should update LFSR to expected value";
        EXPECT_NE(top->random_val, before)
            << "Press #" << press << " should change LFSR value";
    }
}

TEST_F(lfsr_test, Rapid_Valid_Presses)
{
    // 验证连续多次完整按键操作的稳定性
    // 预期LFSR序列（已知的LFSR输出序列）
    uint8_t expected_sequence[] = {
        0x80, 0x40, 0x20, 0x10, 0x88, 0xC4, 0xE2, 0x71,
        0x38, 0x1C, 0x8E, 0x47, 0x23, 0x91, 0x48, 0xA4
    };
    const int NUM_PRESSES = 16;
    
    // 确保复位已释放，系统处于工作状态
    top->KEY = 0b11;  // rst_n=1, btn_in=1(未按下)
    step_clk();
    
    // 连续执行多次有效按键
    for (int i = 0; i < NUM_PRESSES; i++) {
        uint8_t value_before = top->random_val;
        
        // 1. 按下按键（KEY[1]=0）
        top->KEY = 0b01;
        
        // 2. 保持按下足够长时间（超过消抖阈值1M周期）
        for (int j = 0; j < 1100000; j++) {
            step_clk();
        }
        
        // 3. 释放按键（KEY[1]=1）
        top->KEY = 0b11;
        step_clk();  // 产生上升沿，形成完整脉冲
        
        // 4. 验证LFSR更新为预期值
        EXPECT_EQ(top->random_val, expected_sequence[i])
            << "第" << (i+1) << "次按键后，LFSR值应为0x"
            << std::hex << (int)expected_sequence[i];
        
        // 5. 验证确实发生了变化
        EXPECT_NE(top->random_val, value_before)
            << "第" << (i+1) << "次按键应改变LFSR值";
        
        // 6. 按键间短暂间隔（模拟快速连按）
        for (int j = 0; j < 100; j++) {
            step_clk();
        }
    }
}

TEST_F(lfsr_test, Long_Press_No_Retrigger)
{
    // 验证按住按键不放不会连续触发LFSR更新
    // 先释放复位，让系统进入工作状态
    top->KEY = 0b11;  // rst_n=1, btn_in=1(未按下)
    step_clk();
    
    // 记录初始值
    uint8_t initial_val = top->random_val;
    
    // 模拟完整按键：按下->保持足够长->释放，获取第一次更新后的值
    top->KEY = 0b01;  // 按下（KEY[1]=0）
    for (int i = 0; i < 1100000; i++) {
        step_clk();
    }
    top->KEY = 0b11;  // 释放（KEY[1]=1）
    step_clk();
    
    uint8_t val_after_first = top->random_val;
    EXPECT_NE(val_after_first, initial_val)
        << "第一次按键应该改变LFSR值";
    
    // 持续按住按键很长时间（远超过1M周期），验证不会重复触发
    top->KEY = 0b01;  // 再次按下（模拟长按不释放）
    for (int i = 0; i < 5000000; i++) {  // 5M周期，远超过消抖阈值
        step_clk();
        // 每10000个周期检查一次，确保LFSR值保持不变
        if (i % 10000 == 0) {
            EXPECT_EQ(top->random_val, val_after_first)
                << "长按不应重复触发LFSR更新，在周期 " << i;
        }
    }
    
    // 释放按键
    top->KEY = 0b11;
    step_clk();
    step_clk();  // 等待消抖模块的 triggered 信号复位
    step_clk();  // 需要连续2个周期高电平才能复位 triggered
    
    // 验证值仍然没变（长按期间没有触发）
    EXPECT_EQ(top->random_val, val_after_first)
        << "释放前LFSR值应保持不变";
    
    // 再次完整按键，验证可以正常触发下一次
    top->KEY = 0b01;
    for (int i = 0; i < 1100000; i++) {
        step_clk();
    }
    top->KEY = 0b11;
    step_clk();
    
    // 验证产生了第二次更新
    EXPECT_NE(top->random_val, val_after_first)
        << "第二次完整按键应该再次改变LFSR值";
    EXPECT_EQ(top->random_val, 0x40)  // 预期序列: 0x01 -> 0x80 -> 0x40
        << "第二次按键后LFSR应为0x40";
}

TEST_F(lfsr_test, LFSR_Full_Period)
{
    // 验证LFSR经过255周期后回到初始值0x01
    // LFSR是8位最大长度序列，周期为2^8-1=255
    
    // 先释放复位，让系统进入工作状态
    top->KEY = 0b11;  // rst_n=1, btn_in=1(未按下)
    step_clk();
    
    // 第一个值应该是0x80
    uint8_t first_value = 0;
    
    // 连续触发255次有效按键
    for (int i = 0; i < 255; i++) {
        uint8_t before = top->random_val;
        
        // 模拟完整按键：按下->保持足够长->释放
        top->KEY = 0b01;  // 按下（KEY[1]=0）
        for (int j = 0; j < 1100000; j++) {  // > 1M周期，确保消抖
            step_clk();
        }
        top->KEY = 0b11;  // 释放（KEY[1]=1）
        step_clk();
        
        // 等待消抖模块的 triggered 信号复位
        step_clk();
        step_clk();
        
        // 记录第一个值
        if (i == 0) {
            first_value = top->random_val;
        }
        
        // 验证每次都有变化（除了最后回到起点）
        if (i < 254) {
            EXPECT_NE(top->random_val, before)
                << "第" << (i+1) << "次按键应改变LFSR值";
        }
    }
    
    // 验证经过255周期后回到初始值0x01
    EXPECT_EQ(top->random_val, 0x01)
        << "LFSR经过255周期后应回到初始值0x01";
    
    // 再执行一次按键，验证产生第一个值（确认周期正确）
    top->KEY = 0b01;
    for (int j = 0; j < 1100000; j++) {
        step_clk();
    }
    top->KEY = 0b11;
    step_clk();
    step_clk();
    step_clk();
    
    EXPECT_EQ(top->random_val, first_value)
        << "第256次按键应产生与第一次相同的值，确认周期为255";
}

TEST_F(lfsr_test, LFSR_State_Uniqueness)
{
    // 验证255周期内所有状态唯一（除周期结束回到初始值）
    // 8位LFSR最大长度周期为2^8-1=255
    
    std::set<uint8_t> seen_states;  // 用于记录已出现的状态
    bool has_duplicate = false;
    uint8_t duplicate_value = 0;
    int duplicate_at = -1;
    
    // 释放复位
    top->KEY = 0b11;
    step_clk();
    
    // 记录初始状态（应该是0x01）
    uint8_t initial_state = top->random_val;
    EXPECT_EQ(initial_state, 0x01) << "初始状态应为0x01";
    
    // 连续触发255次
    for (int i = 0; i < 255; i++) {
        uint8_t before = top->random_val;
        
        // 模拟完整按键
        top->KEY = 0b01;  // 按下
        for (int j = 0; j < 1100000; j++) step_clk();
        top->KEY = 0b11;  // 释放
        step_clk();
        step_clk();  // 等待消抖复位
        
        uint8_t current = top->random_val;
        
        // 检查是否重复（最后一次应该回到0x01）
        if (i < 254) {
            // 前254次不应该有重复
            if (seen_states.count(current)) {
                has_duplicate = true;
                duplicate_value = current;
                duplicate_at = i + 1;
                break;
            }
            seen_states.insert(current);
        }
        
        // 验证每次都有变化（除了最后一次回到起点）
        if (i < 254) {
            EXPECT_NE(current, before)
                << "第" << (i+1) << "次按键应改变LFSR值";
        }
    }
    
    // 验证没有提前重复
    EXPECT_FALSE(has_duplicate)
        << "状态 0x" << std::hex << (int)duplicate_value
        << " 在第" << duplicate_at << "次按键时重复出现";
    
    // 验证确实产生了254个不同状态（加上初始0x01共255个）
    EXPECT_EQ(seen_states.size(), 254)
        << "预期在回到初始值前产生254个不同状态";
    
    // 验证最后回到初始值
    EXPECT_EQ(top->random_val, 0x01)
        << "经过255周期后应回到初始值0x01";
    
    // 验证0x00不在任何状态中（LFSR不能产生全0）
    EXPECT_EQ(seen_states.count(0x00), 0)
        << "LFSR不应产生0x00状态";
}

TEST_F(lfsr_test, Async_Reset_Behavior)
{
    // 验证异步复位在任意时刻生效
    
    // 先释放复位，让系统进入工作状态
    top->KEY = 0b11;  // rst_n=1, btn_in=1(未按下)
    step_clk();
    
    // 先执行几次按键，离开初始状态
    for (int i = 0; i < 5; i++) {
        // 模拟完整按键：按下->保持足够长->释放
        top->KEY = 0b01;  // 按下（KEY[1]=0）
        for (int j = 0; j < 1100000; j++) {
            step_clk();
        }
        top->KEY = 0b11;  // 释放（KEY[1]=1）
        step_clk();
        // 等待消抖模块的 triggered 信号复位
        step_clk();
        step_clk();
    }
    
    uint8_t before_reset = top->random_val;
    EXPECT_NE(before_reset, 0x01)
        << "执行5次按键后应已离开初始值0x01";
    
    // 异步复位：拉低rst_n（KEY[0]=0），不等待特定时钟沿
    top->KEY = 0b00;  // rst_n=0（激活复位），KEY[1]=0
    step_clk();
    
    // 立即检查是否复位到初始值0x01
    EXPECT_EQ(top->random_val, 0x01)
        << "异步复位应立即将LFSR复位到0x01";
    
    // 验证数码管也正确显示复位后的值
    // random_val=0x01: 低4位=0x1(HEX0), 高4位=0x0(HEX1)
    // 硬件输出已取反适配NVBoard共阳极: ~0x06=0x79, ~0x3F=0x40
    EXPECT_EQ(top->HEX0, 0x79)  // 显示1
        << "复位后HEX0应显示random_val低4位(0x1)";
    EXPECT_EQ(top->HEX1, 0x40)  // 显示0
        << "复位后HEX1应显示random_val高4位(0x0)";
    
    // 记录复位后的HEX值，用于后续比较
    uint8_t hex0_after_reset = top->HEX0;
    uint8_t hex1_after_reset = top->HEX1;
    
    // 释放复位，验证系统恢复正常工作
    top->KEY = 0b01;  // rst_n=1（释放复位），KEY[1]=0
    step_clk();
    EXPECT_EQ(top->random_val, 0x01)
        << "释放复位后LFSR应保持初始值0x01";
    
    // 再次执行一次完整按键，验证可以正常触发
    for (int j = 0; j < 1100000; j++) {
        step_clk();
    }
    top->KEY = 0b11;  // 释放按键
    step_clk();
    step_clk();
    step_clk();
    
    // 验证LFSR已更新为序列的下一个值0x80
    EXPECT_EQ(top->random_val, 0x80)
        << "复位后第一次按键应产生0x80";
    
    // 验证数码管也已更新
    EXPECT_NE(top->HEX0, hex0_after_reset)
        << "按键后HEX0应改变";
    EXPECT_NE(top->HEX1, hex1_after_reset)
        << "按键后HEX1应改变";
    
    // 0x80: 低4位=0x0(HEX0), 高4位=0x8(HEX1)
    // 硬件输出已取反适配NVBoard共阳极: ~0x3F=0x40, ~0x7F=0x00
    EXPECT_EQ(top->HEX0, 0x40)  // 显示0
        << "0x80的低4位(0x0)应正确显示";
    EXPECT_EQ(top->HEX1, 0x00)  // 显示8
        << "0x80的高4位(0x8)应正确显示";
}

TEST_F(lfsr_test, Debounce_Boundary_Threshold)
{
    // 验证消抖阈值边界：cnt_done在cnt_q == 20'h0F423F(999999)时置位
    // 即需要恰好1,000,000个周期才会触发
    
    // ========== 测试1：刚好不足1M周期（不应触发） ==========
    // 释放复位，系统进入工作状态
    top->KEY = 0b11;
    step_clk();
    
    // 按下按键，持续999,999周期（cnt_q从0到999,998）
    top->KEY = 0b01;
    for (int i = 0; i < 999999; i++) {
        step_clk();
    }
    // 此时cnt_q = 999,998，cnt_done尚未置位
    
    // 释放按键（产生上升沿）
    top->KEY = 0b11;
    step_clk();
    step_clk();  // 等待消抖状态机复位
    
    // 验证：LFSR应保持初始值，未触发更新
    EXPECT_EQ(top->random_val, 0x01)
        << "持续999,999周期不应触发LFSR更新";
    
    // ========== 测试2：刚好1M周期（应触发） ==========
    // 再次按下按键，持续1,000,000周期
    top->KEY = 0b01;
    for (int i = 0; i < 1000000; i++) {
        step_clk();  // 第1M个周期后cnt_done置位
    }
    
    // 释放按键产生脉冲
    top->KEY = 0b11;
    step_clk();
    
    // 验证：LFSR应更新为0x80
    EXPECT_EQ(top->random_val, 0x80)
        << "持续1,000,000周期应触发LFSR更新为0x80";
}

TEST_F(lfsr_test, HexDisplay_KeyValues)
{
    // 验证数码管 HEX0/HEX1 对关键十六进制值显示正确
    // 使用从 Async_Reset_Behavior 测试已验证的期望值
    
    // 释放复位，系统进入工作状态
    top->KEY = 0b11;
    step_clk();
    
    // 测试1: 初始值 0x01 -> HEX0显示1, HEX1显示0
    EXPECT_EQ(top->random_val, 0x01);
    EXPECT_EQ(top->HEX0, 0x79) << "0x01的低4位(1)应显示为0x79(~0x06)";
    EXPECT_EQ(top->HEX1, 0x40) << "0x01的高4位(0)应显示为0x40(~0x3F)";
    
    // 触发第一次按键，得到 0x80
    top->KEY = 0b01;
    for (int j = 0; j < 1100000; j++) step_clk();
    top->KEY = 0b11;
    step_clk();
    step_clk();
    step_clk();
    
    // 测试2: 0x80 -> HEX0显示0, HEX1显示8
    EXPECT_EQ(top->random_val, 0x80);
    EXPECT_EQ(top->HEX0, 0x40) << "0x80的低4位(0)应显示为0x40(~0x3F)";
    EXPECT_EQ(top->HEX1, 0x00) << "0x80的高4位(8)应显示为0x00(~0x7F)";
    
    // 触发第二次按键，得到 0x40
    top->KEY = 0b01;
    for (int j = 0; j < 1100000; j++) step_clk();
    top->KEY = 0b11;
    step_clk();
    step_clk();
    step_clk();
    
    // 测试3: 0x40 -> HEX0显示0, HEX1显示4
    EXPECT_EQ(top->random_val, 0x40);
    EXPECT_EQ(top->HEX0, 0x40) << "0x40的低4位(0)应显示为0x40(~0x3F)";
    EXPECT_EQ(top->HEX1, 0x19) << "0x40的高4位(4)应显示为0x19(~0x66)";
    
    // 触发第三次按键，得到 0x20
    top->KEY = 0b01;
    for (int j = 0; j < 1100000; j++) step_clk();
    top->KEY = 0b11;
    step_clk();
    step_clk();
    step_clk();
    
    // 测试4: 0x20 -> HEX0显示0, HEX1显示2
    EXPECT_EQ(top->random_val, 0x20);
    EXPECT_EQ(top->HEX0, 0x40) << "0x20的低4位(0)应显示为0x40(~0x3F)";
    EXPECT_EQ(top->HEX1, 0x24) << "0x20的高4位(2)应显示为0x24(~0x5B)";
}

TEST_F(lfsr_test, HexDisplay_AllValues_WhiteBox)
{
    // 白盒测试：使用测试接口直接验证hex_7seg模块对所有16个十六进制值的译码
    // 共阴极7段数码管编码表 (gfedcba) - 正逻辑
    const uint8_t seg_table[16] = {
        0x3F, // 0: 011_1111
        0x06, // 1: 000_0110
        0x5B, // 2: 101_1011
        0x4F, // 3: 100_1111
        0x66, // 4: 110_0110
        0x6D, // 5: 110_1101
        0x7D, // 6: 111_1101
        0x07, // 7: 000_0111
        0x7F, // 8: 111_1111
        0x6F, // 9: 110_1111
        0x77, // A: 111_0111
        0x7C, // B: 111_1100
        0x39, // C: 011_1001
        0x5E, // D: 101_1110
        0x79, // E: 111_1001
        0x71  // F: 111_0001
    };
    
    // 取反后的编码表 - 适配NVBoard共阳极数码管（硬件输出已取反）
    const uint8_t seg_table_inv[16] = {
        0x40, // 0: ~0x3F & 0x7F
        0x79, // 1: ~0x06 & 0x7F
        0x24, // 2: ~0x5B & 0x7F
        0x30, // 3: ~0x4F & 0x7F
        0x19, // 4: ~0x66 & 0x7F
        0x12, // 5: ~0x6D & 0x7F
        0x02, // 6: ~0x7D & 0x7F
        0x78, // 7: ~0x07 & 0x7F
        0x00, // 8: ~0x7F & 0x7F
        0x10, // 9: ~0x6F & 0x7F
        0x08, // A: ~0x77 & 0x7F
        0x03, // B: ~0x7C & 0x7F
        0x46, // C: ~0x39 & 0x7F
        0x21, // D: ~0x5E & 0x7F
        0x06, // E: ~0x79 & 0x7F
        0x0E  // F: ~0x71 & 0x7F
    };
    
    // 使能测试模式
    top->test_mode_en = 1;
    
    // 遍历所有16个低4位值
    for (int low = 0; low < 16; low++) {
        top->test_hex_low = low;
        step_clk();
        
        EXPECT_EQ(top->HEX0_test, seg_table_inv[low])
            << "低4位输入0x" << std::hex << low
            << "时，HEX0显示错误，期望0x" << (int)seg_table_inv[low]
            << "，实际0x" << (int)top->HEX0_test;
    }
    
    // 遍历所有16个高4位值
    for (int high = 0; high < 16; high++) {
        top->test_hex_high = high;
        step_clk();
        
        EXPECT_EQ(top->HEX1_test, seg_table_inv[high])
            << "高4位输入0x" << std::hex << high
            << "时，HEX1显示错误，期望0x" << (int)seg_table_inv[high]
            << "，实际0x" << (int)top->HEX1_test;
    }
    
    // 组合测试：验证所有256个组合
    for (int low = 0; low < 16; low++) {
        for (int high = 0; high < 16; high++) {
            top->test_hex_low = low;
            top->test_hex_high = high;
            step_clk();
            
            EXPECT_EQ(top->HEX0_test, seg_table_inv[low])
                << "组合测试：低4位0x" << std::hex << low
                << "，高4位0x" << high << "时HEX0错误";
            EXPECT_EQ(top->HEX1_test, seg_table_inv[high])
                << "组合测试：低4位0x" << std::hex << low
                << "，高4位0x" << high << "时HEX1错误";
        }
    }
    
    // 关闭测试模式
    top->test_mode_en = 0;
}

TEST_F(lfsr_test, HexDisplay_Dynamic_Update)
{
    // 场景：验证LFSR变化时数码管实时更新
    // 共阴极7段数码管编码表 (gfedcba) - 正逻辑
    const uint8_t seg_table[16] = {
        0x3F, // 0: 011_1111
        0x06, // 1: 000_0110
        0x5B, // 2: 101_1011
        0x4F, // 3: 100_1111
        0x66, // 4: 110_0110
        0x6D, // 5: 110_1101
        0x7D, // 6: 111_1101
        0x07, // 7: 000_0111
        0x7F, // 8: 111_1111
        0x6F, // 9: 110_1111
        0x77, // A: 111_0111
        0x7C, // B: 111_1100
        0x39, // C: 011_1001
        0x5E, // D: 101_1110
        0x79, // E: 111_1001
        0x71  // F: 111_0001
    };
    
    // 取反后的编码表 - 适配NVBoard共阳极数码管（硬件输出已取反）
    const uint8_t seg_table_inv[16] = {
        0x40, // 0: ~0x3F & 0x7F
        0x79, // 1: ~0x06 & 0x7F
        0x24, // 2: ~0x5B & 0x7F
        0x30, // 3: ~0x4F & 0x7F
        0x19, // 4: ~0x66 & 0x7F
        0x12, // 5: ~0x6D & 0x7F
        0x02, // 6: ~0x7D & 0x7F
        0x78, // 7: ~0x07 & 0x7F
        0x00, // 8: ~0x7F & 0x7F
        0x10, // 9: ~0x6F & 0x7F
        0x08, // A: ~0x77 & 0x7F
        0x03, // B: ~0x7C & 0x7F
        0x46, // C: ~0x39 & 0x7F
        0x21, // D: ~0x5E & 0x7F
        0x06, // E: ~0x79 & 0x7F
        0x0E  // F: ~0x71 & 0x7F
    };
    
    // 释放复位，系统进入工作状态
    top->KEY = 0b11;
    step_clk();
    
    // 记录一系列LFSR值对应的数码管输出
    std::vector<uint8_t> lfsr_values;
    std::vector<uint8_t> hex0_values;
    std::vector<uint8_t> hex1_values;
    
    const int NUM_SAMPLES = 10;
    
    for (int i = 0; i < NUM_SAMPLES; i++) {
        uint8_t lfsr_before = top->random_val;
        
        // 触发按键：按下->保持->释放
        top->KEY = 0b01;  // 按下
        for (int j = 0; j < 1100000; j++) {
            step_clk();
        }
        top->KEY = 0b11;  // 释放
        step_clk();
        step_clk();  // 等待消抖复位
        step_clk();
        
        // 记录当前状态
        lfsr_values.push_back(top->random_val);
        hex0_values.push_back(top->HEX0);
        hex1_values.push_back(top->HEX1);
        
        // 验证LFSR确实改变了
        EXPECT_NE(top->random_val, lfsr_before)
            << "第" << (i+1) << "次按键应改变LFSR值";
    }
    
    // 验证每一对都符合译码关系
    for (int i = 0; i < NUM_SAMPLES; i++) {
        uint8_t lfsr = lfsr_values[i];
        uint8_t low_nibble = lfsr & 0x0F;
        uint8_t high_nibble = (lfsr >> 4) & 0x0F;
        
        uint8_t expected_hex0 = seg_table_inv[low_nibble];
        uint8_t expected_hex1 = seg_table_inv[high_nibble];
        
        EXPECT_EQ(hex0_values[i], expected_hex0)
            << "第" << (i+1) << "次更新：LFSR=0x" << std::hex << (int)lfsr
            << "，低4位=0x" << (int)low_nibble
            << "，HEX0期望0x" << (int)expected_hex0
            << "，实际0x" << (int)hex0_values[i];
        
        EXPECT_EQ(hex1_values[i], expected_hex1)
            << "第" << (i+1) << "次更新：LFSR=0x" << std::hex << (int)lfsr
            << "，高4位=0x" << (int)high_nibble
            << "，HEX1期望0x" << (int)expected_hex1
            << "，实际0x" << (int)hex1_values[i];
    }
    
    // 额外验证：确认数码管输出与LFSR是实时同步的
    // 执行一次新的按键，在每个时钟沿检查一致性
    top->KEY = 0b01;  // 按下
    for (int j = 0; j < 1100000; j++) {
        step_clk();
    }
    top->KEY = 0b11;  // 释放
    step_clk();
    
    // 在刚释放后的第一个时钟沿立即检查
    uint8_t current_lfsr = top->random_val;
    uint8_t expected_hex0_now = seg_table_inv[current_lfsr & 0x0F];
    uint8_t expected_hex1_now = seg_table_inv[(current_lfsr >> 4) & 0x0F];
    
    EXPECT_EQ(top->HEX0, expected_hex0_now)
        << "按键释放后HEX0应立即更新";
    EXPECT_EQ(top->HEX1, expected_hex1_now)
        << "按键释放后HEX1应立即更新";
}

TEST_F(lfsr_test, Reset_During_Debounce)
{
    // 场景：在消抖计数过程中触发复位，验证复位优先级
    // 消抖模块需要1M周期计数，我们在不同时间点注入复位
    
    // 测试不同复位注入时间点
    int reset_timings[] = {100, 500000, 999999};
    
    for (int reset_at : reset_timings) {
        // 释放复位，系统进入工作状态
        top->KEY = 0b11;
        step_clk();
        
        // 记录复位前的LFSR值
        uint8_t val_before = top->random_val;
        
        // 先执行几次按键，离开初始状态
        for (int i = 0; i < 3; i++) {
            top->KEY = 0b01;
            for (int j = 0; j < 1100000; j++) step_clk();
            top->KEY = 0b11;
            step_clk();
            step_clk();
            step_clk();
        }
        
        uint8_t val_after_presses = top->random_val;
        EXPECT_NE(val_after_presses, 0x01)
            << "执行3次按键后应已离开初始值";
        
        // 开始新的按键按下，消抖计数器开始计数
        top->KEY = 0b01;
        for (int i = 0; i < reset_at; i++) {
            step_clk();
        }
        
        // 此时消抖计数器正在计数（未达到1M）
        // 注入异步复位（拉低rst_n）
        top->KEY = 0b00;  // rst_n=0, btn_in=0
        step_clk();
        
        // 验证复位立即生效，LFSR回到初始值0x01
        EXPECT_EQ(top->random_val, 0x01)
            << "在消抖计数到" << reset_at << "时触发复位，"
            << "LFSR应立即复位到0x01";
        
        // 验证数码管也正确显示复位后的值（硬件输出已取反）
        EXPECT_EQ(top->HEX0, 0x79)  // ~0x06 & 0x7F，显示1
            << "复位后HEX0应显示0x1";
        EXPECT_EQ(top->HEX1, 0x40)  // ~0x3F & 0x7F，显示0
            << "复位后HEX1应显示0x0";
        
        // 继续按住复位一段时间，验证保持稳定
        for (int i = 0; i < 1000; i++) {
            step_clk();
        }
        EXPECT_EQ(top->random_val, 0x01)
            << "复位保持期间LFSR应保持0x01";
        
        // 释放复位，验证系统恢复正常工作
        top->KEY = 0b01;  // rst_n=1, btn_in=0（仍然按下）
        step_clk();
        
        // 此时按键仍然处于按下状态，消抖计数器应该重新开始计数
        // 我们需要完成这次按键来验证系统正常
        for (int j = 0; j < 1100000; j++) {
            step_clk();
        }
        
        // 释放按键
        top->KEY = 0b11;
        step_clk();
        step_clk();
        step_clk();
        
        // 验证LFSR已更新为0x80（复位后的第一次有效按键）
        EXPECT_EQ(top->random_val, 0x80)
            << "复位后第一次完整按键应产生0x80";
        
        // 验证数码管已更新（硬件输出已取反）
        EXPECT_EQ(top->HEX0, 0x40)  // ~0x3F=0x40，显示0（0x80的低4位）
            << "复位后第一次按键HEX0应显示0";
        EXPECT_EQ(top->HEX1, 0x00)  // ~0x7F=0x00，显示8（0x80的高4位）
            << "复位后第一次按键HEX1应显示8";
    }
}

TEST_F(lfsr_test, Reset_During_Button_Pulse)
{
    // 场景：在按键脉冲产生后立即复位，验证不会二次触发
    
    // 释放复位，系统进入工作状态
    top->KEY = 0b11;
    step_clk();
    
    // 执行一次完整按键，LFSR更新到0x80
    top->KEY = 0b01;
    for (int j = 0; j < 1100000; j++) step_clk();
    top->KEY = 0b11;
    step_clk();
    step_clk();
    step_clk();
    
    EXPECT_EQ(top->random_val, 0x80)
        << "第一次按键后LFSR应为0x80";
    
    // 立即触发复位（不等待消抖模块的triggered信号完全复位）
    top->KEY = 0b00;  // 激活复位
    step_clk();
    
    // 验证立即复位到0x01
    EXPECT_EQ(top->random_val, 0x01)
        << "按键后立即复位，LFSR应为0x01";
    
    // 快速释放并再次按键，验证消抖模块已正确复位
    top->KEY = 0b11;  // 释放复位，按键未按下
    step_clk();
    step_clk();
    
    // 再次完整按键
    top->KEY = 0b01;
    for (int j = 0; j < 1100000; j++) step_clk();
    top->KEY = 0b11;
    step_clk();
    step_clk();
    step_clk();
    
    // 验证能正常触发（不会漏掉或重复）
    EXPECT_EQ(top->random_val, 0x80)
        << "复位后应能正常触发第一次更新";
}

#else
#include <nvboard.h>
void nvboard_bind_all_pins(Vlfsr_random_generator_top *top);
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
    // NVBorad逻辑
    std::cout << std::format("开始nvboard测试") << std::endl;
    auto top = std::make_unique<Vlfsr_random_generator_top>();
    nvboard_init();
    nvboard_bind_all_pins(top.get());
    while (1)
    {
        top->CLOCK_50 = 0;
        top->eval();
        top->CLOCK_50 = 1;
        top->eval();
        nvboard_update();
    }
    nvboard_quit();
    return 0;
#endif
}
