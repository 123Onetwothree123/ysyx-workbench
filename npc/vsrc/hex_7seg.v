`include "VerilogHead.vh"

module hex_7seg (
    input [3:0] hex,      // 4位输入，2进制或者16进制
    output reg [6:0] seg  // 数码管7位输出 (gfedcba)
);
    // 使用case语句实现十六进制到七段数码管的译码
    // 标准共阴极7段数码管编码 (gfedcba)
    always @(*) begin
        case (hex)
            4'h0: seg = 7'b011_1111; // 0 -> 0x3F
            4'h1: seg = 7'b000_0110; // 1 -> 0x06
            4'h2: seg = 7'b101_1011; // 2 -> 0x5B
            4'h3: seg = 7'b100_1111; // 3 -> 0x4F
            4'h4: seg = 7'b110_0110; // 4 -> 0x66
            4'h5: seg = 7'b110_1101; // 5 -> 0x6D
            4'h6: seg = 7'b111_1101; // 6 -> 0x7D
            4'h7: seg = 7'b000_0111; // 7 -> 0x07
            4'h8: seg = 7'b111_1111; // 8 -> 0x7F
            4'h9: seg = 7'b110_1111; // 9 -> 0x6F
            4'hA: seg = 7'b111_0111; // A -> 0x77
            4'hB: seg = 7'b111_1100; // B -> 0x7C
            4'hC: seg = 7'b011_1001; // C -> 0x39
            4'hD: seg = 7'b101_1110; // D -> 0x5E
            4'hE: seg = 7'b111_1001; // E -> 0x79
            4'hF: seg = 7'b111_0001; // F -> 0x71
            default: seg = 7'b111_1111; // 默认全灭
        endcase
    end
endmodule
