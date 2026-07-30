`include "VerilogHead.vh"

module SevenSegLEDNixietubeKeyboardASCII (
    input clk,
    input rst_n,
    input ps2_clk,
    input ps2_data,
    output [6:0] HEX0, HEX1, HEX2, HEX3, HEX4, HEX5,
    output [15:0] LED
);

    // --- 内部信号 ---
    wire [7:0] ps2_raw_data;
    wire ps2_ready;
    wire [7:0] scan_code;
    wire key_pressed;
    wire key_pressed_pulse;
    wire [7:0] ascii;
    wire [7:0] key_count;

    // ==========================================================================
    // 诊断 LED (观察 LD0 和 LD1 是关键)
    // ==========================================================================
    assign LED[0] = rst_n;      // LD0: 复位指示。亮绿色表示系统运行正常。
    assign LED[1] = ps2_clk;    // LD1: 键盘时钟。!!如果这里不亮，说明绑定失败!!
    assign LED[2] = ps2_data;   // LD2: 键盘数据。空闲时也应亮起。
    assign LED[3] = ps2_ready;  // LD3: 数据接收脉冲。按键时闪烁。
    assign LED[4] = key_pressed;// LD4: 按键状态。按下亮，松开灭。
    assign LED[15:5] = 11'b0;

    // ==========================================================================
    // 逻辑部分
    // ==========================================================================
    ps2_keyboard u_ps2_keyboard (
        .clk(clk), .clrn(rst_n), .ps2_clk(ps2_clk), .ps2_data(ps2_data),
        .data(ps2_raw_data), .ready(ps2_ready), .nextdata_n(1'b0), .overflow()
    );

    scan_code_parser u_parser (
        .clk(clk), .rst_n(rst_n), .ps2_data(ps2_raw_data), .ps2_ready(ps2_ready),
        .scan_code(scan_code), .key_pressed(key_pressed), .key_pressed_pulse(key_pressed_pulse)
    );

    scan_to_ascii_ROM u_rom (scan_code, ascii);
    key_counter u_cnt (clk, rst_n, key_pressed_pulse, key_count);

    // ==========================================================================
    // 数码管并行驱动 (验收要求实现)
    // ==========================================================================
    wire [6:0] s0, s1, s2, s3, s4, s5;
    hex_7seg d0(scan_code[3:0],  s0);
    hex_7seg d1(scan_code[7:4],  s1);
    hex_7seg d2(ascii[3:0],      s2);
    hex_7seg d3(ascii[7:4],      s3);
    hex_7seg d4(key_count[3:0],  s4);
    hex_7seg d5(key_count[7:4],  s5);

    // 逻辑控制：按键按下时显示键码和ASCII，松开时低四位熄灭
    assign HEX0 = key_pressed ? s0 : 7'b111_1111;
    assign HEX1 = key_pressed ? s1 : 7'b111_1111;
    assign HEX2 = key_pressed ? s2 : 7'b111_1111;
    assign HEX3 = key_pressed ? s3 : 7'b111_1111;
    assign HEX4 = s4; // 始终显示计数
    assign HEX5 = s5;

endmodule