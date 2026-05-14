module mstatus (
    input clk,
    input rst,
    input wen,
    input [31:0]wdata,
    output [31:0]rdata
);
  reg [31:0]RegMstatus=32'b0;
  always @(posedge clk) begin
    if (rst) begin
        RegMstatus<=32'b0;
    end
    else if (wen) begin
        RegMstatus<=wdata;
    end
  end
  assign rdata=RegMstatus;
endmodule