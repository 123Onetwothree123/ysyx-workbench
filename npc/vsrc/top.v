`include "VerilogHead.vh"
module top(
    input clk,//时钟
    input rst_n,//复位
    input WE,
    input[7:0] DATAIn,
    output [7:0] scpuResult,
    output [7:0] seg0,
    output [7:0] seg1,
    output [7:0] seg2,
    output [7:0] seg3,
    output [7:0] seg4,
    output [7:0] seg5,
    output [7:0] seg6,
    output [7:0] seg7
);
    wire [6:0] seg_lo;
    wire [6:0] seg_hi;

    scpu u_scpu (
        .clk(clk),
        .rst_n(rst_n),
        .WE(WE),
        .DATAIn(DATAIn),
        .scpuResult(scpuResult)
    );

    hex_7seg seg_low_inst (
        .hex(scpuResult[3:0]),
        .seg(seg_lo)
    );

    hex_7seg seg_high_inst (
        .hex(scpuResult[7:4]),
        .seg(seg_hi)
    );

    // NVBoard 7-seg widget (len=8) interprets seg[7:0] as {A,B,C,D,E,F,G,DP}
    // and uses active-low segments (0 = on).
    // hex_7seg outputs {G,F,E,D,C,B,A}, active-low.
    assign seg0 = {seg_lo[0], seg_lo[1], seg_lo[2], seg_lo[3], seg_lo[4], seg_lo[5], seg_lo[6], 1'b1};
    assign seg1 = {seg_hi[0], seg_hi[1], seg_hi[2], seg_hi[3], seg_hi[4], seg_hi[5], seg_hi[6], 1'b1};
    assign seg2 = 8'hff;
    assign seg3 = 8'hff;
    assign seg4 = 8'hff;
    assign seg5 = 8'hff;
    assign seg6 = 8'hff;
    assign seg7 = 8'hff;
endmodule
