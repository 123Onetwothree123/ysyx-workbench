`include "VerilogHead.vh"
//PS/2扫描码到ASCII码转换ROM
//只实现字符和数字键，不考虑组合键和小键盘
module scan_to_ascii_ROM (
    input  [7:0] scan_code,//PS/2扫描码（通码）
    output [7:0] ascii//ASCII码
);
wire [575:0] lut_data;
assign lut_data = {
    //数字键
    8'h45, 8'h30,  // 0 -> '0' (0x30)
    8'h16, 8'h31,  // 1 -> '1' (0x31)
    8'h1E, 8'h32,  // 2 -> '2' (0x32)
    8'h26, 8'h33,  // 3 -> '3' (0x33)
    8'h25, 8'h34,  // 4 -> '4' (0x34)
    8'h2E, 8'h35,  // 5 -> '5' (0x35)
    8'h36, 8'h36,  // 6 -> '6' (0x36)
    8'h3D, 8'h37,  // 7 -> '7' (0x37)
    8'h3E, 8'h38,  // 8 -> '8' (0x38)
    8'h46, 8'h39,  // 9 -> '9' (0x39)
    //字母键
    8'h1C, 8'h61,  // A -> 'a' (0x61)
    8'h32, 8'h62,  // B -> 'b' (0x62)
    8'h21, 8'h63,  // C -> 'c' (0x63)
    8'h23, 8'h64,  // D -> 'd' (0x64)
    8'h24, 8'h65,  // E -> 'e' (0x65)
    8'h2B, 8'h66,  // F -> 'f' (0x66)
    8'h34, 8'h67,  // G -> 'g' (0x67)
    8'h33, 8'h68,  // H -> 'h' (0x68)
    8'h43, 8'h69,  // I -> 'i' (0x69)
    8'h3B, 8'h6A,  // J -> 'j' (0x6A)
    8'h42, 8'h6B,  // K -> 'k' (0x6B)
    8'h4B, 8'h6C,  // L -> 'l' (0x6C)
    8'h3A, 8'h6D,  // M -> 'm' (0x6D)
    8'h31, 8'h6E,  // N -> 'n' (0x6E)
    8'h44, 8'h6F,  // O -> 'o' (0x6F)
    8'h4D, 8'h70,  // P -> 'p' (0x70)
    8'h15, 8'h71,  // Q -> 'q' (0x71)
    8'h2D, 8'h72,  // R -> 'r' (0x72)
    8'h1B, 8'h73,  // S -> 's' (0x73)
    8'h2C, 8'h74,  // T -> 't' (0x74)
    8'h3C, 8'h75,  // U -> 'u' (0x75)
    8'h2A, 8'h76,  // V -> 'v' (0x76)
    8'h1D, 8'h77,  // W -> 'w' (0x77)
    8'h22, 8'h78,  // X -> 'x' (0x78)
    8'h35, 8'h79,  // Y -> 'y' (0x79)
    8'h1A, 8'h7A   // Z -> 'z' (0x7A)
};
MuxKeyInternal #(.NR_KEY(36),.KEY_LEN(8),.DATA_LEN(8),.HAS_DEFAULT(1)) u_max(
    .out(ascii),
    .key(scan_code),
    .default_out(8'h3f),
    .lut(lut_data)
);
endmodule
