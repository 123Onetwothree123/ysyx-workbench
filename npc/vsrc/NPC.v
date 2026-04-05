module NPC(
    input [31:0] SNPC,
    input [31:0] ALUResult,//jal（反正还没要求先不设计了）和jalr 目标地址
    input IsJump,//jal和jalr
    output [31:0] NextPC,
    output PCEnable
);
assign NextPC = IsJump ? ALUResult : SNPC;
assign PCEnable = 1'b1;
endmodule