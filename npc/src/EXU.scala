package RV32I
import chisel3._
import chisel3.util._

class EXU extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new IDUMessage))
    val out = Decoupled(new EXUMessage)
    val MemoryReadDATA = Input(UInt(32.W))
    val MemWE = Output(Bool())
    val MemAddr = Output(UInt(32.W))
    val MemWriteDATA = Output(UInt(32.W))
    val MemWriteMask = Output(UInt(4.W))
    val Redirect = Output(Bool())
    val RedirectTarget = Output(UInt(32.W))
    val ExceptionTaken = Output(Bool())
    val ExceptionTarget = Output(UInt(32.W))
  })
  io.in.ready := io.out.ready
  io.out.valid := io.in.valid

  val Commit = io.in.fire

  val ALUUnit = Module(new ALU)
  ALUUnit.io.A := io.in.bits.ALU_A
  ALUUnit.io.B := io.in.bits.AluB
  ALUUnit.io.ALUCtrl := io.in.bits.ALUCtrl

  val BranchUnit = Module(new BranchComparator)
  BranchUnit.io.A := io.in.bits.BranchA
  BranchUnit.io.B := io.in.bits.BranchB
  BranchUnit.io.Funct3 := io.in.bits.BranchFunct3
  BranchUnit.io.IsBranch := io.in.bits.IsBranch

  val LSUUnit = Module(new LSU)
  LSUUnit.io.MemoryValid := io.in.bits.MemValid
  LSUUnit.io.MemoryWrite := io.in.bits.MemWrite && Commit
  LSUUnit.io.WidthSel := io.in.bits.WidthSel
  LSUUnit.io.ALUResult := ALUUnit.io.result
  LSUUnit.io.MemoryReadDATA := io.MemoryReadDATA
  LSUUnit.io.StoreDATA := io.in.bits.StoreData
  LSUUnit.io.LoadSigned := io.in.bits.LoadSigned

  io.MemWE := LSUUnit.io.MemoryWE
  io.MemAddr := LSUUnit.io.MemoryAddr
  io.MemWriteDATA := LSUUnit.io.MemoryWriteDATA
  io.MemWriteMask := LSUUnit.io.MemoryWriteMask

  val CSRUnit = Module(new CSR)
  CSRUnit.io.clk := clock
  CSRUnit.io.rst := reset.asBool
  CSRUnit.io.Enable := Commit
  CSRUnit.io.IsCsrrw := io.in.bits.IsCsrrw
  CSRUnit.io.IsCsrrs := io.in.bits.IsCsrrs
  CSRUnit.io.IsEcall := io.in.bits.IsEcall
  CSRUnit.io.IsEbreak := io.in.bits.IsEbreak
  CSRUnit.io.IsMret := io.in.bits.IsMret
  CSRUnit.io.CSRAddress := io.in.bits.CSRAddress
  CSRUnit.io.rs1 := io.in.bits.Rs1
  CSRUnit.io.Rs1Data := io.in.bits.Rs1Data
  CSRUnit.io.pc := io.in.bits.pc

  val BranchTarget = io.in.bits.pc + io.in.bits.Immediate
  val JalTarget = ALUUnit.io.result
  val JalrTarget = Cat(ALUUnit.io.result(31, 1), 0.U(1.W))
  val Redirect = io.in.bits.IsJal || io.in.bits.IsJalr || BranchUnit.io.Taken

  io.Redirect := Commit && Redirect
  io.RedirectTarget := Mux(io.in.bits.IsJalr, JalrTarget, Mux(io.in.bits.IsJal, JalTarget, BranchTarget))
  io.ExceptionTaken := CSRUnit.io.ExceptionTaken
  io.ExceptionTarget := CSRUnit.io.ExceptionTarget

  io.out.bits.Rd := io.in.bits.Rd
  io.out.bits.RegWrite := io.in.bits.RegWrite
  io.out.bits.WBSel := io.in.bits.WBSel
  io.out.bits.ALUResult := ALUUnit.io.result
  io.out.bits.LoadData := LSUUnit.io.LoadDATA
  io.out.bits.snpc := io.in.bits.snpc
  io.out.bits.CSRReadData := CSRUnit.io.CSR_rdata
}
