module ysyx_26030103_PutcharProbe(
  input clk,
  input en,
  input [7:0] data,
  output reg done
);
  always @(posedge clk) begin
    done <= 1'b0;
    if (en) begin
      $write("%c", data);
      done <= 1'b1;
    end
  end
endmodule
