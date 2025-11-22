module alu(
    input  [3:0] A,          // 操作数 A (补码)
    input  [3:0] B,          // 操作数 B (补码)
    input  [2:0] ALU_Sel,    // 功能选择 (Button 或 SW)
    output reg [3:0] Result, // 运算结果
    output reg Zero,         // 零标志
    output reg Carry,        // 进位标志
    output reg Overflow      // 溢出标志
    );
    // 内部信号：用于扩展位宽以捕获进位
    wire [4:0] full_add;
    wire [4:0] full_sub;
    assign full_add = {1'b0, A} + {1'b0, B};
    assign full_sub = {1'b0, A} - {1'b0, B};
    always @(*) begin
        Carry = 1'b0;
        Overflow = 1'b0;
        case (ALU_Sel)
            //加法
            3'b000:begin
                Result = A + B;
                Carry = full_add[4];
                Overflow = (A[3] == B[3]) && (Result[3] != A[3]);
            end
            //减法
            3'b001:begin
                Result=A-B;
                Carry=full_add[4];
                Overflow = (A[3] != B[3]) && (Result[3] != A[3]);
            end
            //取反
            3'b010:begin
                Result = ~A;
            end
            //与
            3'b011:begin
                Result=A&B;
            end
            //或
            3'b100:begin
                Result=A|B;
            end
            //异或
            3'b101:begin
                Result=A^B;
            end
            //比较大小
            3'b110:begin
                if ($signed(A)<$signed(B)) begin
                    Result=4'b0001;
                end else begin
                    Result=4'b0000;
                end
            end
            3'b111:begin
                if (A==B) begin
                    Result=4'b0001;
                end else begin
                    Result=4'b0000;
                end
            end
            default: begin
                Result = 4'b0000;
            end
        endcase
        if (ALU_Sel == 3'b000 || ALU_Sel == 3'b001) begin
            Zero=(Result == 4'b0000);
        end else begin
            Zero=1'b0;
        end
    end
endmodule