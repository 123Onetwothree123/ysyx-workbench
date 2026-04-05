//第一层ALU译码
`include "minirv.vh"
module ALUOpDecoder(
    input  [6:0] opcode,
    output reg [1:0] ALUOp
);
    localparam [1:0] ALUOP_ADDR   = 2'b00;
    localparam [1:0] ALUOP_BRANCH = 2'b01;
    localparam [1:0] ALUOP_ARITH  = 2'b10;
    localparam [1:0] ALUOP_MISC   = 2'b11;
    always @(*) begin
        ALUOp = ALUOP_MISC;
        case (opcode)
            `OPCODE_Immediate_Lxxx,
            `OPCODE_Immediate_Bxxx,
            `OPCODE_Store,
            `OPCODE_UpperImmediate_auipc,
            `OPCODE_Jump: begin
                ALUOp = ALUOP_ADDR;
            end
            `OPCODE_Branch: begin
                ALUOp = ALUOP_BRANCH;
            end
            `OPCODE_Immediate,
            `OPCODE_Register: begin
                ALUOp = ALUOP_ARITH;
            end
            `OPCODE_UpperImmediate_lui: begin
                ALUOp = ALUOP_MISC;
            end
            default: begin
                ALUOp = ALUOP_MISC;
            end
        endcase
    end
endmodule
