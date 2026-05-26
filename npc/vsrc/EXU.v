//EXU(EXecution Unit): 负责根据控制信号控制ALU, 对数据进行计算
module EXU(
    input [3:0]ALUCtrl,
    input [31:0] SourceDATA_A,
    input [31:0] SourceDATA_B,
    output [31:0] ALUResult
);
//简单起见就直接输入给alu，以后再考虑搞个控制环境吧
    ALU alu(
        .A(SourceDATA_A),
        .B(SourceDATA_B),
        .ALUCtrl(ALUCtrl),
        .result(ALUResult)
    );
endmodule