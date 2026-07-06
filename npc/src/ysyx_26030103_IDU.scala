package ysyx_26030103
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.ysyx_26030103_General.ysyx_26030103_opcode._
import _root_.ysyx_26030103.ysyx_26030103_Message.ysyx_26030103_IFUMessage
import _root_.ysyx_26030103.ysyx_26030103_Message.ysyx_26030103_IDUMessage
import _root_.ysyx_26030103.ysyx_26030103_ALU._
class ysyx_26030103_IDU extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_IFUMessage))
    val out = Decoupled(new ysyx_26030103_IDUMessage)
    val ReadDATA1 = Input(UInt(32.W))
    val ReadDATA2 = Input(UInt(32.W))
    val Read1SELECT = Output(UInt(5.W))
    val Read2SELECT = Output(UInt(5.W))
  })
  // 唉直接照着抄，直接连了
  io.in.ready := io.out.ready
  io.out.valid := io.in.valid
  val Instruction = io.in.bits.Instruction
  val pc = io.in.bits.pc
  val snpc = pc + 4.U(32.W)
  val Rs1 = Instruction(19, 15)
  val Rs2 = Instruction(24, 20)
  io.Read1SELECT := Rs1
  io.Read2SELECT := Rs2
  val Rd = Instruction(11, 7)
  val opcode = Instruction(6, 0)
  val funct3 = Instruction(14, 12)
  val funct7 = Instruction(31, 25)
  val IsRType = (opcode === OPCODE_Register)
  val IsIType =
    (opcode === OPCODE_Immediate) || (opcode === OPCODE_Immediate_Lxxx) || (opcode === OPCODE_Immediate_Bxxx)
  val IsSType = (opcode === OPCODE_Store)
  val IsBType = (opcode === OPCODE_Branch)
  val IsUType =
    (opcode === OPCODE_UpperImmediate_lui) || (opcode === OPCODE_UpperImmediate_auipc)
  val IsJType = (opcode === OPCODE_Jump)
  val IsLoad = (opcode === OPCODE_Immediate_Lxxx)
  val IsSystem = (opcode === OPCODE_System)
  // 两个中间变量，他妈了个逼的，Verilog的wire既能读还能写，chisel3的输出端口还他妈的只能写不能读，还得搞中间变量
  val IsCsrrw = IsSystem && (funct3 === "b001".U(3.W))
  val IsCsrrs = IsSystem && (funct3 === "b010".U(3.W))
  val IsEbreak = Instruction === "h00100073".U(32.W)
  val IsEcall = Instruction === "h00000073".U(32.W)
  val IsMret = Instruction === "h30200073".U(32.W)
  val RegisterWrite =
    IsRType || IsIType || IsUType || IsJType || IsCsrrs || IsCsrrw
  val MemoryValid = IsLoad || IsSType
  val MemoryWrite = IsSType
  /*
  又忘记了为什么这样做，就是想要减少代码量，主要是代码量多是负担，就是只看末尾的两位，我怀疑可能是RISCV故意这么设计的，也可以简化
  000 LB和SB 1byte
  001 LH和SH 2byte
  010 LW和SW 4byte
  U是没有符号的意思
  100 LBU 1byte（没有符号）
  101 LHU 2byte（没有符号）
   */
  val WidthSelect = Mux(IsLoad || IsSType, funct3(1, 0), 2.U(2.W))
  val LoadSigned = Mux(IsLoad, (~funct3(2)).asBool, false.B)
  // 控制ysyx_26030103_WBU的，不知道该不该让ysyx_26030103_IDU来控制ysyx_26030103_WBU，本来想手搓一个控制模块的，结果模块多了，还分不清
  val WB_ALU = 0.U(2.W)
  val WB_MEMORY = 1.U(2.W)
  val WB_SNPC = 2.U(2.W)
  val WB_CSR = 3.U(2.W)
  val WBSelect = Wire(UInt(2.W))
  when(IsCsrrw || IsCsrrs) {
    WBSelect := WB_CSR // ysyx_26030103_CSR指令写回ysyx_26030103_CSR读出值
  }.elsewhen(IsLoad) {
    WBSelect := WB_MEMORY // load就直接写回访存结果
  }.elsewhen(
    (opcode === OPCODE_Immediate_Bxxx) && (funct3 === "b000".U(3.W))
  ) { // jalr
    WBSelect := WB_SNPC
  }.elsewhen(IsJType) { // jal
    WBSelect := WB_SNPC
  }.otherwise { // 正常写回的普通指令
    WBSelect := WB_ALU
  }
  val ImmediateGeneratorModule = Module(new ysyx_26030103_ImmediateGenerator)
  ImmediateGeneratorModule.io.Instruction := Instruction
  val Immediate = ImmediateGeneratorModule.io.Immediate
  // ysyx_26030103_ALU直接模块实例化了
  val ALUOpDecoderModule = Module(new ysyx_26030103_ALUOpDecoder)
  ALUOpDecoderModule.io.opcode := opcode
  val ALUOp = ALUOpDecoderModule.io.ALUOp
  val ALUControlDecoderModule = Module(new ysyx_26030103_ALUControlDecoder)
  ALUControlDecoderModule.io.ALUOp := ALUOp
  ALUControlDecoderModule.io.opcode := opcode
  ALUControlDecoderModule.io.funct3 := funct3
  ALUControlDecoderModule.io.funct7 := funct7
  val ALUCtrl = ALUControlDecoderModule.io.ALUCtrl
  val ALUCDIllegal = ALUControlDecoderModule.io.Illegal
  val ALU_A = WireDefault(io.ReadDATA1) // 默认所有指令的第一个计算的数是寄存器值
  switch(opcode) {
    is(OPCODE_UpperImmediate_lui) {
      ALU_A := 0.U(32.W)
    }
    is(OPCODE_UpperImmediate_auipc, OPCODE_Jump) {
      ALU_A := pc - 4.U
    }
  }
  val ALU_B = WireDefault(Immediate)
  when(opcode === OPCODE_Register) {
    ALU_B := io.ReadDATA2
  }
  io.out.bits.pc := pc
  io.out.bits.snpc := snpc
  io.out.bits.ALUCtrl := ALUCtrl
  io.out.bits.ALU_A := ALU_A
  io.out.bits.ALU_B := ALU_B
  io.out.bits.BranchA := io.ReadDATA1
  io.out.bits.BranchB := io.ReadDATA2
  io.out.bits.BranchFunct3 := funct3
  io.out.bits.IsBranch := IsBType
  io.out.bits.IsJal := IsJType
  io.out.bits.IsJalr := (opcode === OPCODE_Immediate_Bxxx) && (funct3 === "b000"
    .U(3.W))
  io.out.bits.Immediate := Immediate
  io.out.bits.Rd := Rd
  io.out.bits.RegisterWrite := RegisterWrite
  io.out.bits.WBSelect := WBSelect
  io.out.bits.MemoryValid := MemoryValid
  io.out.bits.MemoryWrite := MemoryWrite
  io.out.bits.WidthSelect := WidthSelect
  io.out.bits.LoadSigned := LoadSigned
  // 写就直接上rs2第二个寄存器
  io.out.bits.StoreData := io.ReadDATA2
  io.out.bits.IsCsrrw := IsCsrrw
  io.out.bits.IsCsrrs := IsCsrrs
  io.out.bits.IsEcall := IsEcall
  io.out.bits.IsEbreak := IsEbreak
  io.out.bits.IsMret := IsMret
  io.out.bits.CSRAddress := Instruction(31, 20)
  io.out.bits.Rs1 := Rs1
  io.out.bits.Rs1Data := io.ReadDATA1
  io.out.bits.ALUCDIllegal := ALUCDIllegal
}
