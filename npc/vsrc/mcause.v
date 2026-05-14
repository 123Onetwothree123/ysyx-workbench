module mcause(
    input clk,
    input rst,
    input wen,
    input [31:0]wdata,
    output [31:0]rdata
);
    reg [31:0]RegMcause=32'b0;
    always @(posedge clk) begin
        if (rst) begin
            RegMcause<=32'b0;
        end else if (wen) begin
            RegMcause<=wdata;
        end
    end
    assign rdata=RegMcause;
endmodule