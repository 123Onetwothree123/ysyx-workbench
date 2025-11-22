`include "VerilogHead.vh"

module SevenSegLEDNixietubeKeyboardASCII (
    input clk,//系统时钟
    input rst_n,//异步复位，低电平有效
    input ps2_clk,//ps/2键盘的时钟
    input ps2_data,//ps/2键盘数据
    output [6:0] HEX0, HEX1, HEX2, HEX3, HEX4, HEX5,//6个气短数码管段选择信号
    output [15:0] LED//LED指示灯
);
    //这是内部信号
    wire [7:0] ps2_raw_data;//PS/2的原始数据，先搞一个内部信号作为从PS/2键盘直接接收的8位原始字节数据，包含扫描码
    wire ps2_ready;//用来接收准备就绪的信号的，当ps/2成功接收完整字节后会产生一个时钟周期的高电平脉冲
    wire [7:0] scan_code;//扫描码，scan_code_parser得到的按键扫描码，删掉了断码内容
    wire key_pressed;//按键的状态，一种新的持续信号，有按键被按下的时候就算1，松开的时候就算0
    wire key_pressed_pulse;//按键的脉冲，按下的时候就一个周期的脉冲，然后给边缘检测组件检测的
    wire [7:0] ascii;//接收scan_to_ascii_ROM输出的ASCII
    wire [7:0] key_count;//用来计数,计算按键次数，用于key_counter
    // 诊断LED，debug用的
    assign LED[0] = rst_n;//LD0：复位指示，亮绿色表示系统运行正常
    assign LED[1] = ps2_clk;//LD1：键盘时钟，如果这里不亮，说明绑定失败
    assign LED[2] = ps2_data;//LD2：键盘数据，空闲时也应亮起
    assign LED[3] = ps2_ready;//LD3：数据接收脉冲，按键时闪烁
    assign LED[4] = key_pressed;//LD4：按键状态，按下亮，松开灭
    assign LED[15:5] = 11'b0;
    //逻辑部分
    //接收PS/2键盘的串行数据，转换为并行字节
    ps2_keyboard ps2_keyboard (
        .clk(clk), .clrn(rst_n), .ps2_clk(ps2_clk), .ps2_data(ps2_data),
        .data(ps2_raw_data), .ready(ps2_ready), .nextdata_n(1'b0), .overflow()
    );
    //解析PS/2协议中的通码和断码，输出按键状态
    scan_code_parser parser (
        .clk(clk), .rst_n(rst_n), .ps2_data(ps2_raw_data), .ps2_ready(ps2_ready),
        .scan_code(scan_code), .key_pressed(key_pressed), .key_pressed_pulse(key_pressed_pulse)
    );
    //使用查找表（LUT）将PS/2扫描码转换为ASCII码
    scan_to_ascii_ROM rom (scan_code, ascii);
    //统计按键按下的总次数，每次检测到key_pressed_pulse脉冲时加1
    key_counter cnt (clk, rst_n, key_pressed_pulse, key_count);
    //数码管并行驱动
    wire [6:0] s0, s1, s2, s3, s4, s5;
    hex_7seg d0(scan_code[3:0],s0);//显示扫描码低4位，用16进制
    hex_7seg d1(scan_code[7:4],s1);//显示扫描码高4位，也是用16进制
    hex_7seg d2(ascii[3:0],s2);//显示ASCII码低4位，16进制
    hex_7seg d3(ascii[7:4],s3);//显示ASCII码高4位，16进制
    hex_7seg d4(key_count[3:0],s4);//显示按键次数低4位，16进制
    hex_7seg d5(key_count[7:4],s5);//显示按键次数高4位，16进制
    //逻辑控制：按键按下时显示键码和ASCII，松开时低四位熄灭
    assign HEX0 = key_pressed ? s0 : 7'b111_1111;
    assign HEX1 = key_pressed ? s1 : 7'b111_1111;
    assign HEX2 = key_pressed ? s2 : 7'b111_1111;
    assign HEX3 = key_pressed ? s3 : 7'b111_1111;
    assign HEX4 = s4;//始终显示计数
    assign HEX5 = s5;

endmodule