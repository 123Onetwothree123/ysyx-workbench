`include "minirv.vh"
module ImmediateGenerator(
    input  [31:0] Instruction,
    output reg [31:0] Immediate
);
wire [6:0] opcode = Instruction[6:0];
wire [11:0] i_imm = Instruction[31:20];
wire [11:0] s_imm = {Instruction[31:25], Instruction[11:7]};
wire [12:0] b_imm = {Instruction[31], Instruction[7], Instruction[30:25], Instruction[11:8], 1'b0};
wire [31:0] u_imm = {Instruction[31:12], 12'b0};
wire [20:0] j_imm = {Instruction[31], Instruction[19:12], Instruction[20], Instruction[30:21], 1'b0};
//这是符号扩展，先标记一下
wire [31:0] i_ext = {{20{i_imm[11]}}, i_imm};
wire [31:0] s_ext = {{20{s_imm[11]}}, s_imm};
wire [31:0] b_ext = {{19{b_imm[12]}}, b_imm};
wire [31:0] j_ext = {{11{j_imm[20]}}, j_imm};
always @(*) begin
    case (opcode)
        `OPCODE_Immediate,
        `OPCODE_Immediate_Lxxx,
        `OPCODE_Immediate_Bxxx:
            Immediate = i_ext;
        `OPCODE_Store:
            Immediate = s_ext;
        `OPCODE_Branch:
            Immediate = b_ext;
        `OPCODE_UpperImmediate_lui,
        `OPCODE_UpperImmediate_auipc:
            Immediate = u_imm;
        `OPCODE_Jump:
            Immediate = j_ext;
        default:
            Immediate = 32'b0;
    endcase
end

endmodule