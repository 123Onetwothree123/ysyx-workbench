`include "VerilogHead.vh"
module scpuControlUnit(
    input [7:0] Instruction,
    // 指令类型标识
    output reg is_add,
    output reg is_out,
    output reg is_li,
    output reg is_bner0,
    // 寄存器选择信号
    output reg [1:0] rs1_select,// rs1寄存器选择
    output reg [1:0] rs2_select,// rs2寄存器选择
    output reg [1:0] rd_select, // rd寄存器选择
    output reg [1:0] rs_select, // out指令的rs寄存器选择
    // 立即数和跳转地址
    output reg [3:0] imm,       // li指令的4位立即数
    output reg [3:0] jump_addr, // bner0指令的跳转地址
    // 写使能信号
    output reg reg_we,          // 寄存器写使能
    output reg pc_we            // PC写使能(用于跳转)
);
    // 指令opcode编码
    localparam [1:0] OPCODE_ADD   = 2'b00;
    localparam [1:0] OPCODE_OUT   = 2'b01;
    localparam [1:0] OPCODE_LI    = 2'b10;
    localparam [1:0] OPCODE_BNER0 = 2'b11;

    // out指令编码: 01 rs 0000
    localparam [3:0] OUT_FUNCT = 4'b0000;

    // 指令opcode字段
    wire [1:0] opcode;
    assign opcode = Instruction[7:6];
    
    always @(*) begin
        // 默认值
        is_add = 0;
        is_out = 0;
        is_li = 0;
        is_bner0 = 0;
        reg_we = 0;
        pc_we = 0;
        rs1_select = 0;
        rs2_select = 0;
        rd_select = 0;
        rs_select = 0;
        imm = 0;
        jump_addr = 0;
        
        case (opcode)
            OPCODE_ADD: begin // add指令: R[rd]=R[rs1]+R[rs2]
                is_add = 1;
                reg_we = 1;      // 需要写寄存器
                rd_select = Instruction[5:4];  // rd字段
                rs1_select = Instruction[3:2]; // rs1字段
                rs2_select = Instruction[1:0]; // rs2字段
            end
            OPCODE_OUT: begin // out指令: 01 rs 0000 (R[rs][3:0] -> hex_7seg)
                if (Instruction[3:0] == OUT_FUNCT) begin
                    is_out = 1;                  // 作为七段数码管更新使能
                    rs_select = Instruction[5:4]; // rs字段，选择要输出的寄存器
                end
            end
            OPCODE_LI: begin // li指令: R[rd]=imm
                is_li = 1;
                reg_we = 1;      // 需要写寄存器
                rd_select = Instruction[5:4];  // rd字段
                imm = Instruction[3:0];        // 4位立即数
            end
            OPCODE_BNER0: begin // bner0指令: if (R[0]!=R[rs2]) PC=addr
                is_bner0 = 1;
                rs2_select = Instruction[1:0]; // rs2字段
                jump_addr = Instruction[5:2];  // 4位跳转地址
                // pc_we由外部比较器结果决定
            end
            default: begin
                // 未知指令，保持默认值
            end
        endcase
    end
endmodule
