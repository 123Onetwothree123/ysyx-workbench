package RV32I
import chisel3._
import chisel3.util._
class CSR extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val Enable = Input(Bool())
    // IDU给出的指令译码信号
    val IsCsrrw = Input(Bool())
    val IsCsrrs = Input(Bool())
    val IsEcall = Input(Bool())
    // Chisel版本设计的真正ebreak，不是之前Verilog里依赖DPI-C结束仿真的ebreak
    val IsEbreak = Input(Bool())
    val IsMret = Input(Bool())
    val CSRAddress = Input(UInt(12.W)) // CSR地址，指令[31:20]
    val rs1 = Input(UInt(5.W)) // rs1寄存器编号，用于判断csrrs是否写CSR
    val Rs1Data = Input(UInt(32.W)) // Rs1寄存器的数据
    val pc = Input(UInt(32.W)) // 现在的PC，就是ecall时保存到mepc
    val CSR_rdata = Output(UInt(32.W)) // CSR读出，写到rd
    val CSRValid = Output(Bool()) // 如果当前是CSR指令，那就需要写回rd
    // 异常和返回的跳转控制
    val ExceptionTaken = Output(Bool()) // 需要跳转，ecall到mtvec，mret到mepc
    val ExceptionTarget = Output(UInt(32.W)) // 跳转目标地址
  })
  // mcycle=12'hB00，低32位
  // mcycleh=12'hB80，高32位
  val CSR_MVENDORID = "hF11".U(12.W)
  val CSR_MARCHID = "hF12".U(12.W)
  val CSR_MCYCLE = "hB00".U(12.W)
  val CSR_MCYCLEH = "hB80".U(12.W)
  val CSR_MSTATUS = "h300".U(12.W)
  val CSR_MTVEC = "h305".U(12.W)
  val CSR_MEPC = "h341".U(12.W)
  val CSR_MCAUSE = "h342".U(12.W)
  val McycleSelect = (io.CSRAddress === CSR_MCYCLE)
  val McyclehSelect = (io.CSRAddress === CSR_MCYCLEH)
  val MstatusSelect = (io.CSRAddress === CSR_MSTATUS)
  val MtvecSelect = (io.CSRAddress === CSR_MTVEC)
  val MepcSelect = (io.CSRAddress === CSR_MEPC)
  val McauseSelect = (io.CSRAddress === CSR_MCAUSE)
  val MvendoridSelect = (io.CSRAddress === CSR_MVENDORID)
  val MarchidSelect = (io.CSRAddress === CSR_MARCHID)
  val McycleAccess = McycleSelect | McyclehSelect // 看目前mcycle是否是正在访问
  val Mvendorid_rdata = "h79737978".U(32.W)
  val Marchid_rdata = "h018d3017".U(32.W)
  val CsrrwWriteEnable = io.Enable && io.IsCsrrw
  val CsrrsWriteEnable = io.Enable && io.IsCsrrs && (io.rs1 =/= 0.U(5.W))
  // 真正的ebreak按RISC-V breakpoint异常处理：写mepc/mcause，然后跳mtvec
  val IsException = io.IsEcall || io.IsEbreak
  val ExceptionCommit = io.Enable && IsException
  val MretCommit = io.Enable && io.IsMret
  val ExceptionCause = Mux(io.IsEbreak, 3.U(32.W), 11.U(32.W))
  val Mstatus_rdata = Wire(UInt(32.W))
  // ecall/ebreak异常进入时，MPP写11，MPIE写旧MIE，MIE写0
  // MPP，00是U，01是S，11是M
  val MstatusEcallData = Cat(
    Mstatus_rdata(31, 13),
    "b11".U(2.W),
    Mstatus_rdata(10, 8),
    Mstatus_rdata(3),
    Mstatus_rdata(6, 4),
    0.U(1.W),
    Mstatus_rdata(2, 0)
  )
  val MstatusWenFromCsrrw = CsrrwWriteEnable && MstatusSelect
  val MstatusWenFromCsrrs = CsrrsWriteEnable && MstatusSelect
  val MstatusCSRWen = MstatusWenFromCsrrw || MstatusWenFromCsrrs
  val MstatusCSRData = Mux(io.IsCsrrw, io.Rs1Data, Mstatus_rdata | io.Rs1Data)
  val MstatusMretData = Wire(UInt(32.W))
  MstatusMretData := Cat(
    Mstatus_rdata(31, 13),
    "b00".U(2.W),
    Mstatus_rdata(10, 8),
    1.U(1.W),
    Mstatus_rdata(6, 4),
    Mstatus_rdata(7),
    Mstatus_rdata(2, 0)
  )
  val MstatusWen = MstatusCSRWen || ExceptionCommit || MretCommit
  val Mstatus_wdata =
    Mux(ExceptionCommit, MstatusEcallData, Mux(MretCommit, MstatusMretData, MstatusCSRData))
  val Mstatus = Module(new mstatus)
  Mstatus.io.clk := io.clk
  Mstatus.io.rst := io.rst
  Mstatus.io.wen := MstatusWen
  Mstatus.io.wdata := Mstatus_wdata
  Mstatus_rdata := Mstatus.io.rdata
  val Mtvec_rdata = Wire(UInt(32.W))
  val MtvecWenFromCsrrw = CsrrwWriteEnable && MtvecSelect
  val MtvecWenFromCsrrs = CsrrsWriteEnable && MtvecSelect
  val MtvecWen = MtvecWenFromCsrrw || MtvecWenFromCsrrs
  val Mtvec_wdata = Mux(io.IsCsrrw, io.Rs1Data, Mtvec_rdata | io.Rs1Data)
  val Mtvec = Module(new mtvec)
  Mtvec.io.clk := io.clk
  Mtvec.io.rst := io.rst
  Mtvec.io.wen := MtvecWen
  Mtvec.io.wdata := Mtvec_wdata
  Mtvec_rdata := Mtvec.io.rdata
  val Mepc_rdata = Wire(UInt(32.W))
  val MepcWenFromException = ExceptionCommit
  val MepcWenFromCsrrw = CsrrwWriteEnable && MepcSelect
  val MepcWenFromCsrrs = CsrrsWriteEnable && MepcSelect
  val MepcWen = MepcWenFromException || MepcWenFromCsrrw || MepcWenFromCsrrs
  val Mepc_wdata = Mux(ExceptionCommit, io.pc, Mux(io.IsCsrrw, io.Rs1Data, Mepc_rdata | io.Rs1Data))
  val Mepc = Module(new mepc)
  Mepc.io.clk := io.clk
  Mepc.io.rst := io.rst
  Mepc.io.wen := MepcWen
  Mepc.io.ExceptionWE := ExceptionCommit
  Mepc.io.ExceptionData := io.pc
  Mepc.io.wdata := Mepc_wdata
  Mepc_rdata := Mepc.io.rdata
  val Mcause_rdata = Wire(UInt(32.W))
  val McauseWenFromException = ExceptionCommit
  val McauseWenFromCsrrw = CsrrwWriteEnable && McauseSelect
  val McauseWenFromCsrrs = CsrrsWriteEnable && McauseSelect
  val McauseWen = McauseWenFromException || McauseWenFromCsrrw || McauseWenFromCsrrs
  val Mcause_wdata =
    Mux(ExceptionCommit, ExceptionCause, Mux(io.IsCsrrw, io.Rs1Data, Mcause_rdata | io.Rs1Data))
  val Mcause = Module(new mcause)
  Mcause.io.clk := io.clk
  Mcause.io.rst := io.rst
  Mcause.io.wen := McauseWen
  Mcause.io.wdata := Mcause_wdata
  Mcause_rdata := Mcause.io.rdata
  // csrrw时写，csrrs时rs1非零才写
  val McycleWenFromCsrrw = CsrrwWriteEnable && McycleAccess
  val McycleWenFromCsrrs = CsrrsWriteEnable && McycleAccess
  val McycleWen = McycleWenFromCsrrw || McycleWenFromCsrrs
  // csrrw直接写rs1，csrrs得写旧值|rs1
  val Mcycle_rdata = Wire(UInt(32.W))
  val Mcycle_wdata = Mux(io.IsCsrrw, io.Rs1Data, Mcycle_rdata | io.Rs1Data)
  val Mcycle = Module(new mcycle)
  Mcycle.io.clk := io.clk
  Mcycle.io.rst := io.rst
  Mcycle.io.wen := McycleWen
  Mcycle.io.SelectHigh := McyclehSelect
  Mcycle.io.wdata := Mcycle_wdata
  Mcycle_rdata := Mcycle.io.rdata
  val CSR_rdataReg = WireDefault(0.U(32.W))
  val CSRValidReg = WireDefault(false.B)
  when(io.IsCsrrw || io.IsCsrrs) {
    CSRValidReg := (McycleSelect || McyclehSelect || MvendoridSelect || MarchidSelect ||
      MstatusSelect || MtvecSelect || MepcSelect || McauseSelect)
    switch(io.CSRAddress) {
      is(CSR_MCYCLE) { CSR_rdataReg := Mcycle_rdata }
      is(CSR_MCYCLEH) { CSR_rdataReg := Mcycle_rdata }
      is(CSR_MVENDORID) { CSR_rdataReg := Mvendorid_rdata }
      is(CSR_MARCHID) { CSR_rdataReg := Marchid_rdata }
      is(CSR_MSTATUS) { CSR_rdataReg := Mstatus_rdata }
      is(CSR_MTVEC) { CSR_rdataReg := Mtvec_rdata }
      is(CSR_MEPC) { CSR_rdataReg := Mepc_rdata }
      is(CSR_MCAUSE) { CSR_rdataReg := Mcause_rdata }
    }
  }
  io.CSR_rdata := CSR_rdataReg
  io.CSRValid := CSRValidReg
  // ecall和ebreak跳mtvec，mret跳mepc
  io.ExceptionTaken := ExceptionCommit || MretCommit
  io.ExceptionTarget := Mux(io.IsMret, Mepc_rdata, Cat(Mtvec_rdata(31, 2), 0.U(2.W)))
}
