module NPC(
    input [31:0] SNPC,
    input [31:0] RedirectTarget,//jal和jalr和branch的目标地址
    input Redirect,
    output [31:0] NextPC,
    output PCEnable
);
assign NextPC = Redirect ? RedirectTarget : SNPC;
assign PCEnable = 1'b1;
endmodule
