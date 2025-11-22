`include "VerilogHead.vh"

/*
6bit的七段数码管动态扫描显示控制
bit5和bit4显示按键总次数 （key_count）
bit3和bit2显示ASCII码 （ascii）
bit1和bit0显示扫描码（scan_code）
当当key_pressed=0时，低四位（bit3-bit0）全灭
*/
module seven_seg_scan (
    input clk,//系统时钟
    input rst_n,//低电平复位
    input key_pressed,//按键按下标志，控制低四位是否显示
    input [7:0] scan_code,//扫描码，显示在位1:0
    input [7:0] ascii,//ASCII码，显示在位3:2
    input [7:0] key_count,//按键次数，显示在位5:4
    output [6:0] seg_led,//七段码输出 (gfedcba，低电平点亮)
    output reg [5:0] dig_sel//位选信号，低电平有效
);
/*
用来扫描时钟分频的
就假设系统的时钟是50MHz，需要1kHz扫描频率（即每1ms切换1bit）
分频系数=50MHz/（1kHz*6bit）≈8333
*/
localparam SCAN_DIV = 16'd8333;//扫描分频系数
localparam SCAN_BITS = 16;//计数器位宽
reg [SCAN_BITS-1:0] scan_cnt;//扫描计数器，扫描分频计数器，用来产生扫描时钟
reg [2:0] scan_pos;//当前扫描位置（0-5），扫描位置计数器，0-5是对应数码管
//扫描计数器，用来产生1kHz的扫描时钟
always @(posedge clk or negedge rst_n) begin//两种情况，第一时钟上升，第二下降的复位启动了
    if (!rst_n) begin//如果复位启动了，直接清零计数器
        scan_cnt <= 0;
        scan_pos <= 0;
    end else begin
        //判断计数器满了
        if (scan_cnt >= SCAN_DIV) begin
            scan_cnt <= 0;//直接清零计数器
            scan_pos <= scan_pos + 1'b1;//切换到下一bit数码管
            if (scan_pos >= 3'd5)//判断位置计数器有没有超过5bit，有没有越界
                scan_pos <= 0;//直接回到0bit
        end else begin
            scan_cnt <= scan_cnt + 1'b1;//分频计数器没有满，继续+1
        end
    end
end
/*
这是产生位选择信号的和控制低4bit灭灯的
*/
always @(*) begin
    //默认全灭
    dig_sel = 6'b111111;
    if (key_pressed) begin
        //按键按下时，6位都正常显示
        case (scan_pos)
            3'd0: dig_sel = 6'b111110;//位0：scan_code[3:0]
            3'd1: dig_sel = 6'b111101;//位1：scan_code[7:4]
            3'd2: dig_sel = 6'b111011;//位2：ascii[3:0]
            3'd3: dig_sel = 6'b110111;//位3：ascii[7:4]
            3'd4: dig_sel = 6'b101111;//位4：key_count[3:0]
            3'd5: dig_sel = 6'b011111;//位5：key_count[7:4]
            default: dig_sel = 6'b111111;
        endcase
    end else begin
        //按键松开时，低四位全灭，只显示高两位（次数）
        case (scan_pos)
            3'd0: dig_sel = 6'b111111;//位0：灭
            3'd1: dig_sel = 6'b111111;//位1：灭
            3'd2: dig_sel = 6'b111111;//位2：灭
            3'd3: dig_sel = 6'b111111;//位3：灭
            3'd4: dig_sel = 6'b101111;//位4：key_count[3:0]
            3'd5: dig_sel = 6'b011111;//位5：key_count[7:4]
            default: dig_sel = 6'b111111;
        endcase
    end
end
/*
用于数据选择，根据扫描位置选择现在要显示的数据
*/
reg [3:0] hex_data;//当前要显示的十六进制数据
always @(*) begin
    case (scan_pos)
        3'd0: hex_data = scan_code[3:0];//位0：扫描码低4位
        3'd1: hex_data = scan_code[7:4];//位1：扫描码高4位
        3'd2: hex_data = ascii[3:0];//位2：ASCII低4位
        3'd3: hex_data = ascii[7:4];//位3：ASCII高4位
        3'd4: hex_data = key_count[3:0];//位4：次数低4位
        3'd5: hex_data = key_count[7:4];//位5：次数高4位
        default: hex_data = 4'h0;
    endcase
end
//调用hex_7seg模块进行七段码译码
hex_7seg u_hex_7seg (
    .hex(hex_data),
    .seg(seg_led)
);

endmodule
