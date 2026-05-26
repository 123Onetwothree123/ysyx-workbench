module BranchComparator(
    input [31:0] A,
    input [31:0] B,
    input [2:0] Funct3,
    input IsBranch,
    output reg Taken
);
    always @(*) begin
        Taken = 1'b0;
        if (IsBranch) begin
            case (Funct3)
                3'b000: Taken = (A == B);
                3'b001: Taken = (A != B);
                3'b100: Taken = ($signed(A) < $signed(B));
                3'b101: Taken = ($signed(A) >= $signed(B));
                3'b110: Taken = (A < B);
                3'b111: Taken = (A >= B);
                default: Taken = 1'b0;
            endcase
        end
    end
endmodule
