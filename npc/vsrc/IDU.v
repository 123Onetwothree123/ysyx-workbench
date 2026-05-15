//IDU(InstructionDecodeUnit):负责对当前指令进行译码,准备执行阶段需要使用的数据和控制信号
`include"opcode.vh"
module IDU(
	input[31:0]Instruction,
	output RegWrite,
	output MemValid,
	output MemWrite,
	output[1:0]WidthSel,//00字节，01半字，10字
	output LoadSigned,
	output[3:0]ALUCtrl,
	output Illegal,
	output[31:0]Immediate,
	//00：ALUResult，01：LoadDATA，10：SNPC，目前的一个可能性的设想就是让WBSel决定最终协会寄存器的数据来自哪里
	output reg[1:0]WBSel,
	output[4:0]rs1,
	output[4:0]rs2,
	output[4:0]rd,
	output IsEbreak_gtest,//ebreak指令检测
	output IsCsrrw,
	output IsCsrrs,
	output IsEcall,
	output IsMret,
	output[11:0]CSRAddress
);
	assign rs1=Instruction[19:15];
	assign rs2=Instruction[24:20];
	assign rd=Instruction[11:7];
	localparam[1:0]WB_ALU=2'b00;
	localparam[1:0]WB_MEM=2'b01;
	localparam[1:0]WB_SNPC=2'b10;
	localparam[1:0]WB_CSR=2'b11;
	wire[6:0]opcode=Instruction[6:0];
	wire[2:0]funct3=Instruction[14:12];
	wire[6:0]funct7=Instruction[31:25];
	wire is_R_type=(opcode==`OPCODE_Register);
	wire is_I_type=(opcode==`OPCODE_Immediate)||(opcode==`OPCODE_Immediate_Lxxx)||(opcode==`OPCODE_Immediate_Bxxx);
	wire is_S_type=(opcode==`OPCODE_Store);
	wire is_B_type=(opcode==`OPCODE_Branch);
	wire is_U_type=(opcode==`OPCODE_UpperImmediate_lui)||(opcode==`OPCODE_UpperImmediate_auipc);
	wire is_J_type=(opcode==`OPCODE_Jump);
	wire is_Load=(opcode==`OPCODE_Immediate_Lxxx);
	wire IsSystem=(opcode==`OPCODE_System);
	//CSR具体指令
	assign IsCsrrw=IsSystem&&(funct3==3'b001);
	assign IsCsrrs=IsSystem&&(funct3==3'b010);
	assign IsEcall=(Instruction==32'h00000073);
	assign IsMret=(Instruction==32'h30200073);
	assign CSRAddress=Instruction[31:20];
	//ebreak指令检测:ebreak=0x00100073
	assign IsEbreak_gtest=(Instruction==32'h00100073);
	assign RegWrite=is_R_type|is_I_type|is_U_type|is_J_type|IsCsrrw|IsCsrrs;
	assign MemValid=is_Load|is_S_type;
	assign MemWrite=is_S_type;
	assign WidthSel=(is_Load||is_S_type)?funct3[1:0]:2'b10;
	assign LoadSigned=is_Load?~funct3[2]:1'b0;
	always@(*)begin
	if(IsCsrrw||IsCsrrs)
		WBSel=WB_CSR;//CSR指令写回CSR读出值
	else if(is_Load)
		WBSel=WB_MEM;//load就直接协会访存结果
	else if((opcode==`OPCODE_Immediate_Bxxx)&&(funct3==3'b000))//jalr
		WBSel=WB_SNPC;
	else if(is_J_type)//jal
		WBSel=WB_SNPC;
	else//正常写回的普通指令
		WBSel=WB_ALU;
	end
	ImmediateGenerator ImmGen(
		.Instruction(Instruction),
		.Immediate(Immediate)
	);
	wire[1:0]ALUOp;
	ALUOpDecoder Decoder1(
		.opcode(opcode),
		.ALUOp(ALUOp)
	);
	ALUControlDecoder Decoder2(
		.ALUOp(ALUOp),
		.opcode(opcode),
		.funct3(funct3),
		.funct7(funct7),
		.ALUCtrl(ALUCtrl),
		.Illegal(Illegal)
	);
endmodule
