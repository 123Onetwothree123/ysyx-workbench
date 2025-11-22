`include "VerilogHead.vh"

module lsfr_8bit (
    input clk,      // 来自消抖后的按键信号
    input rst_n,    // 低电平有效的异步复位信号
    output reg [7:0] q  // 输出8bit伪随机序列
);
    wire feedback = q[4] ^ q[3] ^ q[2] ^ q[0];
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n)
            q <= 8'b00000001;
        else
            q <= {feedback, q[7:1]};
    end
endmodule