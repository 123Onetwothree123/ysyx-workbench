//EXU(EXecution Unit): 负责根据控制信号控制ALU, 对数据进行计算
module EXU(
    input [3:0]ALUCtrl,
    input [31:0] SourceDATA_A,
    input [31:0] SourceDATA_B,
    output [31:0] ALUResult
);
    ALU alu_inst(
        .A(SourceDATA_A),
        .B(SourceDATA_B),
        .ALUCtrl(ALUCtrl),
        .result(ALUResult)
    );
endmodule