module CSR(
    input clk,
    input rst,
    //IDU给出的指令译码信号
    input IsCsrrw,
    input IsCsrrs,
    input IsEcall,
    input IsMret,
    input [11:0]CSRAddress,//CSR地址，指令[31:20]
    input [4:0]rs1,//rs1寄存器编号，用于判断csrrs是否写CSR
    input [31:0]Rs1Data,//Rs1寄存器的数据
    input [31:0]pc,//现在的PC，就是ecall时保存到mepc
    output [31:0]CSR_rdata,//CSR读出，写到rd
    output CSRValid,//如果当前是CSR指令，那就需要写回rd
    //异常和返回的跳转控制
    output ExceptionTaken, // 需要跳转，ecall到mtvec，mret到mepc
    output [31:0] ExceptionTarget // 跳转目标地址
);
    // mcycle=12'hB00，低32位
    // mcycleh=12'hB80，高32位
    localparam [11:0]CSR_MVENDORID=12'hF11;
    localparam [11:0]CSR_MARCHID=12'hF12;
    localparam [11:0]CSR_MCYCLE=12'hB00;
    localparam [11:0]CSR_MCYCLEH=12'hB80;
    localparam [11:0] CSR_MSTATUS=12'h300;
    localparam [11:0] CSR_MTVEC=12'h305;
    localparam [11:0] CSR_MEPC=12'h341;
    localparam [11:0] CSR_MCAUSE=12'h342;
    wire McycleSelect=(CSRAddress==CSR_MCYCLE);
    wire McyclehSelect=(CSRAddress==CSR_MCYCLEH);
    wire MstatusSelect=(CSRAddress==CSR_MSTATUS);
    wire MtvecSelect=(CSRAddress==CSR_MTVEC);
    wire MepcSelect=(CSRAddress==CSR_MEPC);
    wire McauseSelect=(CSRAddress==CSR_MCAUSE);
    wire MvendoridSelect=(CSRAddress==CSR_MVENDORID);
    wire MarchidSelect=(CSRAddress==CSR_MARCHID);
    wire McycleAccess=McycleSelect|McyclehSelect;
    wire [31:0]Mvendorid_rdata=32'h79737978;
    wire [31:0]Marchid_rdata=32'h018d3017;
    wire CsrrsWriteEnable=IsCsrrs&&(rs1!=5'b0);
    wire [31:0]Mstatus_rdata;
    // ecall时，MPP写11，MPIE写旧MIE，MIE写0
    //MPP，00是U，01是S，11是M
    wire [31:0]MstatusEcallData;
    assign MstatusEcallData={Mstatus_rdata[31:13],2'b11,Mstatus_rdata[10:8],Mstatus_rdata[3],Mstatus_rdata[6:4],1'b0,Mstatus_rdata[2:0]};
    wire MstatusWenFromCsrrw=IsCsrrw&&MstatusSelect;
    wire MstatusWenFromCsrrs=CsrrsWriteEnable&&MstatusSelect;
    wire MstatusCSRWen=MstatusWenFromCsrrw||MstatusWenFromCsrrs;
    wire [31:0]MstatusCSRData=IsCsrrw?Rs1Data:(Mstatus_rdata|Rs1Data);
    wire [31:0] MstatusMretData;
    assign MstatusMretData={Mstatus_rdata[31:13],2'b00,Mstatus_rdata[10:8], 1'b1,Mstatus_rdata[6:4],Mstatus_rdata[7],Mstatus_rdata[2:0]};
    //ecall优先>mret>CSR指令
    wire MstatusWen=IsEcall||IsMret||MstatusCSRWen;
    reg [31:0] Mstatus_wdata;
    always @(*) begin
    if (IsEcall)
        Mstatus_wdata=MstatusEcallData;
    else if (IsMret)
        Mstatus_wdata=MstatusMretData;
    else
        Mstatus_wdata=MstatusCSRData;
    end
    mstatus Mstatus(
        .clk(clk),
        .rst(rst),
        .wen(MstatusWen),
        .wdata(Mstatus_wdata),
        .rdata(Mstatus_rdata)
    );
    wire [31:0]Mtvec_rdata;
    wire MtvecWenFromCsrrw=IsCsrrw&&MtvecSelect;
    wire MtvecWenFromCsrrs=CsrrsWriteEnable&&MtvecSelect;
    wire MtvecWen=MtvecWenFromCsrrw||MtvecWenFromCsrrs;
    wire [31:0]Mtvec_wdata=IsCsrrw?Rs1Data:(Mtvec_rdata|Rs1Data);
    mtvec Mtvec(
        .clk(clk),
        .rst(rst),
        .wen(MtvecWen),
        .wdata(Mtvec_wdata),
        .rdata(Mtvec_rdata)
    );
    wire [31:0]Mepc_rdata;
    wire MepcWenFromEcall=IsEcall;
    wire MepcWenFromCsrrw=IsCsrrw&&MepcSelect;
    wire MepcWenFromCsrrs=CsrrsWriteEnable&&MepcSelect;
    wire MepcWen=MepcWenFromEcall||MepcWenFromCsrrw||MepcWenFromCsrrs;
    reg [31:0]Mepc_wdata;
    always @(*) begin
    if (IsEcall)
        Mepc_wdata=pc;
    else if (IsCsrrw)
        Mepc_wdata=Rs1Data;
    else
        Mepc_wdata=Mepc_rdata|Rs1Data;
    end
    mepc Mepc(
        .clk(clk),
        .rst(rst),
        .wen(MepcWen),
        .ExceptionWE(IsEcall),
        .ExceptionData(pc),
        .wdata(Mepc_wdata),
        .rdata(Mepc_rdata)
    );
    wire [31:0]Mcause_rdata;
    wire McauseWenFromEcall=IsEcall;
    wire McauseWenFromCsrrw=IsCsrrw&&McauseSelect;
    wire McauseWenFromCsrrs=CsrrsWriteEnable&&McauseSelect;
    wire McauseWen=McauseWenFromEcall||McauseWenFromCsrrw||McauseWenFromCsrrs;
    reg [31:0]Mcause_wdata;
    always @(*) begin
    if (IsEcall)
        Mcause_wdata=32'd11;
    else if (IsCsrrw)
        Mcause_wdata=Rs1Data;
    else
        Mcause_wdata=Mcause_rdata|Rs1Data;
    end
    mcause Mcause(
        .clk(clk),
        .rst(rst),
        .wen(McauseWen),
        .wdata(Mcause_wdata),
        .rdata(Mcause_rdata)
    );
    //csrrw时写，csrrs时rs1非零才写
    wire McycleWenFromCsrrw=IsCsrrw&&McycleAccess;
    wire McycleWenFromCsrrs=CsrrsWriteEnable&&McycleAccess;
    wire McycleWen=McycleWenFromCsrrw||McycleWenFromCsrrs;
    //csrrw直接写rs1，csrrs得写旧值|rs1
    wire [31:0] Mcycle_rdata;
    wire [31:0] Mcycle_wdata=IsCsrrw?Rs1Data:(Mcycle_rdata|Rs1Data);
    mcycle Mcycle(
        .clk(clk),
        .rst(rst),
        .wen(McycleWen),
        .SelectHigh(McyclehSelect),
        .wdata(Mcycle_wdata),
        .rdata(Mcycle_rdata)
    );
    reg [31:0]CSR_rdataReg;
    reg CSRValidReg;
    always @(*) begin
        CSR_rdataReg=32'b0;
        CSRValidReg=1'b0;
        if (IsCsrrw||IsCsrrs) begin
            CSRValidReg=1'b1;
            case (CSRAddress)
                CSR_MCYCLE:CSR_rdataReg=Mcycle_rdata;
                CSR_MCYCLEH:CSR_rdataReg=Mcycle_rdata;
                CSR_MVENDORID:CSR_rdataReg=Mvendorid_rdata;
                CSR_MARCHID:CSR_rdataReg=Marchid_rdata;
                CSR_MSTATUS:CSR_rdataReg=Mstatus_rdata;
                CSR_MTVEC:CSR_rdataReg=Mtvec_rdata;
                CSR_MEPC:CSR_rdataReg=Mepc_rdata;
                CSR_MCAUSE:CSR_rdataReg=Mcause_rdata;
                default:CSRValidReg=1'b0;
            endcase
        end
    end
    assign CSR_rdata=CSR_rdataReg;
    assign CSRValid=CSRValidReg;
    //ecall跳mtvec，mret跳mepc
    assign ExceptionTaken=IsEcall||IsMret;
    assign ExceptionTarget=IsEcall?Mtvec_rdata:Mepc_rdata;
endmodule
