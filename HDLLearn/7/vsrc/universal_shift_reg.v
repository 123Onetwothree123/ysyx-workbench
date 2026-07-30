`include "VerilogHead.vh"
module universal_shift_reg (
    input clk,//时钟信号
    input rst_n,//异步复位信号
    /*
    南京大学实验6：
    000清0；
    001置数；
    010逻辑右移；
    011逻辑左移；
    100算数右移；
    101左端串行输入1位值，并行输出8位值；
    110逻辑右移；
    111逻辑左移
    */
    input[2:0] ctrl,         // 控制信号
    input[7:0] data_in,      // 并行输入数据
    input ser_in,            // 串行输入位，于"左端串行输入1位"工作方式
    output reg[7:0] q        // 8bit输出
);
    
endmodule