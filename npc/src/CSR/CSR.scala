package RV32I.CSR
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
  val CSR_MVENDORID = 0xf11.U(12.W)
  val CSR_MARCHID = 0xf12.U(12.W)
  val CSR_MCYCLE = 0xb00.U(12.W)
  val CSR_MCYCLEH = 0xb80.U(12.W)
  val CSR_MSTATUS = 0x300.U(12.W)
  val CSR_MTVEC = 0x305.U(12.W)
  val CSR_MEPC = 0x341.U(12.W)
  val CSR_MCAUSE = 0x342.U(12.W)
  val Mvendorid_rdata = 0x79737978.U(32.W)
  val Marchid_rdata = 0x018d3017.U(32.W)
  def IsCSRAddress(addr: UInt): Bool = io.CSRAddress === addr
  // csrrw时写，csrrs时rs1非零才写
  val CSRReadCommand =
    io.IsCsrrw || io.IsCsrrs // 忘记了，后面重看的，以防以后忘记，先标记一下，这是看现在是不是会读取CSR旧数值的CSR指令
  val csrrwWen = io.Enable && io.IsCsrrw
  val csrrsWen = io.Enable && io.IsCsrrs && io.rs1 =/= 0.U // 不能是0号寄存器
  val CSRWen = csrrwWen || csrrsWen
  // csrrw直接写rs1，csrrs写旧值|rs1
  def CSRWriteData(old: UInt): UInt =
    Mux(io.IsCsrrw, io.Rs1Data, old | io.Rs1Data)
  // 真正的ebreak按RISC-V breakpoint异常处理：写mepc/mcause，然后跳mtvec
  val IsException = io.IsEcall || io.IsEbreak
  val ExceptionCommit = io.Enable && IsException
  val MretCommit = io.Enable && io.IsMret
  val ExceptionCause =
    Mux( // 标记一下，English确实是烂，cause是名词（n）翻译是原因，Verilog那边能记住，这里就记不住，服了
      io.IsEbreak,
      3.U(32.W),
      11.U(32.W)
    ) // 如果是ebreak，mcause=3，不然就是ecall，mcause=11
  val Mstatus = Module(new mstatus)
  val Mtvec = Module(new mtvec)
  val Mepc = Module(new mepc)
  val Mcause = Module(new mcause)
  val Mcycle = Module(new mcycle)
  Mstatus.io.clk := io.clk
  Mstatus.io.rst := io.rst
  Mtvec.io.clk := io.clk
  Mtvec.io.rst := io.rst
  Mepc.io.clk := io.clk
  Mepc.io.rst := io.rst
  Mcause.io.clk := io.clk
  Mcause.io.rst := io.rst
  Mcycle.io.clk := io.clk
  Mcycle.io.rst := io.rst
  val Mstatus_rdata = Mstatus.io.rdata
  val Mtvec_rdata = Mtvec.io.rdata
  val Mepc_rdata = Mepc.io.rdata
  val Mcause_rdata = Mcause.io.rdata
  val Mcycle_rdata = Mcycle.io.rdata
  // ecall和ebreak异常进入时，MPP写11，MPIE写旧MIE，MIE写0
  // MPP，00是U，01是S，11是M
  // 他妈的又忘记了，先标记一下，MPP是标记现在的等级的，MPIE是专门保存MIE的，MIE在异常的时候要关闭中断（归零）
  val MstatusExceptionData = Cat(
    Mstatus_rdata(31, 13),
    "b11".U(2.W),
    Mstatus_rdata(10, 8),
    Mstatus_rdata(3),
    Mstatus_rdata(6, 4),
    0.U(1.W),
    Mstatus_rdata(2, 0)
  )
  val MstatusMretData = Cat(
    Mstatus_rdata(31, 13),
    "b00".U(2.W),
    Mstatus_rdata(10, 8),
    1.U(1.W),
    Mstatus_rdata(6, 4),
    Mstatus_rdata(7),
    Mstatus_rdata(2, 0)
  )
  Mstatus.io.wen := (CSRWen && IsCSRAddress(
    CSR_MSTATUS
  )) || ExceptionCommit || MretCommit
  Mstatus.io.wdata := MuxCase(
    CSRWriteData(
      Mstatus_rdata
    ), // 这段重新写了，就直接不用拆分变量了，直接默认CSR指令正常的写入，然后根据csrrw和csrrs的语义来算新的数值
    Seq(
      ExceptionCommit -> MstatusExceptionData,
      MretCommit -> MstatusMretData
    )
  )
  Mtvec.io.wen := CSRWen && IsCSRAddress(CSR_MTVEC)
  Mtvec.io.wdata := CSRWriteData(Mtvec_rdata)
  Mepc.io.wen := (CSRWen && IsCSRAddress(CSR_MEPC)) || ExceptionCommit
  Mepc.io.ExceptionWE := ExceptionCommit
  Mepc.io.ExceptionData := io.pc
  // 正常写入，反正到时候优先级不如异常的优先级高，直接写了，后面再说
  Mcause.io.wen := (CSRWen && IsCSRAddress(CSR_MCAUSE)) || ExceptionCommit
  // 好吧，确实可以正常写的，那这个也要改成mcause的样子
  Mepc.io.wdata := Mux(ExceptionCommit, io.pc, CSRWriteData(Mepc_rdata))
  Mcause.io.wdata := Mux(
    ExceptionCommit,
    ExceptionCause,
    // 先标记一下，这是正常的写入，最开始不知道，以为可以直接写if语句收ExceptionCause，后面查了才知道，软件也可以写csrrw和csrrs
    // 等下，那说明mepc也是可以正常转的吗？
    CSRWriteData(Mcause_rdata)
  )
  val McycleAccess = IsCSRAddress(CSR_MCYCLE) || IsCSRAddress(CSR_MCYCLEH)
  Mcycle.io.wen := CSRWen && McycleAccess
  Mcycle.io.SelectHigh := IsCSRAddress(CSR_MCYCLEH)
  Mcycle.io.wdata := CSRWriteData(Mcycle_rdata)
  // 做一个表，本来直接用switch的，结果知乎链接看到可以用Seq来实现，然后后面别的地方也开始用Seq了
  // https://zhuanlan.zhihu.com/p/567818196
  val CSRMap = Seq(
    CSR_MCYCLE -> Mcycle_rdata,
    CSR_MCYCLEH -> Mcycle_rdata,
    CSR_MVENDORID -> Mvendorid_rdata,
    CSR_MARCHID -> Marchid_rdata,
    CSR_MSTATUS -> Mstatus_rdata,
    CSR_MTVEC -> Mtvec_rdata,
    CSR_MEPC -> Mepc_rdata,
    CSR_MCAUSE -> Mcause_rdata
  )
  // 管他了，找不到就赋值0了
  io.CSR_rdata := MuxLookup(io.CSRAddress, 0.U(32.W))(CSRMap)
  // 我是真的服了，居然光直接跳过命令控制，直接连线看具体指令还不够，过不了检查，还得为了检查去看地址有没有匹配
  val IsAddressValid = IsCSRAddress(CSR_MCYCLE) ||
    IsCSRAddress(CSR_MCYCLEH) ||
    IsCSRAddress(CSR_MVENDORID) ||
    IsCSRAddress(CSR_MARCHID) ||
    IsCSRAddress(CSR_MSTATUS) ||
    IsCSRAddress(CSR_MTVEC) ||
    IsCSRAddress(CSR_MEPC) ||
    IsCSRAddress(CSR_MCAUSE)
  io.CSRValid := CSRReadCommand && IsAddressValid
  // ecall和ebreak跳mtvec，mret跳mepc
  io.ExceptionTaken := ExceptionCommit || MretCommit
  io.ExceptionTarget := Mux(
    io.IsMret,
    Mepc_rdata,
    Cat(Mtvec_rdata(31, 2), 0.U(2.W))
  )
}
