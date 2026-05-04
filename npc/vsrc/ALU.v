`include "opcode.vh"
module ALU(
    input  [31:0] A,
    input  [31:0] B,
    input  [3:0]  ALUCtrl,
    output reg [31:0] result
);
    localparam [3:0] ALUCTRL_ADD    = 4'b0000;
    localparam [3:0] ALUCTRL_NOP    = 4'b1111;
    localparam [3:0] ALUCTRL_SUB    = 4'b0001;
    localparam [3:0] ALUCTRL_SLL    = 4'b0010;
    localparam [3:0] ALUCTRL_SLT    = 4'b0011;
    localparam [3:0] ALUCTRL_SLTU   = 4'b0100;
    localparam [3:0] ALUCTRL_XOR    = 4'b0101;
    localparam [3:0] ALUCTRL_SRL    = 4'b0110;
    localparam [3:0] ALUCTRL_SRA    = 4'b0111;
    localparam [3:0] ALUCTRL_OR     = 4'b1000;
    localparam [3:0] ALUCTRL_AND    = 4'b1001;
    always @(*) begin
        case(ALUCtrl)
            ALUCTRL_ADD: begin
                result = A + B;
            end
            ALUCTRL_SUB:begin
                result=A-B;
            end
            ALUCTRL_SLL: begin
                result = A << B[4:0];
            end
            ALUCTRL_XOR: begin
                result = A ^ B;
            end
            ALUCTRL_SRL: begin
                result = A >> B[4:0];
            end
            ALUCTRL_OR: begin
                result = A | B;
            end
            ALUCTRL_AND: begin
                result = A & B;
            end
            ALUCTRL_SLT: begin
                result = ($signed(A) < $signed(B)) ? 32'b1 : 32'b0;
            end
            ALUCTRL_SLTU: begin
                result = ($unsigned(A) < $unsigned(B)) ? 32'b1 : 32'b0;
            end
            ALUCTRL_SRA: begin
                result = $signed(A) >>> B[4:0];
            end
            default: begin
                result = 32'b0;
            end
        endcase
    end
endmodule
