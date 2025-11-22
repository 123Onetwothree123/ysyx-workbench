`include "VerilogHead.vh"
module pc(
    input[31:0] DATAIn,
    input WE,
    input clk,
    input reset,
    output[31:0] QOut
);
    Reg #(32,WE) my_reg(.clk(clk),.rst(reset),.din(DATAIn),.dout(QOut),.wen(WE));
endmodule