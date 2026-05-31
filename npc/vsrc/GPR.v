module GPR(
    input clk,
    input [31:0] wdata,
    input [4:0] WriteSELECT,
    input WriteEN,
    input [4:0] Read1SELECT,
    input [4:0] Read2SELECT,
    output [31:0] ReadDATA1,
    output [31:0] ReadDATA2,
    output [31:0] EbreakCode_gtest,
    //给sdb的
    input [4:0] DebugRaddr,
    output [31:0] DebugRdata,
    input DebugClk,
    input [4:0] DebugWaddr,
    input [31:0] DebugWdata,
    input DebugWriteEN
);
    wire [31:0] RegisterFileRdata1;
    wire [31:0] RegisterFileRdata2;
    wire [31:0] RegisterFileEbreakcodeGtest;
    wire [31:0] RegisterFileDebugRdata;//debug的
    wire RegisterFileWen = (WriteSELECT == 5'b0) ? 1'b0 : WriteEN;
    RegisterFile#(.ADDR_WIDTH(5),.DATA_WIDTH(32)) rf(
        .clk(clk),
        .wdata(wdata),
        .waddr(WriteSELECT),
        .wen(RegisterFileWen),
        .raddr1(Read1SELECT),
        .rdata1(RegisterFileRdata1),
        .raddr2(Read2SELECT),
        .rdata2(RegisterFileRdata2),
        .ebreakcode_gtest(RegisterFileEbreakcodeGtest),
        //sdb
        .debug_raddr(DebugRaddr),
        .debug_rdata(RegisterFileDebugRdata),
        .debug_clk(DebugClk),
        .debug_waddr(DebugWaddr),
        .debug_wdata(DebugWdata),
        .debug_wen(DebugWriteEN)
    );
    assign ReadDATA1 = (Read1SELECT == 5'b0) ? 32'b0 : RegisterFileRdata1;
    assign ReadDATA2 = (Read2SELECT == 5'b0) ? 32'b0 : RegisterFileRdata2;
    assign EbreakCode_gtest = RegisterFileEbreakcodeGtest;
    assign DebugRdata = (DebugRaddr == 5'b0) ? 32'b0 : RegisterFileDebugRdata;
endmodule
