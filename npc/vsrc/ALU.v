`include "minirv.vh"
module ALU(
    input  [31:0] A,
    input  [31:0] B,
    input  [3:0]  ALUCtrl,
    output reg [31:0] result
);
    localparam [3:0] ALUCTRL_ADD    = 4'b0000;
    localparam [3:0] ALUCTRL_COPY_B = 4'b1010;
    localparam [3:0] ALUCTRL_NOP    = 4'b1111;
    always @(*) begin
        case(ALUCtrl)
            ALUCTRL_ADD: begin
                result = A + B;
            end
            ALUCTRL_COPY_B: begin
                result = B;
            end
            default: begin
                result = 32'b0;
            end
        endcase
    end
endmodule