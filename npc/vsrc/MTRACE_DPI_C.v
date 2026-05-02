module MTRACE_DPI_C (
    input clk,
    input rst,
    input wen,//0读取，1写入
    input AccessMemory,//这个周期是否访问内存
    input [31:0] PC,
    input [31:0] Address,
    input [31:0] WriteData,
    input [31:0] ReadData,
    input [3:0] WriteMask//字节写掩码
);
import "DPI-C" function void MtraceRecord(input longint PC, input int Address, input int WriteData, input int ReadData, input byte WriteMask, input byte wen);
always @(posedge clk) begin
    if (!rst && AccessMemory) begin
        MtraceRecord({32'b0, PC}, Address, WriteData, ReadData, {4'b0, WriteMask}, {7'b0, wen});
    end
end
endmodule