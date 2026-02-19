`include "VerilogHead.vh"
module scpu(
    input clk,//时钟
    input rst_n,//复位
    input WE,
    input[7:0] DATAIn,
    output[7:0] scpuResult
);
    wire rst;
    wire [3:0] pc_result;
    wire [3:0] rom_addr;
    wire [7:0] rom_data;
    wire is_add_result;
    wire is_out_result;
    wire is_li_result;
    wire is_bner0_result;
    wire [1:0] rs1_select_result;
    wire [1:0] rs2_select_result;
    wire [1:0] rd_select_result;
    wire [1:0] rs_select_result;
    wire [3:0] imm_result;
    wire [3:0] jump_addr_result;
    wire reg_we_result;
    wire pc_we_result;
    reg [7:0] gpr_wdata;
    wire [1:0] gpr_read1_select;
    wire [7:0] gpr_read_data1;
    wire [7:0] gpr_read_data2;
    wire [1:0] gpr_read1_select_key;
    reg [7:0] out_latched;
    reg [3:0] pc_next;//当前周期组合逻辑计算出的“下一条指令地址”
    wire pc_wen;//PC写使能
    wire branch_taken;//bner0条件是否成立，成立则跳转到jump_addr_result
    assign rst = ~rst_n;
    assign pc_wen = 1'b1;
    assign rom_addr = pc_result;
    //这个注释是GPT5.3codex写的
    // bner0 语义: if (R0 != R[rs2]) PC = addr
    // 这里约定:
    //   - gpr_read_data1 在 bner0 时强制读 R0
    //   - gpr_read_data2 由 rs2_select_result 读出 R[rs2]
    // 因此二者不等即分支成立
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
    //GPT5.3codex写的注释
    // 读端口1的选择策略:
    // 1) out 指令: read1 读取 rs_select 指向的寄存器，用于数码管显示
    // 2) bner0 指令: read1 固定读取 R0，用来和 read2 的 R[rs2] 做比较
    // 3) 其它指令: read1 按 rs1_select_result 正常读取
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
