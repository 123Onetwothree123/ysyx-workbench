// 简单的4位计数器模块
module counter(
    input wire clk,
    input wire rst_n,
    input wire enable,
    output reg [3:0] count
);

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            count <= 4'b0000;
        end else if (enable) begin
            count <= count + 1;
        end
    end

endmodule
