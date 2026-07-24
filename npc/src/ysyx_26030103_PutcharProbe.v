module ysyx_26030103_PutcharProbe(
  input clk,
  input en,
  input [7:0] data,
  output reg done
);
  reg en_r;
  always @(posedge clk) begin
    en_r <= en;
    done <= 1'b0;
    if (en && !en_r) begin
      $write("%c", data);
      done <= 1'b1;
    end
  end
endmodule
