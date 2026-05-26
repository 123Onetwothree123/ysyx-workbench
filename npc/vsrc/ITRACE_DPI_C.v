module ITRACE_DPI_C(
    input        clk,
    input        rst,
    input [31:0] pc,
    input [31:0] inst,
    input [31:0] snpc,
    input        valid
);
import "DPI-C" function void itrace_record(input longint pc, input int inst, input int len);
always @(posedge clk) begin
    if (!rst && valid) begin
        itrace_record({32'b0, pc}, inst, snpc - pc);
    end
end
endmodule
