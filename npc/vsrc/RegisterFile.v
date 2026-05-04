module RegisterFile #(ADDR_WIDTH = 1, DATA_WIDTH = 1) (
  input clk,
  input [DATA_WIDTH-1:0] wdata,
  input [ADDR_WIDTH-1:0] waddr,
  input wen,
  input  [ADDR_WIDTH-1:0] raddr1,
  output [DATA_WIDTH-1:0] rdata1,
  input  [ADDR_WIDTH-1:0] raddr2,
  output [DATA_WIDTH-1:0] rdata2,
  output [DATA_WIDTH-1:0] ebreakcode_gtest,
  //给sdb的
  input [ADDR_WIDTH-1:0] debug_raddr,
  output [DATA_WIDTH-1:0] debug_rdata,
  input debug_clk,
  input [ADDR_WIDTH-1:0] debug_waddr,
  input [DATA_WIDTH-1:0] debug_wdata,
  input debug_wen
);
  reg [DATA_WIDTH-1:0] rf [2**ADDR_WIDTH-1:0];
  always @(posedge clk or posedge debug_clk) begin
    if (debug_clk) begin
      if (debug_wen && debug_waddr != {ADDR_WIDTH{1'b0}}) rf[debug_waddr] <= debug_wdata;
    end else if (wen) begin
      rf[waddr] <= wdata;
    end
  end
  assign rdata1 = rf[raddr1];
  assign rdata2 = rf[raddr2];
  assign ebreakcode_gtest = rf[10];
  //sdb的
  assign debug_rdata = rf[debug_raddr];
endmodule
