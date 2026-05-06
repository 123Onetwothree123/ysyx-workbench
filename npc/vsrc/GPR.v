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
    wire [31:0] rf_rdata1;
    wire [31:0] rf_rdata2;
    wire [31:0] rf_ebreakcode_gtest;
    wire [31:0] rf_debug_rdata;//debug的
    wire rf_wen = (WriteSELECT == 5'b0) ? 1'b0 : WriteEN;
    RegisterFile#(.ADDR_WIDTH(5),.DATA_WIDTH(32)) rf(
        .clk(clk),
        .wdata(wdata),
        .waddr(WriteSELECT),
        .wen(rf_wen),
        .raddr1(Read1SELECT),
        .rdata1(rf_rdata1),
        .raddr2(Read2SELECT),
        .rdata2(rf_rdata2),
        .ebreakcode_gtest(rf_ebreakcode_gtest),
        //sdb
        .debug_raddr(DebugRaddr),
        .debug_rdata(rf_debug_rdata),
        .debug_clk(DebugClk),
        .debug_waddr(DebugWaddr),
        .debug_wdata(DebugWdata),
        .debug_wen(DebugWriteEN)
    );
    assign ReadDATA1 = (Read1SELECT == 5'b0) ? 32'b0 : rf_rdata1;
    assign ReadDATA2 = (Read2SELECT == 5'b0) ? 32'b0 : rf_rdata2;
    assign EbreakCode_gtest = rf_ebreakcode_gtest;
    assign DebugRdata = (DebugRaddr == 5'b0) ? 32'b0 : rf_debug_rdata;
endmodule
