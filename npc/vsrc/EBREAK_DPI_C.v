module EBREAK_DPI_C(
    input clk,
    input valid,//当前提交的指令是否是ebreak
    input [31:0] pc,//当前ebreak的PC
    input [31:0] code//a0，作为halt(code)的返回码
);

import "DPI-C" function void npc_ebreak(input int pc, input int code);
always @(posedge clk) begin
    if (valid) begin
        npc_ebreak(pc, code);
    end
end
endmodule