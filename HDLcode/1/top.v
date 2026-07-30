module top(
    input clk,
    input rst, 
    output reg [7:0] led // 8个 LED
);
    // 简单的跑马灯逻辑
    always @(posedge clk) begin
        if (rst) led <= 1;
        else led <= {led[6:0], led[7]};
    end
endmodule
