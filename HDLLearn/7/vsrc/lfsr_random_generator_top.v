`include "VerilogHead.vh"

module lfsr_random_generator_top (
    input CLOCK_50,         // 50MHz主时钟
    input[1:0] KEY,         // KEY[0]做复位(rst_n)，KEY[1]做随机数触发(btn_in)
    output [6:0] HEX0,      // 低4位数码管输出
    output [6:0] HEX1,      // 高4位数码管输出
    output [7:0] random_val, // 用于存储由LFSR模块生成的8位伪随机数值
    
    // 测试模式接口（仅在TEST_MODE下使用）
`ifdef TEST_MODE
    input [3:0] test_hex_low,   
    input [3:0] test_hex_high,  
    input       test_mode_en,   
`endif
    output [6:0] HEX0_test,     
    output [6:0] HEX1_test      
);

    // --- 内部信号声明 ---
    wire btn_pulse;         // 接收消抖后的脉冲信号
    wire [6:0] hex0_int;    // 内部低4位译码信号（正逻辑）
    wire [6:0] hex1_int;    // 内部高4位译码信号（正逻辑）

    // --- 模块实例化 ---

    // 1. 消抖模块：处理 KEY[1] 机械抖动
    debounce u_debounce (
        .clk    (CLOCK_50),
        .rst_n  (KEY[0]),
        .btn_in (KEY[1]),
        .btn_out(btn_pulse)
    );

    // 2. LFSR 模块：接收脉冲，产生随机数
    lsfr_8bit u_lsfr (
        .clk    (btn_pulse),
        .rst_n  (KEY[0]),
        .q      (random_val)
    );

    // 3. 数码管译码：低4位
    hex_7seg u_seg_low (
        .hex    (random_val[3:0]),
        .seg    (hex0_int)          // 连接到内部正逻辑线
    );

    // 4. 数码管译码：高4位
    hex_7seg u_seg_high (
        .hex    (random_val[7:4]),
        .seg    (hex1_int)          // 连接到内部正逻辑线
    );

    // --- 核心修改：逻辑取反 ---
    // NVBoard 通常是低电平点亮（共阳极），而译码器输出是高电平点亮
    assign HEX0 = ~hex0_int; 
    assign HEX1 = ~hex1_int;

    // --- 测试模式处理 ---
`ifdef TEST_MODE
    wire [6:0] hex0_test_int;
    wire [6:0] hex1_test_int;
    wire [3:0] test_hex_low_internal  = test_mode_en ? test_hex_low  : 4'b0;
    wire [3:0] test_hex_high_internal = test_mode_en ? test_hex_high : 4'b0;
    
    hex_7seg u_seg_low_test (
        .hex    (test_hex_low_internal),
        .seg    (hex0_test_int)
    );
    
    hex_7seg u_seg_high_test (
        .hex    (test_hex_high_internal),
        .seg    (hex1_test_int)
    );

    // 测试输出同样需要取反，以保证在 NVBoard 上观察正确
    assign HEX0_test = ~hex0_test_int;
    assign HEX1_test = ~hex1_test_int;
`else
    assign HEX0_test = 7'b111_1111; // 全灭（取反后为0）
    assign HEX1_test = 7'b111_1111; // 全灭
`endif

endmodule