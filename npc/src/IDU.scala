package RV32I
import chisel3._
import chisel3.util._
import RV32I.opcode._
class IDU extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new IFUMessage))
    val out = Decoupled(new IDUMessage)
    val Read1SELECT = Output(UInt(5.W))
    val Read2SELECT = Output(UInt(5.W))
    val ReadDATA1 = Input(UInt(32.W))
    val ReadDATA2 = Input(UInt(32.W))
  })
  io.in.ready := io.out.ready
  io.out.valid := io.in.valid

  val Instruction = io.in.bits.Instruction
  val pc = io.in.bits.pc
  val snpc = pc + 4.U(32.W)
  val Rs1 = Instruction(19, 15)
  val Rs2 = Instruction(24, 20)
  val Rd = Instruction(11, 7)
  val opcode = Instruction(6, 0)
  val funct3 = Instruction(14, 12)
  val funct7 = Instruction(31, 25)

  io.Read1SELECT := Rs1
  io.Read2SELECT := Rs2

  val is_R_type = (opcode === OPCODE_Register)
  val is_I_type =
    (opcode === OPCODE_Immediate) || (opcode === OPCODE_Immediate_Lxxx) || (opcode === OPCODE_Immediate_Bxxx)
  val is_S_type = (opcode === OPCODE_Store)
  val is_B_type = (opcode === OPCODE_Branch)
  val is_U_type =
    (opcode === OPCODE_UpperImmediate_lui) || (opcode === OPCODE_UpperImmediate_auipc)
  val is_J_type = (opcode === OPCODE_Jump)
  val is_Load = (opcode === OPCODE_Immediate_Lxxx)
  val IsSystem = (opcode === OPCODE_System)
  val WB_ALU = 0.U(2.W)
  val WB_MEM = 1.U(2.W)
  val WB_SNPC = 2.U(2.W)
  val WB_CSR = 3.U(2.W)
  // 两个中间变量，他妈了个逼的，Verilog的wire既能读还能写，chisel3的输出端口还他妈的只能写不能读，还得搞中间变量
  val is_Csrrw = IsSystem && (funct3 === "b001".U(3.W))
  val is_Csrrs = IsSystem && (funct3 === "b010".U(3.W))
  val IsEbreak = Instruction === "h00100073".U(32.W)
  val IsEcall = Instruction === "h00000073".U(32.W)
  val IsMret = Instruction === "h30200073".U(32.W)
  val RegWrite = is_R_type || is_I_type || is_U_type || is_J_type || is_Csrrw || is_Csrrs
  val MemValid = is_Load || is_S_type
  val MemWrite = is_S_type
  val WidthSel = Mux(is_Load || is_S_type, funct3(1, 0), 2.U(2.W))
  val LoadSigned = Mux(is_Load, (~funct3(2)).asBool, false.B)
  val WBSel = Wire(UInt(2.W))
  when(is_Csrrw || is_Csrrs) {
    WBSel := WB_CSR // CSR指令写回CSR读出值
  }.elsewhen(is_Load) {
    WBSel := WB_MEM // load就直接写回访存结果
  }.elsewhen(
    (opcode === OPCODE_Immediate_Bxxx) && (funct3 === "b000".U(3.W))
  ) { // jalr
    WBSel := WB_SNPC
  }.elsewhen(is_J_type) { // jal
    WBSel := WB_SNPC
  }.otherwise { // 正常写回的普通指令
    WBSel := WB_ALU
  }
  val immGen = Module(new ImmediateGenerator)
  immGen.io.Instruction := Instruction
  val Immediate = immGen.io.Immediate

  val decoder1 = Module(new ALUOpDecoder)
  decoder1.io.opcode := opcode

  val decoder2 = Module(new ALUControlDecoder)
  decoder2.io.ALUOp := decoder1.io.ALUOp
  decoder2.io.opcode := opcode
  decoder2.io.funct3 := funct3
  decoder2.io.funct7 := funct7
  val ALUCtrl = decoder2.io.ALUCtrl

  val ALU_A = WireDefault(io.ReadDATA1)
  switch(opcode) {
    is(OPCODE_UpperImmediate_lui) {
      ALU_A := 0.U(32.W)
    }
    is(OPCODE_UpperImmediate_auipc, OPCODE_Jump) {
      ALU_A := pc
    }
  }
  val AluB = Mux(opcode === OPCODE_Register, io.ReadDATA2, Immediate)

  io.out.bits.pc := pc
  io.out.bits.snpc := snpc
  io.out.bits.ALUCtrl := ALUCtrl
  io.out.bits.ALU_A := ALU_A
  io.out.bits.AluB := AluB
  io.out.bits.BranchA := io.ReadDATA1
  io.out.bits.BranchB := io.ReadDATA2
  io.out.bits.BranchFunct3 := funct3
  io.out.bits.IsBranch := is_B_type
  io.out.bits.IsJal := is_J_type
  io.out.bits.IsJalr := (opcode === OPCODE_Immediate_Bxxx) && (funct3 === "b000".U(3.W))
  io.out.bits.Immediate := Immediate
  io.out.bits.Rd := Rd
  io.out.bits.RegWrite := RegWrite
  io.out.bits.WBSel := WBSel
  io.out.bits.MemValid := MemValid
  io.out.bits.MemWrite := MemWrite
  io.out.bits.WidthSel := WidthSel
  io.out.bits.LoadSigned := LoadSigned
  io.out.bits.StoreData := io.ReadDATA2
  io.out.bits.IsCsrrw := is_Csrrw
  io.out.bits.IsCsrrs := is_Csrrs
  io.out.bits.IsEcall := IsEcall
  io.out.bits.IsEbreak := IsEbreak
  io.out.bits.IsMret := IsMret
  io.out.bits.CSRAddress := Instruction(31, 20)
  io.out.bits.Rs1 := Rs1
  io.out.bits.Rs1Data := io.ReadDATA1
}
