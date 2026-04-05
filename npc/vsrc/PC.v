module PC (
    input         clk,          // 时钟信号
    input         rst,          // 复位信号（同步复位，高电平有效）
    input  [31:0] NextPC,       // 下一条PC值
    input         PCEnable,     // PC写使能信号
    output [31:0] PC            // 当前PC值输出
);
    parameter RESET_ADDR = 32'h80000000;
    // PC寄存器，初始化为复位地址
    reg [31:0] PCReg = RESET_ADDR;
    always @(posedge clk) begin
        if (rst) begin
            PCReg <= RESET_ADDR;
        end else if (PCEnable) begin
            PCReg <= NextPC;
        end
    end
    assign PC = PCReg;

endmodule
