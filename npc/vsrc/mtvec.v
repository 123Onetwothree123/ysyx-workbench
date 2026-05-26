module mtvec(
    input clk,
    input rst,
    input wen,
    input [31:0]wdata,
    output [31:0]rdata
);
    reg [31:0]RegMtvec=32'b0;
    always @(posedge clk) begin
        if (rst) begin
            RegMtvec<=32'b0;
        end else if(wen)begin
            RegMtvec<=wdata;
        end
    end
    assign rdata=RegMtvec;
endmodule