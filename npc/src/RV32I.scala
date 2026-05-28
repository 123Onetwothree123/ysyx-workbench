package RV32I
import chisel3._
import chisel3.util._
import RV32I.opcode._

class RV32I extends Module {
  val io = IO(new RV32IIO)
//先直接实例化模块了（反正获得对象自由了，不像Verilog那么死）
  val pc = Module(new PC)
  val ifu = Module(new IFU)
  val idu = Module(new IDU)
  val gpr = Module(new GPR)
  val exu = Module(new EXU)
  val branch = Module(new BranchComparator)
  val csr = Module(new CSR)
  val lsu = Module(new LSU)
  val wbu = Module(new WBU)
  val NextPc = Module(new NextPC)
//连线
  ifu.io.PC := pc.io.PC
  ifu.io.InstructionReadDATA := io.InstructionReadDATA
  io.InstructionAddress := ifu.io.InstructionAddress

  val Instruction = ifu.io.InstructionOutput
  val Opcode = Instruction(6, 0)
  val Funct3 = Instruction(14, 12)
  val IsJal = Opcode === OPCODE_Jump
  val IsJalr = (Opcode === OPCODE_Immediate_Bxxx) && (Funct3 === "b000".U(3.W))

  idu.io.Instruction := Instruction

  gpr.io.Read1SELECT := idu.io.rs1
  gpr.io.Read2SELECT := idu.io.rs2
  gpr.io.WriteSELECT := idu.io.rd
  gpr.io.WriteEN := wbu.io.RegisterFileWriteEN
  gpr.io.wdata := wbu.io.RegisterFileWriteDATA

  val Rs1Data = gpr.io.ReadDATA1
  val Rs2Data = gpr.io.ReadDATA2
  val PCValue = pc.io.PC
  val Immediate = idu.io.Immediate

  val AluA = WireDefault(Rs1Data)
  switch(Opcode) {
    is(OPCODE_UpperImmediate_lui) {
      AluA := 0.U(32.W)
    }
    is(OPCODE_UpperImmediate_auipc, OPCODE_Jump) {
      AluA := PCValue
    }
  }
  val AluB = Mux(Opcode === OPCODE_Register, Rs2Data, Immediate)

  exu.io.ALUCtrl := idu.io.ALUCtrl
  exu.io.SourceDATA_A := AluA
  exu.io.SourceDATA_B := AluB

  branch.io.A := Rs1Data
  branch.io.B := Rs2Data
  branch.io.Funct3 := Funct3
  branch.io.IsBranch := Opcode === OPCODE_Branch

  csr.io.clk := clock
  csr.io.rst := reset.asBool
  csr.io.IsCsrrw := idu.io.IsCsrrw
  csr.io.IsCsrrs := idu.io.IsCsrrs
  csr.io.IsEcall := idu.io.IsEcall
  csr.io.IsEbreak := idu.io.IsEbreak
  csr.io.IsMret := idu.io.IsMret
  csr.io.CSRAddress := idu.io.CSRAddress
  csr.io.rs1 := idu.io.rs1
  csr.io.Rs1Data := Rs1Data
  csr.io.pc := PCValue

  lsu.io.MemoryValid := idu.io.MemoryValid
  lsu.io.MemoryWrite := idu.io.MemoryWrite
  lsu.io.WidthSel := idu.io.WidthSel
  lsu.io.ALUResult := exu.io.ALUResult
  lsu.io.MemoryReadDATA := io.MemoryReadDATA
  lsu.io.StoreDATA := Rs2Data
  lsu.io.LoadSigned := idu.io.LoadSigned

  io.MemWE := lsu.io.MemoryWE
  io.MemAddr := lsu.io.MemoryAddr
  io.MemWriteDATA := lsu.io.MemoryWriteDATA
  io.MemWriteMask := lsu.io.MemoryWriteMask

  wbu.io.RegWrite := idu.io.RegWrite
  wbu.io.WBSel := idu.io.WBSel
  wbu.io.ALUResult := exu.io.ALUResult
  wbu.io.LoadDATA := lsu.io.LoadDATA
  wbu.io.SNPC := ifu.io.SNPC
  wbu.io.CSR_rdata := csr.io.CSR_rdata

  val BranchTarget = PCValue + Immediate
  val JalTarget = exu.io.ALUResult
  val JalrTarget = Cat(exu.io.ALUResult(31, 1), 0.U(1.W))
  val Redirect = IsJal || IsJalr || branch.io.Taken
  val RedirectTarget =
    Mux(IsJalr, JalrTarget, Mux(IsJal, JalTarget, BranchTarget))

  NextPc.io.SNPC := ifu.io.SNPC
  NextPc.io.Redirect := Redirect
  NextPc.io.RedirectTarget := RedirectTarget
  NextPc.io.ExceptionTaken := csr.io.ExceptionTaken
  NextPc.io.ExceptionTarget := csr.io.ExceptionTarget

  pc.io.NextPC := NextPc.io.NextPC
  pc.io.PCEnable := NextPc.io.PCEnable
}
