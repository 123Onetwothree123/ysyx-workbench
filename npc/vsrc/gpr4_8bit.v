`include "VerilogHead.vh"
module gpr4_8bit(
    input clk,
    input rst,
    input[7:0] wdata,
    input[1:0] WriteSELECT,
    input[1:0] Read1SELECT,
    input[1:0] Read2SELECT,
    input WriteEN,
    output[7:0] ReadDATA1,
    output[7:0] ReadDATA2
);
    wire [7:0] reg_dout0;
    wire [7:0] reg_dout1;
    wire [7:0] reg_dout2;
    wire [7:0] reg_dout3;
    // 实例化4个8位寄存器
    Reg #(8, 0) gpr_reg0 (.clk(clk), .rst(rst), .din(wdata), .dout(reg_dout0), .wen(WriteEN && (WriteSELECT == 2'd0)));
    Reg #(8, 0) gpr_reg1 (.clk(clk), .rst(rst), .din(wdata), .dout(reg_dout1), .wen(WriteEN && (WriteSELECT == 2'd1)));
    Reg #(8, 0) gpr_reg2 (.clk(clk), .rst(rst), .din(wdata), .dout(reg_dout2), .wen(WriteEN && (WriteSELECT == 2'd2)));
    Reg #(8, 0) gpr_reg3 (.clk(clk), .rst(rst), .din(wdata), .dout(reg_dout3), .wen(WriteEN && (WriteSELECT == 2'd3)));
    // 读端口1的多路选择器
    MuxKey #(4, 2, 8) read1_mux (
        .out(ReadDATA1),
        .key(Read1SELECT),
        .lut({
            2'd0, reg_dout0,
            2'd1, reg_dout1,
            2'd2, reg_dout2,
            2'd3, reg_dout3
        })
    );
    // 读端口2的多路选择器
    MuxKey #(4, 2, 8) read2_mux (
        .out(ReadDATA2),
        .key(Read2SELECT),
        .lut({
            2'd0, reg_dout0,
            2'd1, reg_dout1,
            2'd2, reg_dout2,
            2'd3, reg_dout3
        })
    );
endmodule
