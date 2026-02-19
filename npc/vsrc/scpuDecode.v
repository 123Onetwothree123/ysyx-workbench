`include "VerilogHead.vh"
module scpuDecode(
    input [7:0] Instruction,
    output reg is_add,
    output reg is_out,
    output reg is_li,
    output reg is_bner0
);
    localparam [1:0] OPCODE_ADD   = 2'b00;
    localparam [1:0] OPCODE_OUT   = 2'b01;
    localparam [1:0] OPCODE_LI    = 2'b10;
    localparam [1:0] OPCODE_BNER0 = 2'b11;
    localparam [3:0] OUT_FUNCT    = 4'b0000;

    wire [1:0] DecodeInstruction;
    assign DecodeInstruction = Instruction[7:6];
    always @(*) begin
        is_add = 0;
        is_out = 0;
        is_li = 0;
        is_bner0 = 0;
        if (DecodeInstruction == OPCODE_ADD) begin
            is_add = 1;
        end
        else if (DecodeInstruction == OPCODE_OUT) begin
            is_out = (Instruction[3:0] == OUT_FUNCT);
        end
        else if (DecodeInstruction == OPCODE_LI) begin
            is_li = 1;
        end
        else if (DecodeInstruction == OPCODE_BNER0) begin
            is_bner0 = 1;
        end
    end
endmodule
