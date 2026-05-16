//WBU(WriteBack Unit): 将数据写入寄存器, 并更新PC
module WBU(
    input RegWrite,
    input [1:0] WBSel,//00是ALUResult，01是LoadDATA，10是SNPC，11是CSR_rdata
    input [31:0] ALUResult,
    input [31:0] LoadDATA,
    input [31:0] SNPC,
    input [31:0] CSR_rdata,
    output RegisterFileWriteEN,
    output reg[31:0] RegisterFileWriteDATA
);
   assign RegisterFileWriteEN=RegWrite;
   always @(*) begin
    case (WBSel)
        2'b00:begin
            RegisterFileWriteDATA=ALUResult;
        end
        2'b01:begin
            RegisterFileWriteDATA=LoadDATA;
        end
        2'b10:begin
            RegisterFileWriteDATA=SNPC;
        end
        2'b11:begin
            RegisterFileWriteDATA=CSR_rdata;
        end
        default:begin
            RegisterFileWriteDATA=32'b0;
        end
    endcase
   end
endmodule