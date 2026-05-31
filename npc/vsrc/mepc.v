module mepc(
    input clk,
    input rst,
    input wen,
    //ecall异常触发时自动写入
    input ExceptionWE,
    input [31:0]ExceptionData,
    input [31:0]wdata,
    output [31:0]rdata
);
    reg[31:0] RegMepc=32'b0;
    always @(posedge clk) begin
        if (rst) begin
        RegMepc<=32'b0;
        end else if(ExceptionWE)begin//异常优先跑
            RegMepc<=ExceptionData;
        end else if(wen)begin
            RegMepc<=wdata;
        end
    end
    assign rdata=RegMepc;
endmodule