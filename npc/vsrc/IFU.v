//IFU(Instruction Fetch Unit): 负责根据当前PC从指令存储器中取出一条指令
module IFU (
  input  [31:0] PC,
  /*
  input  [31:0] ROMOutput,
  output [31:0] ROMInput,
  */
  input [31:0] InstructionReadDATA,//rom输出的数据
  output [31:0] InstructionAddress,//rom地址
  output [31:0] InstructionOutput,
  output [31:0] SNPC
);
  assign InstructionAddress = PC;
  assign InstructionOutput = InstructionReadDATA;
  assign SNPC = PC + 32'd4;
endmodule
