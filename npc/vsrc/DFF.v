`include "VerilogHead.vh"
module DFF(
    input clk,
    input rst,
    input din,
    output dout,
    input wen
);
  Reg #(.WIDTH(1),.RESET_VAL(0)) u_reg(.clk(clk),.rst(rst),.din(din),.dout(dout),.wen(wen));
endmodule
