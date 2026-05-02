`include "opcode.vh"
module RV32E32Reg (
    input clk,
    input rst,
    input        sdb_debug_clk,
    input        sdb_pc_write_en,
    input [31:0] sdb_pc_write_data,
    input        sdb_gpr_write_en,
    input [4:0]  sdb_gpr_write_addr,
    input [31:0] sdb_gpr_write_data
);
wire [31:0] pc_current;
wire [31:0] pc_next;
wire        pc_enable;
wire [31:0] inst_addr;
wire [31:0] inst_data;
wire [31:0] instruction;
wire [31:0] snpc;
wire        reg_write;
wire        mem_valid;
wire        mem_write;
wire [1:0]  width_sel;
wire        load_signed;
wire [3:0]  alu_ctrl;
wire        illegal;
wire [31:0] immediate;
wire [1:0]  wb_sel;
wire [4:0]  rs1;
wire [4:0]  rs2;
wire [4:0]  rd;
wire [31:0] rs1_data;
wire [31:0] rs2_data;
wire [31:0] ebreak_code_gtest;
wire [31:0] source_data_a;
wire [31:0] source_data_b;
wire [31:0] alu_result;
wire        mem_we;
wire [31:0] mem_addr;
wire [31:0] mem_write_data;
wire [3:0]  mem_write_mask;
wire [31:0] mem_read_data;
wire [31:0] load_data;
wire        addr_misaligned;
wire        rf_write_en;
wire [31:0] rf_write_data;
wire        is_jump;
wire        is_jalr;
wire        is_ebreak_gtest;
wire [31:0] jump_target;
wire [6:0]  opcode = instruction[6:0];
wire [2:0]  funct3 = instruction[14:12];

assign source_data_a = ((opcode == `OPCODE_UpperImmediate_auipc) || (opcode == `OPCODE_Jump)) ? pc_current : rs1_data;
assign source_data_b = (opcode == `OPCODE_Register) ? rs2_data : immediate;
assign is_jalr = (opcode == `OPCODE_Immediate_Bxxx) && (funct3 == 3'b000);
assign is_jump = (opcode == `OPCODE_Jump) || is_jalr;
assign jump_target = is_jalr ? {alu_result[31:1], 1'b0} : alu_result;
//sdb
wire [4:0]  sdb_debug_raddr;
wire [31:0] sdb_debug_rdata;

PC CPU_PC(
    .clk(clk),
    .rst(rst),
    .NextPC(pc_next),
    .PCEnable(pc_enable),
    .DebugClk(sdb_debug_clk),
    .DebugWriteEN(sdb_pc_write_en),
    .DebugNextPC(sdb_pc_write_data),
    .PC(pc_current)
);

IFU CPU_IFU(
    .PC(pc_current),
    .InstructionReadDATA(inst_data),
    .InstructionAddress(inst_addr),
    .InstructionOutput(instruction),
    .SNPC(snpc)
);

IDU CPU_IDU(
    .Instruction(instruction),
    .RegWrite(reg_write),
    .MemValid(mem_valid),
    .MemWrite(mem_write),
    .WidthSel(width_sel),
    .LoadSigned(load_signed),
    .ALUCtrl(alu_ctrl),
    .Illegal(illegal),
    .Immediate(immediate),
    .WBSel(wb_sel),
    .rs1(rs1),
    .rs2(rs2),
    .rd(rd),
    .IsEbreak_gtest(is_ebreak_gtest)
);

EXU CPU_EXU(
    .ALUCtrl(alu_ctrl),
    .SourceDATA_A(source_data_a),
    .SourceDATA_B(source_data_b),
    .ALUResult(alu_result)
);

LSU CPU_LSU(
    .MemValid(mem_valid),
    .MemWrite(mem_write),
    .WidthSel(width_sel),
    .ALUResult(alu_result),
    .MemoryReadDATA(mem_read_data),
    .StoreDATA(rs2_data),
    .LoadSigned(load_signed),
    .MemWE(mem_we),
    .MemAddr(mem_addr),
    .MemWriteDATA(mem_write_data),
    .MemWriteMask(mem_write_mask),
    .LoadDATA(load_data),
    .AddrMisaligned(addr_misaligned)
);

WBU CPU_WBU(
    .RegWrite(reg_write),
    .WBSel(wb_sel),
    .ALUResult(alu_result),
    .LoadDATA(load_data),
    .SNPC(snpc),
    .RegisterFileWriteEN(rf_write_en),
    .RegisterFileWriteDATA(rf_write_data)
);

NPC CPU_NPC(
    .SNPC(snpc),
    .ALUResult(jump_target),
    .IsJump(is_jump),
    .NextPC(pc_next),
    .PCEnable(pc_enable)
);

GPR CPU_GPR(
    .clk(clk),
    .wdata(rf_write_data),
    .WriteSELECT(rd),
    .WriteEN(rf_write_en),
    .Read1SELECT(rs1),
    .Read2SELECT(rs2),
    .ReadDATA1(rs1_data),
    .ReadDATA2(rs2_data),
    .EbreakCode_gtest(ebreak_code_gtest),
    //sdb
    .DebugRaddr(sdb_debug_raddr),
    .DebugRdata(sdb_debug_rdata),
    .DebugClk(sdb_debug_clk),
    .DebugWaddr(sdb_gpr_write_addr),
    .DebugWdata(sdb_gpr_write_data),
    .DebugWriteEN(sdb_gpr_write_en)
);

ROM_DPI_C CPU_ROM(
    .Address(inst_addr),
    .ReadDATA(inst_data)
);

Memory_DPI_C CPU_Memory(
    .clk(clk),
    .valid(mem_valid),
    .wen(mem_we),
    .raddr(mem_addr),
    .waddr(mem_addr),
    .wdata(mem_write_data),
    .wmask(mem_write_mask),
    .rdata(mem_read_data)
);

EBREAK_DPI_C CPU_EBREAK(
    .clk(clk),
    .valid(is_ebreak_gtest),
    .pc(pc_current),
    .code(ebreak_code_gtest)
);
SDB_DPI_C SDB(
    .DebugRdata(sdb_debug_rdata),
    .DebugRaddr(sdb_debug_raddr)
);
PC_DPI_C PC_DPI(
    .pc_current(pc_current)
);
ITRACE_DPI_C ITRACE_DPI(
    .clk(clk),
    .rst(rst),
    .pc(pc_current),
    .inst(instruction),
    .snpc(snpc),
    .valid(pc_enable)
);
MTRACE_DPI_C MTRACE_DPI(
    .clk(clk),
    .rst(rst),
    .wen(mem_we),
    .AccessMemory(mem_valid),
    .PC(pc_current),
    .Address(mem_addr),
    .WriteData(mem_write_data),
    .ReadData(mem_read_data),
    .WriteMask(mem_write_mask)
);
FTRACE_DPI_C FTRACE_DPI(
    .clk(clk),
    .rst(rst),
    .pc(pc_current),
    .inst(instruction),
    .next_pc(pc_next),
    .valid(pc_enable)
);
endmodule
