package ysyx_26030103.idu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_NPCConfig
import _root_.ysyx_26030103.common.ysyx_26030103_opcode._
import _root_.ysyx_26030103.common.ysyx_26030103_IFUMessage
import _root_.ysyx_26030103.common.ysyx_26030103_IDUMessage
class ysyx_26030103_IDU(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_IFUMessage))
    val out = Decoupled(new ysyx_26030103_IDUMessage)
    val ReadDATA1 = Input(UInt(32.W))
    val ReadDATA2 = Input(UInt(32.W))
    val Read1SELECT = Output(UInt(5.W))
    val Read2SELECT = Output(UInt(5.W))
    val ex_valid = Input(Bool())
    val ex_rd = Input(UInt(5.W))
    val ex_regWrite = Input(Bool())
    val wb_valid = Input(Bool())
    val wb_rd = Input(UInt(5.W))
    val wb_regWrite = Input(Bool())
    val ex_memop = Input(Bool())
    // 转发: EX阶段的最终写回值及其就绪标志, WB阶段的写回值(总是就绪)
    val ex_fwd_ready = Input(Bool())
    val ex_fwd_data = Input(UInt(32.W))
    val ex_mdu_hidden_writes = Input(UInt(32.W))
    val wb_fwd_data = Input(UInt(32.W))
    // MEM级(MEM)的冒险检测与转发
    val me_valid = Input(Bool())
    val me_rd = Input(UInt(5.W))
    val me_regWrite = Input(Bool())
    val me_memop = Input(Bool())
    val me_fwd_ready = Input(Bool())
    val me_fwd_data = Input(UInt(32.W))
    // MEM级等待槽(EX/MEM流水寄存器里等待的指令,比MEM级年轻)
    val me2_valid = Input(Bool())
    val me2_rd = Input(UInt(5.W))
    val me2_regWrite = Input(Bool())
    val me2_memop = Input(Bool())
    val me2_fwd_ready = Input(Bool())
    val me2_fwd_data = Input(UInt(32.W))
    val pipeline_mode = Input(Bool())
    val perf_stall_raw = Output(Bool())
    val perf_stall_raw_loaduse = Output(Bool())
    val perf_stall_raw_alu = Output(Bool())
  })
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
  // custom-0 编码只用于仿真器的 AM halt ABI。标准 EBREAK 始终保留其
  // breakpoint 异常语义，不能再被宿主机无条件当成退出请求。
  val IsSimHalt = Instruction === "h0000000b".U(32.W)
  // FENCE 和 FENCE.I 都属于 MISC-MEM 操作码类别，但在下游具有不同的
  // 顺序约束和冲刷语义。
  // 基础实现可以保守地把所有fm/pred/succ配置都执行成完整FENCE。
  // 为向前兼容，保留的fm编码以及rd/rs1都必须被忽略；FENCE.TSO也可
  // 合法地退化为更强的FENCE RW,RW，不能因fm非零报非法指令。
  val IsFence =
    (opcode === OPCODE_MiscMem) && (funct3 === "b000".U(3.W))
  val IsFenceI = (opcode === OPCODE_MiscMem) && (funct3 === "b001".U(3.W))
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
  ) { // 间接跳转并链接（JALR）
    WBSelect := WB_SNPC
  }.elsewhen(IsJType) { // 跳转并链接（JAL）
    WBSelect := WB_SNPC
  }.otherwise { // 正常写回的普通指令
    WBSelect := WB_ALU
  }
  val ImmediateGeneratorModule = Module(new ysyx_26030103_ImmediateGenerator)
  ImmediateGeneratorModule.io.Instruction := Instruction
  val Immediate = ImmediateGeneratorModule.io.Immediate
  // ALU 控制解码已经合并为一个模块，直接使用 opcode/funct3/funct7 完成译码。
  val ALUDecoderModule = Module(new ysyx_26030103_ALUDecoder(config))
  ALUDecoderModule.io.opcode := opcode
  ALUDecoderModule.io.funct3 := funct3
  ALUDecoderModule.io.funct7 := funct7
  val ALUCtrl = ALUDecoderModule.io.ALUCtrl
  val ALUCDIllegal = ALUDecoderModule.io.Illegal
  val IsMDU = ALUDecoderModule.io.IsMDU
  val MDUOp = ALUDecoderModule.io.MDUOp
  // ALUCDIllegal只覆盖了已知指令类别里funct3/funct7非法的情况,这里补上"不属于任何已知指令"的检测:
  // System里只实现了csrrw/csrrs/ecall/ebreak/mret,MiscMem里fence/fence.i分别处理。
  val IsKnownInstruction =
    IsRType || IsIType || IsSType || IsBType || IsUType || IsJType ||
      IsCsrrw || IsCsrrs || IsEcall || IsEbreak || IsSimHalt || IsMret ||
      IsFenceI || IsFence
  // CSR 访问合法性在译码时检查，而不是依赖 CSRUnit 的读取数据多路选择器。
  // 本核心目前只实现下面明确列出的机器态 CSR 集合，访问集合外的 CSR
  // 将被视为非法指令。
  val CSRAddress = Instruction(31, 20)
  val CSRAddressValid =
    (CSRAddress === "hB00".U(12.W)) || // 机器周期计数器（mcycle）
      (CSRAddress === "hB80".U(12.W)) || // 机器周期计数器高位（mcycleh）
      (CSRAddress === "hF11".U(12.W)) || // 机器厂商编号（mvendorid，只读）
      (CSRAddress === "hF12".U(12.W)) || // 机器架构编号（marchid，只读）
      (CSRAddress === "h300".U(12.W)) || // 机器状态寄存器（mstatus）
      (CSRAddress === "h305".U(12.W)) || // 机器陷阱向量基址（mtvec）
      (CSRAddress === "h341".U(12.W)) || // 机器异常程序计数器（mepc）
      (CSRAddress === "h342".U(12.W))   // 机器陷阱原因（mcause）
  // CSRRW 始终执行写入，包括 rd=x0 的情况。CSRRS 仅在 rs1!=x0 时写入；
  // rs1=x0 的形式是纯读取操作，因此可合法访问只读 CSR。
  val CSRWriteIntent = IsCsrrw || (IsCsrrs && (Rs1 =/= 0.U))
  // 根据特权级 ISA，地址的 [11:10] 位为 11 表示只读 CSR。这里保留通用规则，
  // 防止以后新增的只读表项意外变为可写。
  val CSRReadOnly = CSRAddress(11, 10) === "b11".U(2.W)
  val CSRIllegal =
    (IsCsrrw || IsCsrrs) &&
      (!CSRAddressValid || (CSRWriteIntent && CSRReadOnly))
  // custom-0 不属于通用 ALU decoder 的 opcode 集合；精确匹配的仿真
  // halt 编码在这里显式豁免，custom-0 的其它编码仍然是非法指令。
  val IllegalInsn =
    (ALUCDIllegal && !IsSimHalt) || !IsKnownInstruction || CSRIllegal
  val needsRs2 = IsRType || IsBType || IsSType
  // 源操作数真正被使用的判断(lui/auipc/jal的rs1字段是立即数,不算使用)
  val usesRs1 = IsRType || IsIType || IsSType || IsBType || IsCsrrw || IsCsrrs
  // 转发命中(EXU > MEM等待槽 > MEM级 > WBU: 多条同时命中时选最年轻生产者)
  val ex_fwd_rs1 =
    usesRs1 && io.ex_fwd_ready && io.ex_rd =/= 0.U && io.ex_rd === Rs1
  val ex_fwd_rs2 =
    io.ex_fwd_ready && io.ex_rd =/= 0.U && needsRs2 && io.ex_rd === Rs2
  val me2_fwd_rs1 =
    usesRs1 && io.me2_fwd_ready && io.me2_rd =/= 0.U && io.me2_rd === Rs1
  val me2_fwd_rs2 =
    io.me2_fwd_ready && io.me2_rd =/= 0.U && needsRs2 && io.me2_rd === Rs2
  val me_fwd_rs1 =
    usesRs1 && io.me_fwd_ready && io.me_rd =/= 0.U && io.me_rd === Rs1
  val me_fwd_rs2 =
    io.me_fwd_ready && io.me_rd =/= 0.U && needsRs2 && io.me_rd === Rs2
  val wb_fwd_rs1 =
    usesRs1 && io.wb_valid && io.wb_regWrite && io.wb_rd =/= 0.U &&
      io.wb_rd === Rs1
  val wb_fwd_rs2 =
    io.wb_valid && io.wb_regWrite && io.wb_rd =/= 0.U && needsRs2 && io.wb_rd === Rs2
  val src1 = Mux(
    ex_fwd_rs1,
    io.ex_fwd_data,
    Mux(
      me2_fwd_rs1,
      io.me2_fwd_data,
      Mux(
        me_fwd_rs1,
        io.me_fwd_data,
        Mux(wb_fwd_rs1, io.wb_fwd_data, io.ReadDATA1)
      )
    )
  )
  val src2 = Mux(
    ex_fwd_rs2,
    io.ex_fwd_data,
    Mux(
      me2_fwd_rs2,
      io.me2_fwd_data,
      Mux(
        me_fwd_rs2,
        io.me_fwd_data,
        Mux(wb_fwd_rs2, io.wb_fwd_data, io.ReadDATA2)
      )
    )
  )
  val ALU_A = WireDefault(src1) // 默认所有指令的第一个计算的数是寄存器值
  switch(opcode) {
    is(OPCODE_UpperImmediate_lui) {
      ALU_A := 0.U(32.W)
    }
    is(OPCODE_UpperImmediate_auipc, OPCODE_Jump) {
      ALU_A := pc
    }
  }
  val ALU_B = Mux(opcode === OPCODE_Register, src2, Immediate)
  val ex_hazard = io.ex_valid && io.ex_regWrite && io.ex_rd =/= 0.U &&
    ((usesRs1 && io.ex_rd === Rs1) || (needsRs2 && io.ex_rd === Rs2))
  val me2_hazard = io.me2_valid && io.me2_regWrite && io.me2_rd =/= 0.U &&
    ((usesRs1 && io.me2_rd === Rs1) || (needsRs2 && io.me2_rd === Rs2))
  val me_hazard = io.me_valid && io.me_regWrite && io.me_rd =/= 0.U &&
    ((usesRs1 && io.me_rd === Rs1) || (needsRs2 && io.me_rd === Rs2))
  // 普通 EX 冒险端口只表示最老的 MDU 指令。队列中更年轻的 MDU 生产者
  // 尚无可供前递的值。
  val hidden_mdu_hazard =
    (usesRs1 && Rs1 =/= 0.U && io.ex_mdu_hidden_writes(Rs1)) ||
      (needsRs2 && Rs2 =/= 0.U && io.ex_mdu_hidden_writes(Rs2))
  // 只有生产者的数据未就绪(load未完成)才需要阻塞,其余全部转发
  val isRAW = Mux(
    io.pipeline_mode,
    (ex_hazard && !io.ex_fwd_ready) || (me2_hazard && !io.me2_fwd_ready) ||
      (me_hazard && !io.me_fwd_ready) || hidden_mdu_hazard,
    false.B
  )
  io.in.ready := io.out.ready && !isRAW
  io.out.valid := io.in.valid && !isRAW
  val raw_loaduse =
    (ex_hazard && io.ex_memop) || (me2_hazard && io.me2_memop) || (me_hazard && io.me_memop)
  io.perf_stall_raw := io.in.valid && isRAW
  io.perf_stall_raw_loaduse := io.in.valid && isRAW && raw_loaduse
  io.perf_stall_raw_alu := io.in.valid && isRAW && !raw_loaduse
  io.out.bits.Instruction := Instruction
  io.out.bits.pc := pc
  io.out.bits.snpc := snpc
  io.out.bits.ALUCtrl := ALUCtrl
  io.out.bits.IsMDU := IsMDU
  io.out.bits.MDUOp := MDUOp
  io.out.bits.ALU_A := ALU_A
  io.out.bits.ALU_B := ALU_B
  io.out.bits.BranchA := src1
  io.out.bits.BranchB := src2
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
  io.out.bits.StoreData := src2
  io.out.bits.IsCsrrw := IsCsrrw
  io.out.bits.IsCsrrs := IsCsrrs
  io.out.bits.IsEcall := IsEcall
  io.out.bits.IsEbreak := IsEbreak
  io.out.bits.IsSimHalt := IsSimHalt
  io.out.bits.IsMret := IsMret
  io.out.bits.IsFence := IsFence
  io.out.bits.IsFenceI := IsFenceI
  io.out.bits.CSRAddress := CSRAddress
  io.out.bits.Rs1 := Rs1
  io.out.bits.Rs1Data := src1
  io.out.bits.ALUCDIllegal := ALUCDIllegal
  // 异常传递:IFU的取指错优先(此时指令本身是垃圾,IDU的译码结果不可信),否则报非法指令(cause=2)
  io.out.bits.ExceptionValid := io.in.bits.ExceptionValid || IllegalInsn
  io.out.bits.ExceptionCause := Mux(
    io.in.bits.ExceptionValid,
    io.in.bits.ExceptionCause,
    2.U(4.W)
  )
  io.out.bits.AccessFaultResp := io.in.bits.AccessFaultResp
  // 分支预测信息透传
  io.out.bits.pred_taken := io.in.bits.pred_taken
  io.out.bits.pred_target := io.in.bits.pred_target
}
