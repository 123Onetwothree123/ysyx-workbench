`include "VerilogHead.vh"
module scpu(
    input clk,//时钟
    input rst_n,//复位
    input WE,
    input[7:0] DATAIn,
    output[7:0] scpuResult
);
    wire rst;//复位信号，高的时候才能复位
    wire [3:0] pc_result;//当前PC值
    wire [3:0] rom_addr;//ROM地址
    wire [7:0] rom_data;//ROM的输出数据
    wire is_add_result;
    wire is_out_result;
    wire is_li_result;
    wire is_bner0_result;
    wire [1:0] rs1_select_result;//1号寄存器的选择结果
    wire [1:0] rs2_select_result;//2号寄存器的选择结果
    wire [1:0] rd_select_result;//目标寄存器的选择结果
    wire [1:0] rs_select_result;//out指令专用的寄存器
    wire [3:0] imm_result;//4bit的立即数
    wire [3:0] jump_addr_result;//跳转地址
    wire reg_we_result;//寄存器写使能
    wire pc_we_result;//PC写使能
    reg [7:0] gpr_wdata;//写入GPR的数据
    wire [1:0] gpr_read1_select;//GPR的读1号端口的选择信号
    wire [7:0] gpr_read_data1;//GPR的1号端口数据
    wire [7:0] gpr_read_data2;//GPR的2号端口数据
    wire [1:0] gpr_read1_select_key;//给1号端口的选择策略的key
    reg [7:0] out_latched;//锁住out指令输出的寄存器
    reg [3:0] pc_next;//当前周期组合逻辑计算出的“下一条指令地址”
    wire pc_wen;//PC写使能
    wire branch_taken;//bner0条件是否成立，成立则跳转到jump_addr_result
    assign rst = ~rst_n;//因为外面的输入是rst_n，所以内部转成高，方便在模块内部使用
    assign pc_wen = 1'b1;//为了方便直接把WE全开了
    assign rom_addr = pc_result;//用PC作为地址取指令
    //bner0的语义是if (R0 != R[rs2]) PC = addr
    //先在这里约定:
    //gpr_read_data1 在 bner0 时强制读 R0
    //gpr_read_data2 由 rs2_select_result 读出 R[rs2]
    //所以只要这二者不等即分支成立
    assign branch_taken = is_bner0_result && (gpr_read_data1 != gpr_read_data2);
    always @(*) begin
        if (branch_taken) begin
            pc_next = jump_addr_result;
        end else begin
            pc_next = pc_result + 4'd1;
        end
    end
    pc4bit scpuPc(
        .clk(clk),
        .reset(rst),
        .WE(pc_wen),
        .DATAIn(pc_next),
        .QOut(pc_result)
    );
    rom8bit #(
        .DATA_WIDTH(8),
        .ADDR_WIDTH(4),
        .DEPTH(16)
    ) scpuROM(
        .addr(rom_addr),
        .data(rom_data)
    );
    scpuControlUnit sCpuControlUnit(
        .Instruction(rom_data),
        .is_add(is_add_result),
        .is_out(is_out_result),
        .is_li(is_li_result),
        .is_bner0(is_bner0_result),
        .rs1_select(rs1_select_result),
        .rs2_select(rs2_select_result),
        .rd_select(rd_select_result),
        .rs_select(rs_select_result),
        .imm(imm_result),
        .jump_addr(jump_addr_result),
        .reg_we(reg_we_result),
        .pc_we(pc_we_result)
    );
    //这是读端口1的选择策略
    // 1，out指令:read1读取rs_select指向的寄存器，用于数码管显示
    // 2，bner0指令:read1固定读取R0，用来和read2的R[rs2]做比较
    // 3，其它指令:read1按rs1_select_result正常读取
    assign gpr_read1_select_key = {is_out_result, is_bner0_result};
    MuxKeyWithDefault #(2, 2, 2) gpr_read1_select_mux (
        .out(gpr_read1_select),
        .key(gpr_read1_select_key),
        .default_out(rs1_select_result),
        .lut({
            2'b10, rs_select_result,
            2'b01, 2'b00
        })
    );
    //因为这里的out_latched是一个寄存器，只有执行out指令的时候is_out_result才是1，才会把数值更新成gpr_read_data1
    //所以这样设计可以做到在out指令的时候输出保持不变
    assign scpuResult = out_latched;
    gpr4_8bit gpr_inst(
        .clk(clk),
        .rst(rst),
        .wdata(gpr_wdata),
        .WriteSELECT(rd_select_result),
        .Read1SELECT(gpr_read1_select),
        .Read2SELECT(rs2_select_result),
        .WriteEN(reg_we_result),
        .ReadDATA1(gpr_read_data1),
        .ReadDATA2(gpr_read_data2)
    );
    always @(posedge clk) begin
        if (rst) begin
            out_latched <= 8'b0;
        end else if (is_out_result) begin
            out_latched <= gpr_read_data1;
        end
    end
    always@(*)begin
        gpr_wdata=8'b0;//默认为0，防止出现锁存器
        if (is_add_result) begin
            gpr_wdata=gpr_read_data1+gpr_read_data2;
        end else if (is_li_result) begin
            gpr_wdata={4'b0,imm_result};
        end else begin
            gpr_wdata=8'b0;
        end
    end
endmodule
