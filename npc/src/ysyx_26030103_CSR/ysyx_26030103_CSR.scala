package ysyx_26030103.ysyx_26030103_CSR
import chisel3._
import chisel3.util._
class ysyx_26030103_CSR extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val Enable = Input(Bool())
    // ysyx_26030103_IDU给出的指令译码信号
    val IsCsrrw = Input(Bool())
    val IsCsrrs = Input(Bool())
    val IsEcall = Input(Bool())
    // Chisel版本设计的真正ebreak，不是之前Verilog里依赖DPI-C结束仿真的ebreak
    val IsEbreak = Input(Bool())
    val IsMret = Input(Bool())
    val CSRAddress = Input(UInt(12.W)) // ysyx_26030103_CSR地址，指令[31:20]
    val rs1 = Input(UInt(5.W)) // rs1寄存器编号，用于判断csrrs是否写ysyx_26030103_CSR
    val Rs1Data = Input(UInt(32.W)) // Rs1寄存器的数据
    val pc = Input(UInt(32.W)) // 现在的ysyx_26030103_PC，就是ecall时保存到ysyx_26030103_mepc
    val CSR_rdata = Output(UInt(32.W)) // ysyx_26030103_CSR读出，写到rd
    val CSRValid = Output(Bool()) // 如果当前是ysyx_26030103_CSR指令，那就需要写回rd
    // 异常和返回的跳转控制
    val ExceptionTaken = Output(Bool()) // 需要跳转，ecall到ysyx_26030103_mtvec，mret到ysyx_26030103_mepc
    val ExceptionTarget = Output(UInt(32.W)) // 跳转目标地址
    // 新加的，真正的处理功能
    val Interrupt = Input(Bool())
    // EXU提交点送来的异常:IFU取指错(1)/IDU非法指令(2)/LSU访存错(5或7),与ecall/ebreak走同一条提交通路
    val TrapValid = Input(Bool())
    val TrapCause = Input(UInt(32.W))
    // 中断提交标志(Enable且中断被接受),给EXU用来压掉被中断指令的副作用:
    // mepc记的是这条指令自己的PC,mret后它会重新执行,因此它本次不得写GPR/CSR
    val IrqCommit = Output(Bool())
  })
  // ysyx_26030103_mcycle=12'hB00，低32位
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
  val Marchid_rdata = "h018d3017".U(32.W)
  def IsCSRAddress(addr: UInt): Bool = io.CSRAddress === addr
  // csrrw时写，csrrs时rs1非零才写
  val CSRReadCommand =
    io.IsCsrrw || io.IsCsrrs // 忘记了，后面重看的，以防以后忘记，先标记一下，这是看现在是不是会读取ysyx_26030103_CSR旧数值的ysyx_26030103_CSR指令
  val csrrwWen = io.Enable && io.IsCsrrw
  val csrrsWen = io.Enable && io.IsCsrrs && io.rs1 =/= 0.U // 不能是0号寄存器
  // csrrw直接写rs1，csrrs写旧值|rs1
  def CSRWriteData(old: UInt): UInt =
    Mux(io.IsCsrrw, io.Rs1Data, old | io.Rs1Data)
  val MretCommit = io.Enable && io.IsMret
  /*
  val ExceptionCause =
    Mux( // 标记一下，English确实是烂，cause是名词（n）翻译是原因，Verilog那边能记住，这里就记不住，服了
      io.IsEbreak,
      3.U(32.W),
      11.U(32.W)
    ) // 如果是ebreak，ysyx_26030103_mcause=3，不然就是ecall，ysyx_26030103_mcause=11
   */
  val Mstatus = Module(new ysyx_26030103_mstatus)
  val Mtvec = Module(new ysyx_26030103_mtvec)
  val Mepc = Module(new ysyx_26030103_mepc)
  val Mcause = Module(new ysyx_26030103_mcause)
  val Mcycle = Module(new ysyx_26030103_mcycle)
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
  // 真正的ebreak按RISC-V breakpoint异常处理：写ysyx_26030103_mepc/ysyx_26030103_mcause，然后跳ysyx_26030103_mtvec
  val HasInterrupt =
    io.Interrupt && Mstatus_rdata(3) // ysyx_26030103_mstatus寄存器第3位是MIE，这个是处理器的中断使能，允许中断进来
  val IsException = io.IsEcall || io.IsEbreak || HasInterrupt || io.TrapValid
  /*
  根据RISCV手册来看，31号bit如果是0那就是异常，而如果是1，那就是中断
  30号到0号，则是编号，3是异常，7是机器定时器中断（就是Machine Timer），11是ecall
   */
  val ExceptionCause = WireDefault(0.U(32.W))
  when(HasInterrupt) {
    ExceptionCause := "h80000007".U(32.W) // ysyx_26030103_mcause=7，bit31=1说明这是中断不是异常
  }.elsewhen(io.TrapValid) {
    ExceptionCause := io.TrapCause // EXU提交点送来的异常号:取指错1/非法指令2/load访存错5/store访存错7
  }.elsewhen(io.IsEbreak) {
    ExceptionCause := 3.U(32.W) // breakpoint
  }.otherwise { // 只剩ecall
    ExceptionCause := 11.U(32.W) // ecall
  }
  // 新加的这行代码，irq是Interrupt ReQuest，是中断请求的意思
  val HasIrqCommit = io.Enable && HasInterrupt
  // 中断提交时被中断的指令要被压掉(mepc记的是它自己的PC,mret后重跑),因此它本次不得写CSR/GPR
  val CSRWen = (csrrwWen || csrrsWen) && !HasInterrupt
  io.IrqCommit := HasIrqCommit
  val ExceptionCommit = io.Enable && IsException
  // 以下这段代码是AI编写的
  /*
  就是RISCV有两种模式，一种是BASE模式，一种是向量模式
  基础模式（好像BASE的中文翻译应该是叫基址），bit0=0
  不管什么原因，比如ecall和ebreak和定时器中断，全往同一个地址跳，然后操作系统这边写一个入口函数，进去后再读ysyx_26030103_mcause，然后判
  断到底发生了什么
  就是ysyx_26030103_mtvec存了什么地址，ecall和ebreak和中断就跳到哪个地址
  向量模式，bit0=1
  不同原因跳不同的地址，然后跳的地址是BASE+4 x n，
  就比如说ysyx_26030103_mtvec存0x80000001，BASE是0x80000000
  ecall和ebreak还是BASE，然后是中断7，所以中断跳的地址是BASE+4x7
   */
  val ExceptionTargetBase = Cat(Mtvec_rdata(31, 2), 0.U(2.W)) // ysyx_26030103_mtvec 地址，4 字节对齐
  when(io.IsMret) {
    io.ExceptionTarget := Mepc_rdata // mret → 从异常返回，跳 ysyx_26030103_mepc
  }.otherwise {
    io.ExceptionTarget := ExceptionTargetBase // 中断/异常 → 统一跳 ysyx_26030103_mtvec
  }
  // ecall和ebreak异常进入时，MPP写11，MPIE写旧MIE，MIE写0
  // MPP，00是U，01是S，11是M
  // 他妈的又忘记了，先标记一下，MPP是标记现在的等级的，MPIE是专门保存MIE的，MIE在异常的时候要关闭中断（归零）
  val MstatusExceptionData = Cat(
    Mstatus_rdata(31, 13),
    "b11".U(2.W),
    Mstatus_rdata(10, 8),
    Mstatus_rdata(3), // MPIE赶快把MIE保存下来
    Mstatus_rdata(6, 4),
    0.U(1.W), // MIE赶紧归零，赶快把中断关掉，别到时候又来个新的中断过来，然后他妈的直接打断施法了
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
    ), // 这段重新写了，就直接不用拆分变量了，直接默认ysyx_26030103_CSR指令正常的写入，然后根据csrrw和csrrs的语义来算新的数值
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
  // 好吧，确实可以正常写的，那这个也要改成ysyx_26030103_mcause的样子
  Mepc.io.wdata := Mux(ExceptionCommit, io.pc, CSRWriteData(Mepc_rdata))
  Mcause.io.wdata := Mux(
    ExceptionCommit,
    ExceptionCause,
    // 先标记一下，这是正常的写入，最开始不知道，以为可以直接写if语句收ExceptionCause，后面查了才知道，软件也可以写csrrw和csrrs
    // 等下，那说明ysyx_26030103_mepc也是可以正常转的吗？
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
  // ecall和ebreak跳ysyx_26030103_mtvec，mret跳ysyx_26030103_mepc
  io.ExceptionTaken := ExceptionCommit || MretCommit
  io.ExceptionTarget := Mux(
    io.IsMret,
    Mepc_rdata,
    Cat(Mtvec_rdata(31, 2), 0.U(2.W))
  )
}
