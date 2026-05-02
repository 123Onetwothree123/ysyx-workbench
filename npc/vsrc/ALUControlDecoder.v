//他妈的烦死了，他妈了个逼的，我他妈的现在看f3和f7还不够，然后实在是干不了了，问了下AIGPT5.4一般怎么做的，结果
//你妈的告诉老子还得去再判断下是R还是I类型，所以还是得去看opcode
`include "opcode.vh"
module ALUControlDecoder(
    input  [1:0] ALUOp,
    input  [6:0] opcode,
    input  [2:0] funct3,
    input  [6:0] funct7,
    output reg [3:0] ALUCtrl,
    output reg Illegal  // 当前未实现或不支持的指令编码标记
);
    localparam [1:0] ALUOP_ADDR   = 2'b00;
    localparam [1:0] ALUOP_BRANCH = 2'b01;
    localparam [1:0] ALUOP_ARITH  = 2'b10;
    localparam [1:0] ALUOP_MISC   = 2'b11;
    localparam [3:0] ALUCTRL_ADD    = 4'b0000;
    localparam [3:0] ALUCTRL_COPY_B = 4'b1010;
    localparam [3:0] ALUCTRL_NOP    = 4'b1111;
    wire is_immediate = (opcode == `OPCODE_Immediate);
    wire is_register  = (opcode == `OPCODE_Register);
    always @(*) begin
        ALUCtrl = ALUCTRL_NOP;
        Illegal = 1'b0;
        case (ALUOp)
            ALUOP_ADDR: begin
                case (opcode)
                    `OPCODE_Immediate_Lxxx: begin
                        ALUCtrl = ALUCTRL_ADD;
                        if (!((funct3 == 3'b010) || (funct3 == 3'b100))) begin
                            ALUCtrl = ALUCTRL_NOP;
                            Illegal = 1'b1;
                        end
                    end
                    `OPCODE_Store: begin
                        ALUCtrl = ALUCTRL_ADD;
                        if (!((funct3 == 3'b000) || (funct3 == 3'b010))) begin
                            ALUCtrl = ALUCTRL_NOP;
                            Illegal = 1'b1;
                        end
                    end
                    `OPCODE_Immediate_Bxxx: begin
                        ALUCtrl = ALUCTRL_ADD;
                        if (funct3 != 3'b000) begin
                            ALUCtrl = ALUCTRL_NOP;
                            Illegal = 1'b1;
                        end
                    end
                    `OPCODE_UpperImmediate_auipc,
                    `OPCODE_Jump: begin
                        ALUCtrl = ALUCTRL_ADD;
                    end
                    default: begin
                        ALUCtrl = ALUCTRL_NOP;
                        Illegal = 1'b1;
                    end
                endcase
            end
            ALUOP_ARITH: begin
                ALUCtrl = ALUCTRL_ADD;
                if (is_immediate) begin
                    if (funct3 != 3'b000) begin
                        ALUCtrl = ALUCTRL_NOP;
                        Illegal = 1'b1;
                    end
                end
                else if (is_register) begin
                    if (!((funct3 == 3'b000) && (funct7 == 7'b0000000))) begin
                        ALUCtrl = ALUCTRL_NOP;
                        Illegal = 1'b1;
                    end
                end
                else begin
                    ALUCtrl = ALUCTRL_NOP;
                    Illegal = 1'b1;
                end
            end
            ALUOP_MISC: begin
                case (opcode)
                    `OPCODE_UpperImmediate_lui: begin
                        ALUCtrl = ALUCTRL_COPY_B;
                    end
                    default: begin
                        ALUCtrl = ALUCTRL_NOP;
                        Illegal = 1'b1;
                    end
                endcase
            end
            ALUOP_BRANCH: begin
                ALUCtrl = ALUCTRL_NOP;
                Illegal = 1'b1;
            end
            default: begin
                ALUCtrl = ALUCTRL_NOP;
                Illegal = 1'b1;
            end
        endcase
    end
endmodule
