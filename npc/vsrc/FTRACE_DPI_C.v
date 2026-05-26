module FTRACE_DPI_C(
    input        clk,
    input        rst,
    input [31:0] pc,
    input [31:0] inst,
    input [31:0] next_pc,
    input        valid
);
import "DPI-C" function void ftrace_record(input longint pc, input int inst, input longint next_pc);
always @(posedge clk) begin
    if (!rst && valid) begin
        ftrace_record({32'b0, pc}, inst, {32'b0, next_pc});
    end
end
endmodule
