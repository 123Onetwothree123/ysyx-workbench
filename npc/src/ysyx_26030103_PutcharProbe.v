module ysyx_26030103_PutcharProbe(
  input clk,
  input en,
  input [7:0] data
);
  always @(posedge clk)
    if (en) $write("%c", data);
endmodule
