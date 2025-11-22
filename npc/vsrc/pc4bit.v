`include "VerilogHead.vh"
module pc4bit(
    input [3:0] DATAIn,
    input WE,
    input clk,
    input reset,
    output [3:0] QOut
);
    Reg #(4, 0) my_reg(.clk(clk), .rst(reset), .din(DATAIn), .dout(QOut), .wen(WE));
endmodule