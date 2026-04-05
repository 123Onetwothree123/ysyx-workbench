//LSU(Load-Store Unit): 负责根据控制信号控制存储器, 从存储器中读出数据, 或将数据写入存储器
module LSU(
    //本来不想加这个接口的，本来想只用MemWrite信号的，结果这个新的设计方案logisim画不出来，然后问了几个ai还是要做双信号
    input MemValid,//当前周期是否发起一次数据访存
    input MemWrite,
    input [1:0] WidthSel,// 00:字节，01:半字，10:字
    input [31:0] ALUResult,
    input [31:0] MemoryReadDATA,//从内存读的原始数据
    input [31:0] StoreDATA,//这玩意就相当于rs2，store要写的数据
    input LoadSigned,//区分LB/LBU和未来我可能会选择支持的LH/LHU，表示load后要不要做无符号扩展，0是零扩展，1是符号扩展
    output reg MemWE,//给memory的写使能
    output [31:0] MemAddr,
    output reg[31:0] MemWriteDATA,
    output reg [3:0] MemWriteMask,//按字节写使能，为了支持SB和SW，SH以后再说吧，烦了
    output reg [31:0] LoadDATA,//LSU处理完最终读数据后，送给WBU写回寄存器的
    output reg AddrMisaligned//反正到时候地址未对齐的时候给个异常指示
);
    assign MemAddr = ALUResult;
    always @(*) begin
        MemWE = MemValid && MemWrite;
        MemWriteDATA = 32'b0;
        MemWriteMask = 4'b0;

        if (MemValid && MemWrite) begin
            case (WidthSel)
                2'b00: begin
                    case (ALUResult[1:0])
                        2'b00: begin
                            MemWriteDATA = {24'b0, StoreDATA[7:0]};
                            MemWriteMask = 4'b0001;
                        end
                        2'b01: begin
                            MemWriteDATA = {16'b0, StoreDATA[7:0], 8'b0};
                            MemWriteMask = 4'b0010;
                        end
                        2'b10: begin
                            MemWriteDATA = {8'b0, StoreDATA[7:0], 16'b0};
                            MemWriteMask = 4'b0100;
                        end
                        2'b11: begin
                            MemWriteDATA = {StoreDATA[7:0], 24'b0};
                            MemWriteMask = 4'b1000;
                        end
                        default: begin
                            MemWriteDATA = 32'b0;
                            MemWriteMask = 4'b0;
                        end
                    endcase
                end
                2'b10: begin
                    MemWriteDATA = StoreDATA;
                    MemWriteMask = 4'b1111;
                end
                2'b01: begin
                    case (ALUResult[1])
                        1'b0: begin
                            MemWriteDATA = {16'b0, StoreDATA[15:0]};
                            MemWriteMask = 4'b0011;
                        end
                        1'b1: begin
                            MemWriteDATA = {StoreDATA[15:0], 16'b0};
                            MemWriteMask = 4'b1100;
                        end
                    endcase
                end
                default: begin
                    MemWriteDATA = 32'b0;
                    MemWriteMask = 4'b0;
                end
            endcase
        end
    end

    always @(*) begin
        LoadDATA = 32'b0;
        if (MemValid && !MemWrite) begin
            case (WidthSel)
                2'b00: begin
                    case (ALUResult[1:0])
                        2'b00: LoadDATA = LoadSigned ? {{24{MemoryReadDATA[7]}}, MemoryReadDATA[7:0]} : {24'b0, MemoryReadDATA[7:0]};
                        2'b01: LoadDATA = LoadSigned ? {{24{MemoryReadDATA[15]}}, MemoryReadDATA[15:8]} : {24'b0, MemoryReadDATA[15:8]};
                        2'b10: LoadDATA = LoadSigned ? {{24{MemoryReadDATA[23]}}, MemoryReadDATA[23:16]} : {24'b0, MemoryReadDATA[23:16]};
                        2'b11: LoadDATA = LoadSigned ? {{24{MemoryReadDATA[31]}}, MemoryReadDATA[31:24]} : {24'b0, MemoryReadDATA[31:24]};
                        default: LoadDATA = 32'b0;
                    endcase
                end
                2'b01: begin
                    case (ALUResult[1])
                        1'b0: LoadDATA = LoadSigned ? {{16{MemoryReadDATA[15]}}, MemoryReadDATA[15:0]} : {16'b0, MemoryReadDATA[15:0]};
                        1'b1: LoadDATA = LoadSigned ? {{16{MemoryReadDATA[31]}}, MemoryReadDATA[31:16]} : {16'b0, MemoryReadDATA[31:16]};
                    endcase
                end
                2'b10: begin
                    LoadDATA = MemoryReadDATA;
                end
                default: begin
                    LoadDATA = 32'b0;
                end
            endcase
        end
    end

    always @(*) begin
        if (WidthSel == 2'b10)
            AddrMisaligned = (ALUResult[1:0] != 2'b00);//word直接就检查4字节对齐
        else if (WidthSel == 2'b01)
            AddrMisaligned = (ALUResult[0] != 1'b0);//half先做了再说，但是sh和lh不想做，反正就检查下2字节对齐
        else
            AddrMisaligned = 1'b0;//byte是始终对齐的
    end
endmodule
