module SDB_DPI_C(
    input [31:0] DebugRdata,//从寄存器堆读出的数据
    output reg [4:0] DebugRaddr//要读取的寄存器地址
);
export "DPI-C" function NPCGetGPR;
function int NPCGetGPR(input int RegNum);
    if (RegNum >= 0 && RegNum < 32) begin
        DebugRaddr = RegNum[4:0];
        NPCGetGPR = DebugRdata;
    end else begin
        NPCGetGPR = 32'b0;
    end
endfunction
endmodule
