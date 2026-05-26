module mcycle(
    input         clk,
    input         rst,
    input         wen,
    input         SelectHigh,
    input  [31:0] wdata,
    output [31:0] rdata
);
reg [63:0] counter = 64'b0;
always @(posedge clk) begin
    if (rst) begin
        counter<=64'b0;
    end else if(wen)begin
        if (SelectHigh) begin
            counter[63:32]<=wdata;
        end else begin
            counter[31:0]<=wdata;
        end
    end else begin
        counter<=counter+64'd1;//默认的情况下
    end
end
assign rdata=SelectHigh?counter[63:32]:counter[31:0];
endmodule
