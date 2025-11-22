`include "VerilogHead.vh"
module key_counter (
    input clk,
    input rst_n,
    input key_pressed_pulse,//改为脉冲输入，直接用parser输出的脉冲
    output reg [7:0] count//按键总次数(0-255)
);
    /*
    删除内部的key_pressed_d寄存器和边沿检测逻辑
    因为之前输入时按键一直按住，所以需要用key_pressed_d来检测从0变1的那个瞬间才能计数一次
    但是现在输入已经是脉冲，只闪一下就没了，本身就代表只按了一次，直接计数就行了，因为输入已经是脉冲，就不需要key_pressed_的做边沿检测了
    */
    always@(posedge clk or negedge rst_n) begin
        if(!rst_n) begin
            count <= 0;
        end
        else if(key_pressed_pulse) begin//直接检测脉冲
            count <= count + 1;
        end
    end
endmodule
