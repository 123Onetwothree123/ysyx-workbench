module top(
    input clk,
    input rst,
    output reg [15:0] led
);
    reg [31:0] count;
    parameter MAX_COUNT = 5000000; 

    always @(posedge clk) begin
        if (rst) begin 
            count <= 0; 
            led <= 16'h0001;
        end 
        else begin
            if (count >= MAX_COUNT) begin
                count <= 0;
                led <= {led[14:0], led[15]};
            end 
            else begin
                count <= count + 1;
            end
        end
    end
endmodule