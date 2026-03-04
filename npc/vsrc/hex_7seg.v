`include "VerilogHead.vh"

module hex_7seg (
    input [3:0] hex,      // 4位输入，2进制或者16进制
    output reg [6:0] seg  // 数码管7位输出 (gfedcba)
);
    // 使用case语句实现十六进制到七段数码管的译码
    // 共阳极7段数码管编码 (gfedcba) - 低电平点亮
    always @(*) begin
        case (hex)
            4'h0: seg = 7'b100_0000; // 0 -> 0x40
            4'h1: seg = 7'b111_1001; // 1 -> 0x79
            4'h2: seg = 7'b010_0100; // 2 -> 0x24
            4'h3: seg = 7'b011_0000; // 3 -> 0x30
            4'h4: seg = 7'b001_1001; // 4 -> 0x19
            4'h5: seg = 7'b001_0010; // 5 -> 0x12
            4'h6: seg = 7'b000_0010; // 6 -> 0x02
            4'h7: seg = 7'b111_1000; // 7 -> 0x78
            4'h8: seg = 7'b000_0000; // 8 -> 0x00
            4'h9: seg = 7'b001_0000; // 9 -> 0x10
            4'hA: seg = 7'b000_1000; // A -> 0x08
            4'hB: seg = 7'b000_0011; // B -> 0x03
            4'hC: seg = 7'b100_0110; // C -> 0x46
            4'hD: seg = 7'b010_0001; // D -> 0x21
            4'hE: seg = 7'b000_0110; // E -> 0x06
            4'hF: seg = 7'b000_1110; // F -> 0x0E
            default: seg = 7'b111_1111; // 默认全灭
        endcase
    end
endmodule
