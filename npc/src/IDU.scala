package RV32I
import chisel3._
import chisel3.util._
import RV32I.opcode._
class IDU extends Module {
  val io = IO(new Bundle {
    val Instruction = Input(UInt(32.W))
    val RegWrite = Output(Bool())
    val MemoryValid = Output(Bool())
    val MemoryWrite = Output(Bool())
    val WidthSel = Output(UInt(2.W)) // 00: 字节, 01: 半字, 10: 字
    val LoadSigned = Output(Bool())
    val ALUCtrl = Output(UInt(4.W))
    val Illegal = Output(Bool())
    val Immediate = Output(UInt(32.W))
    val WBSel = Output(UInt(2.W)) // 00: ALUResult, 01: LoadDATA, 10: SNPC
    val rs1 = Output(UInt(5.W))
    val rs2 = Output(UInt(5.W))
    val rd = Output(UInt(5.W))
    val IsEbreak_gtest = Output(Bool())
    val IsCsrrw = Output(Bool())
    val IsCsrrs = Output(Bool())
    val IsEcall = Output(Bool())
    val IsMret = Output(Bool())
    val CSRAddress = Output(UInt(12.W))
  })
  io.rs1 := io.Instruction(19, 15)
  io.rs2 := io.Instruction(24, 20)
  io.rd := io.Instruction(11, 7)
  val opcode = io.Instruction(6, 0)
  val funct3 = io.Instruction(14, 12)
  val funct7 = io.Instruction(31, 25)
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
  io.IsCsrrw := is_Csrrw
  io.IsCsrrs := is_Csrrs
  io.IsEcall := (io.Instruction === "h00000073".U(32.W))
  io.IsMret := (io.Instruction === "h30200073".U(32.W))
  io.CSRAddress := io.Instruction(31, 20)
  io.RegWrite := is_R_type || is_I_type || is_U_type || is_J_type || is_Csrrw || is_Csrrs
  io.MemoryValid := is_Load || is_S_type
  io.MemoryWrite := is_S_type
  io.WidthSel := Mux(is_Load || is_S_type, funct3(1, 0), 2.U(2.W))
  io.LoadSigned := Mux(is_Load, (~funct3(2)).asBool, false.B)
  when(is_Csrrw || is_Csrrs) {
    io.WBSel := WB_CSR // CSR指令写回CSR读出值
  }.elsewhen(is_Load) {
    io.WBSel := WB_MEM // load就直接写回访存结果
  }.elsewhen(
    (opcode === OPCODE_Immediate_Bxxx) && (funct3 === "b000".U(3.W))
  ) { // jalr
    io.WBSel := WB_SNPC
  }.elsewhen(is_J_type) { // jal
    io.WBSel := WB_SNPC
  }.otherwise { // 正常写回的普通指令
    io.WBSel := WB_ALU
  }
}
