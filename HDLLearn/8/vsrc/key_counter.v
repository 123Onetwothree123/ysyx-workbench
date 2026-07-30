`include "VerilogHead.vh"
module key_counter (
    input clk,
    input rst_n,
    input key_pressed_pulse,    // 改为脉冲输入，直接用parser输出的脉冲
    output reg [7:0] count      // 按键总次数(0-255)
);
    // 删除内部的 key_pressed_d 寄存器和边沿检测逻辑
    always@(posedge clk or negedge rst_n) begin
        if(!rst_n) begin
            count <= 0;
        end
        else if(key_pressed_pulse) begin    // 直接检测脉冲
            count <= count + 1;
        end
    end
endmodule
