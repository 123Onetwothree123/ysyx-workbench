// VerilogHead.vh - 项目共用头文件

// 时钟频率
`define CLOCK_FREQ 50_000_000  // 50MHz

// 消抖时间
`define DEBOUNCE_TIME 20_000_000  // 20ms @ 50MHz
`define DEBOUNCE_WIDTH $clog2(`DEBOUNCE_TIME)

// 通用移位寄存器控制信号
`define USR_CLEAR   3'b000  // 清0
`define USR_LOAD    3'b001  // 置数
`define USR_SRL     3'b010  // 逻辑右移
`define USR_SLL     3'b011  // 逻辑左移
`define USR_SRA     3'b100  // 算数右移
`define USR_SER_IN  3'b101  // 串行输入
`define USR_SRL_2   3'b110  // 逻辑右移
`define USR_SLL_2   3'b111  // 逻辑左移

// LFSR 多项式 (8位)
`define LFSR_TAPS 8'b0011_1000

// 七段数码管编码 (标准共阴极 - gfedcba)
`define SEG_0 7'b011_1111
`define SEG_1 7'b000_0110
`define SEG_2 7'b101_1011
`define SEG_3 7'b100_1111
`define SEG_4 7'b110_0110
`define SEG_5 7'b110_1101
`define SEG_6 7'b111_1101
`define SEG_7 7'b000_0111
`define SEG_8 7'b111_1111
`define SEG_9 7'b110_1111
`define SEG_A 7'b111_0111
`define SEG_B 7'b111_1100
`define SEG_C 7'b011_1001
`define SEG_D 7'b101_1110
`define SEG_E 7'b111_1001
`define SEG_F 7'b111_0001

// 数据位宽
`define DATA_WIDTH 8
