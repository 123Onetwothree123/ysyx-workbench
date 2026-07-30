`define CLOCK_FREQ 50_000_000  // 50MHz
// 数据位宽
`define DATA_WIDTH 8
// 消抖时间
`define DEBOUNCE_TIME 20_000_000  // 20ms @ 50MHz
`define DEBOUNCE_WIDTH $clog2(`DEBOUNCE_TIME)
//编码
`define SEG_0 7'b100_0000
`define SEG_1 7'b111_1001
`define SEG_2 7'b010_0100
`define SEG_3 7'b011_0000
`define SEG_4 7'b001_1001
`define SEG_5 7'b001_0010
`define SEG_6 7'b000_0010
`define SEG_7 7'b111_1000
`define SEG_8 7'b000_0000
`define SEG_9 7'b001_0000
`define SEG_A 7'b000_1000
`define SEG_B 7'b000_0011
`define SEG_C 7'b100_0110
`define SEG_D 7'b010_0001
`define SEG_E 7'b000_0110
`define SEG_F 7'b000_1110