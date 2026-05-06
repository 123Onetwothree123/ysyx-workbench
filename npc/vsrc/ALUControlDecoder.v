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
    localparam [3:0] ALUCTRL_SUB    = 4'b0001;
    localparam [3:0] ALUCTRL_SLL    = 4'b0010;
    localparam [3:0] ALUCTRL_SLT    = 4'b0011;
    localparam [3:0] ALUCTRL_SLTU   = 4'b0100;
    localparam [3:0] ALUCTRL_XOR    = 4'b0101;
    localparam [3:0] ALUCTRL_SRL    = 4'b0110;
    localparam [3:0] ALUCTRL_SRA    = 4'b0111;
    localparam [3:0] ALUCTRL_OR     = 4'b1000;
    localparam [3:0] ALUCTRL_AND    = 4'b1001;
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
                        if (!((funct3 == 3'b000) || (funct3 == 3'b001) || (funct3 == 3'b010) || (funct3 == 3'b100) || (funct3 == 3'b101))) begin
                            ALUCtrl = ALUCTRL_NOP;
                            Illegal = 1'b1;
                        end
                    end
                    `OPCODE_Store: begin
                        ALUCtrl = ALUCTRL_ADD;
                        if (!((funct3 == 3'b000) || (funct3 == 3'b001) || (funct3 == 3'b010))) begin
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
                if (is_immediate) begin
                    case (funct3)
                        3'b000: begin
                            ALUCtrl = ALUCTRL_ADD;
                        end
                        3'b001: begin
                            if (funct7 == 7'b0000000) begin
                                ALUCtrl = ALUCTRL_SLL;
                            end else begin
                                ALUCtrl = ALUCTRL_NOP;
                                Illegal = 1'b1;
                            end
                        end
                        3'b010: begin
                            ALUCtrl = ALUCTRL_SLT;
                        end
                        3'b011: begin
                            ALUCtrl = ALUCTRL_SLTU;
                        end
                        3'b100: begin
                            ALUCtrl = ALUCTRL_XOR;
                        end
                        3'b101: begin
                            if (funct7 == 7'b0000000) begin
                                ALUCtrl = ALUCTRL_SRL;
                            end else if (funct7 == 7'b0100000) begin
                                ALUCtrl = ALUCTRL_SRA;
                            end else begin
                                ALUCtrl = ALUCTRL_NOP;
                                Illegal = 1'b1;
                            end
                        end
                        3'b110: begin
                            ALUCtrl = ALUCTRL_OR;
                        end
                        3'b111: begin
                            ALUCtrl = ALUCTRL_AND;
                        end
                        default: begin
                            ALUCtrl = ALUCTRL_NOP;
                            Illegal = 1'b1;
                        end
                    endcase
                end
                else if (is_register) begin
                    case (funct3)
                        3'b000: begin
                            if (funct7 == 7'b0000000) begin
                                ALUCtrl = ALUCTRL_ADD;
                            end else if (funct7 == 7'b0100000) begin
                                ALUCtrl = ALUCTRL_SUB;
                            end else begin
                                ALUCtrl = ALUCTRL_NOP;
                                Illegal = 1'b1;
                            end
                        end
                        3'b001: begin
                            if (funct7 == 7'b0000000) begin
                                ALUCtrl = ALUCTRL_SLL;
                            end else begin
                                ALUCtrl = ALUCTRL_NOP;
                                Illegal = 1'b1;
                            end
                        end
                        3'b010: begin
                            if (funct7 == 7'b0000000) begin
                                ALUCtrl = ALUCTRL_SLT;
                            end else begin
                                ALUCtrl = ALUCTRL_NOP;
                                Illegal = 1'b1;
                            end
                        end
                        3'b011: begin
                            if (funct7 == 7'b0000000) begin
                                ALUCtrl = ALUCTRL_SLTU;
                            end else begin
                                ALUCtrl = ALUCTRL_NOP;
                                Illegal = 1'b1;
                            end
                        end
                        3'b100: begin
                            if (funct7 == 7'b0000000) begin
                                ALUCtrl = ALUCTRL_XOR;
                            end else begin
                                ALUCtrl = ALUCTRL_NOP;
                                Illegal = 1'b1;
                            end
                        end
                        3'b101: begin
                            if (funct7 == 7'b0000000) begin
                                ALUCtrl = ALUCTRL_SRL;
                            end else if (funct7 == 7'b0100000) begin
                                ALUCtrl = ALUCTRL_SRA;
                            end else begin
                                ALUCtrl = ALUCTRL_NOP;
                                Illegal = 1'b1;
                            end
                        end
                        3'b110: begin
                            if (funct7 == 7'b0000000) begin
                                ALUCtrl = ALUCTRL_OR;
                            end else begin
                                ALUCtrl = ALUCTRL_NOP;
                                Illegal = 1'b1;
                            end
                        end
                        3'b111: begin
                            if (funct7 == 7'b0000000) begin
                                ALUCtrl = ALUCTRL_AND;
                            end else begin
                                ALUCtrl = ALUCTRL_NOP;
                                Illegal = 1'b1;
                            end
                        end
                        default: begin
                            ALUCtrl = ALUCTRL_NOP;
                            Illegal = 1'b1;
                        end
                    endcase
                end
                else begin
                    ALUCtrl = ALUCTRL_NOP;
                    Illegal = 1'b1;
                end
            end
            ALUOP_MISC: begin
                case (opcode)
                    `OPCODE_UpperImmediate_lui: begin
                        ALUCtrl = ALUCTRL_ADD;
                    end
                    default: begin
                        ALUCtrl = ALUCTRL_NOP;
                        Illegal = 1'b1;
                    end
                endcase
            end
            ALUOP_BRANCH: begin
                ALUCtrl = ALUCTRL_SUB;
                if (!((funct3 == 3'b000) || (funct3 == 3'b001) ||
                      (funct3 == 3'b100) || (funct3 == 3'b101) ||
                      (funct3 == 3'b110) || (funct3 == 3'b111))) begin
                    ALUCtrl = ALUCTRL_NOP;
                    Illegal = 1'b1;
                end
            end
            default: begin
                ALUCtrl = ALUCTRL_NOP;
                Illegal = 1'b1;
            end
        endcase
    end
endmodule
