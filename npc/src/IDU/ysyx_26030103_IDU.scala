package ysyx_26030103.idu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.config.ysyx_26030103_NPCConfig
import _root_.ysyx_26030103.common.ysyx_26030103_opcode._
import _root_.ysyx_26030103.common.ysyx_26030103_IFUMessage
import _root_.ysyx_26030103.common.ysyx_26030103_IDUMessage
import _root_.ysyx_26030103.common.ysyx_26030103_AtomicOp
import _root_.ysyx_26030103.fpu._
class ysyx_26030103_IDU(
    val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig()
) extends Module {
  private val hasS = config.MMU.hasS
  private val hasU = config.MMU.hasU
  private val XLEN = config.XLEN
  private val FLEN = config.FPULen
  private val UseF = config.UseF
  private val UseD = config.UseD
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_IFUMessage(XLEN)))
    val out = Decoupled(new ysyx_26030103_IDUMessage(XLEN))
    val ReadDATA1 = Input(UInt(XLEN.W))
    val ReadDATA2 = Input(UInt(XLEN.W))
    val Read1SELECT = Output(UInt(5.W))
    val Read2SELECT = Output(UInt(5.W))
    // 浮点寄存器堆读口。FLEN 可能大于 XLEN（RV32F 的 FLEN=32 时相等；
    // RV64 的 FLEN=64 与 XLEN 相等；这里统一用 64 位转发总线）。
    val ReadFPR1 = Input(UInt(64.W))
    val ReadFPR2 = Input(UInt(64.W))
    val ReadFPR3 = Input(UInt(64.W))
    val Read1FSELECT = Output(UInt(5.W))
    val Read2FSELECT = Output(UInt(5.W))
    val Read3FSELECT = Output(UInt(5.W))
    // 当前 frm；动态舍入在译码级解析，非法 frm 直接判非法指令。
    val Frm = Input(UInt(3.W))
    // EXU/MEM/WB 的浮点结果转发（与整数转发端口独立）。
    val ex_fp_valid = Input(Bool())
    val ex_fp_rd = Input(UInt(5.W))
    val ex_fp_regWrite = Input(Bool())
    val ex_fp_fwd_ready = Input(Bool())
    val ex_fp_fwd_data = Input(UInt(64.W))
    val me_fp_valid = Input(Bool())
    val me_fp_rd = Input(UInt(5.W))
    val me_fp_regWrite = Input(Bool())
    val me_fp_fwd_ready = Input(Bool())
    val me_fp_fwd_data = Input(UInt(64.W))
    val me2_fp_valid = Input(Bool())
    val me2_fp_rd = Input(UInt(5.W))
    val me2_fp_regWrite = Input(Bool())
    val me2_fp_fwd_ready = Input(Bool())
    val me2_fp_fwd_data = Input(UInt(64.W))
    val wb_fp_valid = Input(Bool())
    val wb_fp_rd = Input(UInt(5.W))
    val wb_fp_regWrite = Input(Bool())
    val wb_fp_fwd_data = Input(UInt(64.W))
    val ex_valid = Input(Bool())
    val ex_rd = Input(UInt(5.W))
    val ex_regWrite = Input(Bool())
    val wb_valid = Input(Bool())
    val wb_rd = Input(UInt(5.W))
    val wb_regWrite = Input(Bool())
    val ex_memop = Input(Bool())
    // 转发: EX阶段的最终写回值及其就绪标志, WB阶段的写回值(总是就绪)
    val ex_fwd_ready = Input(Bool())
    val ex_fwd_data = Input(UInt(XLEN.W))
    val ex_mdu_hidden_writes = Input(UInt(32.W))
    val wb_fwd_data = Input(UInt(XLEN.W))
    // MEM级(MEM)的冒险检测与转发
    val me_valid = Input(Bool())
    val me_rd = Input(UInt(5.W))
    val me_regWrite = Input(Bool())
    val me_memop = Input(Bool())
    val me_fwd_ready = Input(Bool())
    val me_fwd_data = Input(UInt(XLEN.W))
    // MEM级等待槽(EX/MEM流水寄存器里等待的指令,比MEM级年轻)
    val me2_valid = Input(Bool())
    val me2_rd = Input(UInt(5.W))
    val me2_regWrite = Input(Bool())
    val me2_memop = Input(Bool())
    val me2_fwd_ready = Input(Bool())
    val me2_fwd_data = Input(UInt(XLEN.W))
    val pipeline_mode = Input(Bool())
    // DMMU 正在翻译一条访存指令时阻塞 IDU，避免后续消费者带着陈旧寄存器值
    // 越过尚未进入 LSU 的 load/store。
    val mmu_stall = Input(Bool())
    val Privilege = Input(UInt(2.W))
    val Mstatus = Input(UInt(XLEN.W))
    val Mcounteren = Input(UInt(32.W))
    val Scounteren = Input(UInt(32.W))
    val perf_stall_raw = Output(Bool())
    val perf_stall_raw_loaduse = Output(Bool())
    val perf_stall_raw_alu = Output(Bool())
  })
  // 取指消息保留原始编码；RVC 展开后的 32 位形式只用于本模块译码。
  // 这样 EXU/trace/非法指令 mtval 仍能看到真实的 16 位编码。
  val RawInstruction = io.in.bits.Instruction
  val IsCompressed = io.in.bits.IsCompressed
  val Instruction = WireDefault(RawInstruction)
  val RvcIllegal = WireDefault(false.B)
  if (config.UseC) {
    val RVCDecoderModule = Module(new ysyx_26030103_RVCDecoder(XLEN))
    RVCDecoderModule.io.in := RawInstruction
    when(IsCompressed) {
      Instruction := RVCDecoderModule.io.out
    }
    RvcIllegal := IsCompressed && RVCDecoderModule.io.illegal
  }
  // C 扩展关闭时，任何低两位不是 11 的取指槽都是压缩指令，必须明确
  // 按非法指令陷入，不能把相邻的半字误拼成一条 32 位指令执行。
  val CExtensionDisabledIllegal =
    if (config.UseC) false.B
    else RawInstruction(1, 0) =/= "b11".U(2.W)
  val pc = io.in.bits.pc
  // 压缩指令只前进 2 字节；这也是分支/JAL 链接值和 trap 返回地址的
  // 顺序后继语义。
  val snpc = pc + Mux(IsCompressed, 2.U(XLEN.W), 4.U(XLEN.W))
  val Rs1 = Instruction(19, 15)
  val Rs2 = Instruction(24, 20)
  io.Read1SELECT := Rs1
  io.Read2SELECT := Rs2
  // 浮点读口：rs1/rs2/rs3 都是 5 位字段；无效时读取结果不会被使用。
  io.Read1FSELECT := Rs1
  io.Read2FSELECT := Rs2
  io.Read3FSELECT := Instruction(31, 27)
  val Rd = Instruction(11, 7)
  val opcode = Instruction(6, 0)
  val funct3 = Instruction(14, 12)
  val funct7 = Instruction(31, 25)
  // OP-32 在 RV64 下复用同一套 R 型/立即数数据通路；RV32 时这些 opcode
  // 会被 ALUDecoder 判为非法指令。
  val IsRType =
    (opcode === OPCODE_Register) || (opcode === OPCODE_RegisterWord)
  val IsIType =
    (opcode === OPCODE_Immediate) || (opcode === OPCODE_ImmediateWord) ||
      (opcode === OPCODE_Immediate_Lxxx) || (opcode === OPCODE_Immediate_Bxxx)
  val IsSType = (opcode === OPCODE_Store)
  val IsBType = (opcode === OPCODE_Branch)
  val IsUType =
    (opcode === OPCODE_UpperImmediate_lui) || (opcode === OPCODE_UpperImmediate_auipc)
  val IsJType = (opcode === OPCODE_Jump)
  val IsLoad = (opcode === OPCODE_Immediate_Lxxx)
  val IsSystem = (opcode === OPCODE_System)
  // 两个中间变量，他妈了个逼的，Verilog的wire既能读还能写，chisel3的输出端口还他妈的只能写不能读，还得搞中间变量
  // Zicsr 的寄存器型和立即数型编码只在 funct3[2] 上有区别；低两位
  // 分别选择 RW/RS/RC。funct3=000 仍留给 ECALL/EBREAK/MRET。
  val IsCSRInstruction = IsSystem && funct3(1, 0) =/= 0.U
  val IsCSRImmediate = IsCSRInstruction && funct3(2)
  val IsCsrrw = IsCSRInstruction && funct3(1, 0) === "b01".U(2.W)
  val IsCsrrs = IsCSRInstruction && funct3(1, 0) === "b10".U(2.W)
  val IsCsrrc = IsCSRInstruction && funct3(1, 0) === "b11".U(2.W)
  val IsEbreak = Instruction === "h00100073".U(32.W)
  val IsEcall = Instruction === "h00000073".U(32.W)
  val IsMret = Instruction === "h30200073".U(32.W)
  // HAS_S=n 时不存在 S 状态，SRET/SFENCE.VMA 连译码匹配都不生成，
  // 直接落入非法指令路径。
  val IsSret =
    if (hasS) Instruction === "h10200073".U(32.W) else false.B
  // sfence.vma 编码：funct7=0001001, funct3=000, rd=0, SYSTEM。
  // rs1/rs2 的取值由软件决定，MMU 侧当前统一做全量 TLB 刷新。
  val IsSfenceVma =
    if (hasS)
      (opcode === OPCODE_System) && (funct7 === "b0001001".U(7.W)) &&
        (funct3 === "b000".U(3.W)) && (Instruction(11, 7) === 0.U)
    else false.B
  // 本核仅实现 M-mode，可将 WFI 合法地实现为 NOP。必须精确匹配整个
  // 标准编码，避免把其它 funct3=000 的保留 SYSTEM 编码一并放行。
  val IsWfi = Instruction === "h10500073".U(32.W)
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
  // A 扩展：funct3=010 为 .W，RV64 另外允许 .D(funct3=011)。
  // 其余 funct3 以及 RISCV_A=n 时整个 AMO 操作码都按非法指令处理。
  val AmoFunct5 = Instruction(31, 27)
  // 只放行标准定义的 11 个 funct5；其余保留编码必须落入非法指令。
  val AmoFunct5Valid = Seq(
    ysyx_26030103_AtomicOp.ADD, ysyx_26030103_AtomicOp.SWAP,
    ysyx_26030103_AtomicOp.LR, ysyx_26030103_AtomicOp.SC,
    ysyx_26030103_AtomicOp.XOR, ysyx_26030103_AtomicOp.OR,
    ysyx_26030103_AtomicOp.AND, ysyx_26030103_AtomicOp.MIN,
    ysyx_26030103_AtomicOp.MAX, ysyx_26030103_AtomicOp.MINU,
    ysyx_26030103_AtomicOp.MAXU
  ).map(AmoFunct5 === _).reduce(_ || _)
  val IsAtomic =
    if (config.UseA)
      (opcode === OPCODE_Amo) && AmoFunct5Valid &&
        ((funct3 === "b010".U(3.W)) ||
          (if (XLEN == 64) funct3 === "b011".U(3.W) else false.B))
    else false.B
  // funct5 直接作为原子操作号（00010=LR，00011=SC，其余为 AMO）。
  val AtomicOp = Mux(IsAtomic, AmoFunct5, 0.U(5.W))
  val IsLr = IsAtomic && AtomicOp === ysyx_26030103_AtomicOp.LR

  // ---------------------------------------------------------------- F/D 译码
  // 所有 F/D 编码在这里集中解析；vendor 内部操作码不会进入上层。
  val FpLoadOpcode = opcode === OPCODE_LoadFP
  val FpStoreOpcode = opcode === OPCODE_StoreFP
  val FpOpcode = opcode === OPCODE_OpFP
  val FpFmaOpcode = (opcode === OPCODE_Fmadd) || (opcode === OPCODE_Fmsub) ||
    (opcode === OPCODE_Fnmsub) || (opcode === OPCODE_Fnmadd)
  val FpMemOpcode = FpLoadOpcode || FpStoreOpcode
  // FLW=010, FLD=011 / FSW=010, FSD=011。
  val FpMemFunct3Legal = funct3 === "b010".U(3.W) ||
    (UseD.B && funct3 === "b011".U(3.W))
  // 格式：load/store 由 funct3 bit0 选择，OP-FP/FMA 由指令 bit[26:25] 选择。
  val FpFmt = Mux(FpMemOpcode, Mux(funct3(0), ysyx_26030103_FpuFmt.D,
    ysyx_26030103_FpuFmt.S), Instruction(26, 25))
  val FpUseD = FpFmt === ysyx_26030103_FpuFmt.D
  val FpOp5 = funct7(6, 2)
  val FpRs2 = Instruction(24, 20)
  val IsFPLoad = UseF.B && FpLoadOpcode && FpMemFunct3Legal
  val IsFPStore = UseF.B && FpStoreOpcode && FpMemFunct3Legal
  val IsFpOp = UseF.B && FpOpcode
  val IsFpFma = UseF.B && FpFmaOpcode
  val IsFP = IsFPLoad || IsFPStore || IsFpOp || IsFpFma
  val FpIsFpu = IsFpOp || IsFpFma
  // 使用 rm 的编码：FADD/FSUB/FMUL/FDIV/FSQRT/FCVT/FMA。
  val FpUsesRm = IsFpFma || (IsFpOp && (
    (FpOp5 === 0.U) || (FpOp5 === 1.U) || (FpOp5 === 2.U) ||
      (FpOp5 === 3.U) || (FpOp5 === 11.U) || (FpOp5 === 8.U) ||
      (FpOp5 === 24.U) || (FpOp5 === 26.U)))
  val FpRmRaw = Mux(FpUsesRm, funct3, 0.U(3.W))
  val FpRm = Mux(FpRmRaw === ysyx_26030103_FpuRm.DYN, io.Frm, FpRmRaw)
  // FCVT.W/WU/L/LU 的宽度由 rs2[1] 选择；FMV.X.W/D 与 FMV.W.X/D.X
  // 的宽度由 fmt 决定（rs2 恒为 0）。
  val FpMvOp = IsFpOp && ((FpOp5 === 28.U) || (FpOp5 === 30.U))
  val FpIntWord = Mux(FpMvOp, FpFmt === ysyx_26030103_FpuFmt.S,
    FpRs2(1) === 0.U)
  val FpIntSigned = FpRs2(0) === 0.U
  // FCVT.S.D 的源是 D，FCVT.D.S 的源是 S；其余指令源/目的同格式。
  val FpSrcFmt = Mux(IsFpOp && FpOp5 === 8.U,
    Mux(FpFmt === ysyx_26030103_FpuFmt.S, ysyx_26030103_FpuFmt.D,
      ysyx_26030103_FpuFmt.S), FpFmt)

  // 编码合法性。未识别/保留编码必须精确落入 illegal instruction。
  val FpFmtLegal = (FpFmt === ysyx_26030103_FpuFmt.S) ||
    (UseD.B && FpFmt === ysyx_26030103_FpuFmt.D)
  val FpMemEncodingLegal =
    !FpMemOpcode || (funct3 === "b010".U(3.W) ||
      (UseD.B && funct3 === "b011".U(3.W)))
  val FpOp5Legal = !IsFpOp || (
    (FpOp5 === 0.U) || (FpOp5 === 1.U) || (FpOp5 === 2.U) ||
      (FpOp5 === 3.U) || (FpOp5 === 4.U) || (FpOp5 === 5.U) ||
      (FpOp5 === 11.U) || (FpOp5 === 8.U) || (FpOp5 === 20.U) ||
      (FpOp5 === 24.U) || (FpOp5 === 26.U) || (FpOp5 === 28.U) ||
      (FpOp5 === 30.U))
  val FpFunct3Legal = !IsFpOp || (
    (FpOp5 =/= 4.U || (funct3 === 0.U || funct3 === 1.U || funct3 === 2.U)) &&
      (FpOp5 =/= 5.U || (funct3 === 0.U || funct3 === 1.U)) &&
      (FpOp5 =/= 20.U || (funct3 === 0.U || funct3 === 1.U ||
        funct3 === 2.U)) &&
      (FpOp5 =/= 28.U || (funct3 === 0.U || funct3 === 1.U)) &&
      (FpOp5 =/= 30.U || funct3 === 0.U))
  val FpRs2Legal = !IsFpOp || MuxCase(false.B, Seq(
    (FpOp5 === 0.U) -> true.B,
    (FpOp5 === 1.U) -> true.B,
    (FpOp5 === 2.U) -> true.B,
    (FpOp5 === 3.U) -> true.B,
    (FpOp5 === 4.U) -> true.B,
    (FpOp5 === 5.U) -> true.B,
    (FpOp5 === 11.U) -> (FpRs2 === 0.U),
    // FCVT.S.D: fmt=S, rs2=1；FCVT.D.S: fmt=D, rs2=0。
    (FpOp5 === 8.U) -> (FpRs2 === Mux(FpFmt === ysyx_26030103_FpuFmt.S,
      1.U, 0.U)),
    (FpOp5 === 20.U) -> true.B,
    (FpOp5 === 24.U) -> (FpRs2 <= 3.U && (if (XLEN == 64) true.B else FpRs2 <= 1.U)),
    (FpOp5 === 26.U) -> (FpRs2 <= 3.U && (if (XLEN == 64) true.B else FpRs2 <= 1.U)),
    (FpOp5 === 28.U) -> (FpRs2 === 0.U),
    (FpOp5 === 30.U) -> (FpRs2 === 0.U)
  ))
  val FpRmLegal = !FpUsesRm ||
    (FpRmRaw <= 4.U) || (FpRmRaw === ysyx_26030103_FpuRm.DYN && io.Frm <= 4.U)
  // mstatus.FS=Off 时任何 F/D 指令都是非法指令；fflags/frm/fcsr 属于
  // 浮点状态，访问它们同样必须陷入，不能只拦截算术/访存指令。
  val FpStateEnabled = !UseF.B || io.Mstatus(14, 13) =/= 0.U(2.W)
  val FpCsrIllegal = IsCSRInstruction && UseF.B &&
    (Instruction(31, 20) === 1.U || Instruction(31, 20) === 2.U ||
      Instruction(31, 20) === 3.U) && !FpStateEnabled
  val FpIllegal = FpCsrIllegal || (IsFP && (!FpFmtLegal || !FpMemEncodingLegal ||
    !FpOp5Legal || !FpFunct3Legal || !FpRs2Legal ||
    !FpRmLegal || !FpStateEnabled))

  // 指令 -> 后端无关的 FpuOp。
  val FpOp = WireDefault(0.U(ysyx_26030103_FpuOp.Width.W))
  when(IsFpFma) {
    FpOp := Mux(opcode === OPCODE_Fmadd, ysyx_26030103_FpuOp.FFma,
      Mux(opcode === OPCODE_Fmsub, ysyx_26030103_FpuOp.FFms,
        Mux(opcode === OPCODE_Fnmsub, ysyx_26030103_FpuOp.FFnms,
          ysyx_26030103_FpuOp.FFnma)))
  }.elsewhen(IsFpOp) {
    switch(FpOp5) {
      is(0.U) { FpOp := ysyx_26030103_FpuOp.FAdd }
      is(1.U) { FpOp := ysyx_26030103_FpuOp.FSub }
      is(2.U) { FpOp := ysyx_26030103_FpuOp.FMul }
      is(3.U) { FpOp := ysyx_26030103_FpuOp.FDiv }
      is(4.U) {
        FpOp := Mux(funct3 === 0.U, ysyx_26030103_FpuOp.FSgnj,
          Mux(funct3 === 1.U, ysyx_26030103_FpuOp.FSgnjn,
            ysyx_26030103_FpuOp.FSgnjx))
      }
      is(5.U) {
        FpOp := Mux(funct3 === 0.U, ysyx_26030103_FpuOp.FMin,
          ysyx_26030103_FpuOp.FMax)
      }
      is(11.U) { FpOp := ysyx_26030103_FpuOp.FSqrt }
      is(8.U) { FpOp := ysyx_26030103_FpuOp.FCvtFpToFp }
      is(20.U) {
        FpOp := Mux(funct3 === 2.U, ysyx_26030103_FpuOp.FEq,
          Mux(funct3 === 1.U, ysyx_26030103_FpuOp.FLt,
            ysyx_26030103_FpuOp.FLe))
      }
      is(24.U) {
        FpOp := Mux(FpIntSigned, ysyx_26030103_FpuOp.FCvtFpToInt,
          ysyx_26030103_FpuOp.FCvtFpToUInt)
      }
      is(26.U) {
        FpOp := Mux(FpIntSigned, ysyx_26030103_FpuOp.FCvtIntToFp,
          ysyx_26030103_FpuOp.FCvtUIntToFp)
      }
      is(28.U) {
        FpOp := Mux(funct3 === 1.U, ysyx_26030103_FpuOp.FClass,
          ysyx_26030103_FpuOp.FMvFpToInt)
      }
      is(30.U) { FpOp := ysyx_26030103_FpuOp.FMvIntToFp }
    }
  }
  // 源寄存器语义。
  val FpGprSource1 = IsFPLoad || IsFPStore ||
    (IsFpOp && (FpOp5 === 26.U || FpOp5 === 30.U))
  val FpUsesFpr1 = IsFP && !FpGprSource1
  val FpUsesFpr2 = IsFPStore || IsFpFma || (IsFpOp &&
    ((FpOp5 === 0.U) || (FpOp5 === 1.U) || (FpOp5 === 2.U) ||
      (FpOp5 === 3.U) || (FpOp5 === 4.U) || (FpOp5 === 5.U) ||
      (FpOp5 === 20.U)))
  val FpUsesFpr3 = IsFpFma
  // 写回目标。浮点 load/算术写 f 寄存器；比较/转换到整数/FCLASS 写 GPR。
  val FpWritesFPR = IsFPLoad || IsFpFma || (IsFpOp && (
    (FpOp5 === 0.U) || (FpOp5 === 1.U) || (FpOp5 === 2.U) ||
      (FpOp5 === 3.U) || (FpOp5 === 4.U) || (FpOp5 === 5.U) ||
      (FpOp5 === 8.U) || (FpOp5 === 11.U) || (FpOp5 === 26.U) ||
      (FpOp5 === 30.U)))
  val FpWritesGPR = IsFpOp && ((FpOp5 === 20.U) || (FpOp5 === 24.U) ||
    (FpOp5 === 28.U))

  val RegisterWrite =
    IsRType || IsIType || IsUType || IsJType || IsCSRInstruction || IsAtomic ||
      FpWritesGPR
  val MemoryValid = IsLoad || IsSType || IsAtomic || IsFPLoad || IsFPStore
  // AMO 同时需要读/写权限，SC 只需要写权限；LR 只读。
  val MemoryWrite = IsSType || (IsAtomic && !IsLr) || IsFPStore
  /*
  又忘记了为什么这样做，就是想要减少代码量，主要是代码量多是负担，就是只看末尾的两位，我怀疑可能是RISCV故意这么设计的，也可以简化
  000 LB和SB 1byte
  001 LH和SH 2byte
  010 LW和SW 4byte
  U是没有符号的意思
  100 LBU 1byte（没有符号）
  101 LHU 2byte（没有符号）
   */
  val WidthSelect = Mux(IsLoad || IsSType || IsAtomic || IsFPLoad || IsFPStore,
    funct3(1, 0), 2.U(2.W))
  // 原子结果在 LSU 内按 ISA 规则完成符号扩展，WBU 只负责写寄存器。
  val LoadSigned = Mux(IsLoad, (~funct3(2)).asBool, false.B)
  // 控制ysyx_26030103_WBU的，不知道该不该让ysyx_26030103_IDU来控制ysyx_26030103_WBU，本来想手搓一个控制模块的，结果模块多了，还分不清
  val WB_ALU = 0.U(2.W)
  val WB_MEMORY = 1.U(2.W)
  val WB_SNPC = 2.U(2.W)
  val WB_CSR = 3.U(2.W)
  val WBSelect = Wire(UInt(2.W))
  when(IsCSRInstruction) {
    WBSelect := WB_CSR // ysyx_26030103_CSR指令写回ysyx_26030103_CSR读出值
  }.elsewhen(IsLoad || IsAtomic || IsFPLoad) {
    WBSelect := WB_MEMORY // load/AMO/LR/SC/FLW/FLD 都写回 LSU 提供的结果
  }.elsewhen(
    (opcode === OPCODE_Immediate_Bxxx) && (funct3 === "b000".U(3.W))
  ) { // 间接跳转并链接（JALR）
    WBSelect := WB_SNPC
  }.elsewhen(IsJType) { // 跳转并链接（JAL）
    WBSelect := WB_SNPC
  }.otherwise { // 正常写回的普通指令
    WBSelect := WB_ALU
  }
  val ImmediateGeneratorModule = Module(new ysyx_26030103_ImmediateGenerator(XLEN))
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
  // System 实现完整的六条 Zicsr 指令以及 ecall/ebreak/mret/wfi；WFI
  // 作为无副作用 NOP 正常退休。MiscMem 中 fence/fence.i 分别处理。
  val IsKnownInstruction =
    IsRType || IsIType || IsSType || IsBType || IsUType || IsJType ||
      IsCSRInstruction || IsEcall || IsEbreak || IsSimHalt || IsMret ||
      IsSret || IsSfenceVma || IsWfi || IsFenceI || IsFence || IsAtomic ||
      IsFP
  // CSR 访问合法性在译码时检查，而不是依赖 CSRUnit 的读取数据多路选择器。
  // 本核心目前只实现下面明确列出的机器态 CSR 集合，访问集合外的 CSR
  // 将被视为非法指令。
  val CSRAddress = Instruction(31, 20)
  private val userCounterAddresses =
    Seq(0xc00, 0xc01, 0xc02, 0xc80, 0xc81, 0xc82)
  // F/D 的 fflags(0x001)/frm(0x002)/fcsr(0x003) 是 U 级可访问 CSR；
  // 是否实现由 RISCV_F 决定，不依赖 MMU profile 的整数 CSR 集合。
  private val fpCsrAddresses =
    if (UseF) Seq(0x001, 0x002, 0x003) else Seq.empty
  private val csrAddresses = (
    config.MMU.csrEnabled.toSeq ++ userCounterAddresses ++ fpCsrAddresses ++
      (if (config.MMU.pmpEnabled) (0x3a0 to 0x3a3) ++ (0x3b0 to 0x3bf) else Seq.empty)
  ).filter(a => config.MMU.csrSupported(a) || userCounterAddresses.contains(a) ||
    fpCsrAddresses.contains(a))
    .distinct.sorted
  val CSRAddressValid =
    if (config.MMU.csrMissingMode == "zero") true.B
    else csrAddresses.map(a => CSRAddress === a.U(12.W)).reduceOption(_ || _).getOrElse(false.B)
  // 地址位 [9:8] 编码最低可访问特权级：00=U, 01=S, 11=M。
  // HAS_U=n 时 U 级不再存在：防御性地把 0 级请求提升为 S，并显式拒绝
  // U 级 CSR，避免上游异常状态重新打开 U 模式访问路径。HAS_S=n 但
  // HAS_U=y（M+U）时仍按真实 U 特权级检查；S 级地址已由 csrSupported
  // 从 csrAddresses 裁剪，因此 U/M 都不会看到 S CSR。
  val EffectivePrivilege =
    if (!hasS && !hasU) 3.U(2.W)
    else if (hasU) io.Privilege
    else Mux(io.Privilege === 0.U, 1.U(2.W), io.Privilege)
  // fcsr/fflags/frm 是 U 级 CSR，但 M 模式始终可访问；M-only 配置不能
  // 因为不存在 U 级就把它们判成非法。
  val IsFpCsr = if (UseF)
    (CSRAddress === 0x001.U) || (CSRAddress === 0x002.U) ||
      (CSRAddress === 0x003.U)
  else false.B
  val CSRPrivilegeOk = IsFpCsr || (if (!hasS && !hasU)
    CSRAddress(9, 8) === "b11".U(2.W)
  else
    (EffectivePrivilege >= CSRAddress(9, 8)) &&
      (if (hasU) true.B else CSRAddress(9, 8) =/= 0.U(2.W)))
  val CSRReadOnly = CSRAddress(11, 10) === "b11".U(2.W) ||
    CSRAddress === "h301".U(12.W) // misa 当前只读 */
  // 读只读 CSR 合法；CSRRW/带非零源的 CSRRS/CSRRC 写只读 CSR 非法。
  val CSRWriteIntent = IsCsrrw || ((IsCsrrs || IsCsrrc) && (Rs1 =/= 0.U))
  val IsUserCounter = userCounterAddresses.map(a => CSRAddress === a.U(12.W))
    .reduceOption(_ || _).getOrElse(false.B)
  val CounterBit = CSRAddress(2, 0)
  val CounterAccessOk = !IsUserCounter || EffectivePrivilege === 3.U ||
    (io.Mcounteren(CounterBit) &&
      (if (hasS) EffectivePrivilege === 1.U || io.Scounteren(CounterBit)
       else true.B))
  val CSRIllegal =
    IsCSRInstruction &&
      (!CSRAddressValid || !CSRPrivilegeOk || !CounterAccessOk ||
        (CSRWriteIntent && CSRReadOnly))
  // mstatus.TVM/TSR/TW 的 S 模式陷门。HAS_S=n 时相关状态和陷门都不存在。
  val TvmFault =
    if (hasS)
      (EffectivePrivilege === 1.U) && io.Mstatus(20) &&
        (IsSfenceVma || (IsCSRInstruction && CSRAddress === "h180".U(12.W)))
    else false.B
  val TsrFault =
    if (hasS) (EffectivePrivilege === 1.U) && io.Mstatus(22) && IsSret
    else false.B
  val TwFault =
    if (hasS) (EffectivePrivilege =/= 3.U) && io.Mstatus(21) && IsWfi
    else false.B
  // 返回指令的固定特权级约束：MRET 只能在 M，SRET/SFENCE.VMA 只能在 S。
  // M+U 没有 S，U 若执行 MRET 必须作为非法指令陷入 M，而不能提权。
  val MretFault = IsMret && EffectivePrivilege =/= 3.U
  val SretFault =
    if (hasS) IsSret && EffectivePrivilege =/= 1.U else false.B
  // SFENCE.VMA 在 S/M 都可用，只禁止 U。
  val SfenceFault =
    if (hasS) IsSfenceVma && EffectivePrivilege === 0.U else false.B
  // custom-0 不属于通用 ALU decoder 的 opcode 集合；精确匹配的仿真
  // halt 编码在这里显式豁免，custom-0 的其它编码仍然是非法指令。
  val IllegalInsn =
    (ALUCDIllegal && !IsSimHalt) || !IsKnownInstruction || CSRIllegal ||
      TvmFault || TsrFault || TwFault ||
      MretFault || SretFault || SfenceFault ||
      RvcIllegal || CExtensionDisabledIllegal || FpIllegal
  // LR 的 rs2 字段必须为 0，但统一按 A 扩展读 rs1/rs2 不会引入错误转发，
  // 反而让 SC/AMO 的源操作数冒险检测与普通指令完全一致。
  // GPR rs2 只有整数寄存器型/分支/整数 store/AMO 使用；浮点 store 的
  // rs2 字段是 f 寄存器，不能触发 GPR 转发/冒险。
  val needsRs2 = IsRType || IsBType || IsSType || IsAtomic
  // 源操作数真正被使用的判断(lui/auipc/jal的rs1字段是立即数,不算使用)
  val usesRs1 = IsRType || IsIType || IsSType || IsBType || IsAtomic ||
    (IsCSRInstruction && !IsCSRImmediate) || FpGprSource1
  // 转发命中(EXU > MEM等待槽 > MEM级 > WBU: 多条同时命中时选最年轻生产者)
  // 显式检查生产者 valid：虽然顶层契约把 valid 折进了 *_fwd_ready
  // （EXU/LSU 的 FwdReady 含 HazardValid），这里保持与下方冒险谓词同口径，
  // 避免未来新增生产者端口只拉 ready 时静默转发陈旧数据。
  val ex_fwd_rs1 =
    usesRs1 && io.ex_valid && io.ex_fwd_ready && io.ex_rd =/= 0.U &&
      io.ex_rd === Rs1
  val ex_fwd_rs2 =
    io.ex_valid && io.ex_fwd_ready && io.ex_rd =/= 0.U && needsRs2 &&
      io.ex_rd === Rs2
  val me2_fwd_rs1 =
    usesRs1 && io.me2_valid && io.me2_fwd_ready && io.me2_rd =/= 0.U &&
      io.me2_rd === Rs1
  val me2_fwd_rs2 =
    io.me2_valid && io.me2_fwd_ready && io.me2_rd =/= 0.U && needsRs2 &&
      io.me2_rd === Rs2
  val me_fwd_rs1 =
    usesRs1 && io.me_valid && io.me_fwd_ready && io.me_rd =/= 0.U &&
      io.me_rd === Rs1
  val me_fwd_rs2 =
    io.me_valid && io.me_fwd_ready && io.me_rd =/= 0.U && needsRs2 &&
      io.me_rd === Rs2
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
  // ---------------------------------------------------------------- FPR 读/转发
  // 三个 f 源操作数与 GPR 使用同一优先级：EXU > MEM 等待槽 > MEM > WBU。
  // f0 是普通寄存器，不做零硬连线，因此这里不排除 rd=0。
  val ex_fp_fwd_rs1 = FpUsesFpr1 && io.ex_fp_valid && io.ex_fp_regWrite &&
    io.ex_fp_rd === Rs1 && io.ex_fp_fwd_ready
  val ex_fp_fwd_rs2 = FpUsesFpr2 && io.ex_fp_valid && io.ex_fp_regWrite &&
    io.ex_fp_rd === Rs2 && io.ex_fp_fwd_ready
  val ex_fp_fwd_rs3 = (FpUsesFpr3 && io.ex_fp_valid && io.ex_fp_regWrite &&
    io.ex_fp_rd === Instruction(31, 27) && io.ex_fp_fwd_ready)
  val me2_fp_fwd_rs1 = FpUsesFpr1 && io.me2_fp_valid && io.me2_fp_regWrite &&
    io.me2_fp_rd === Rs1 && io.me2_fp_fwd_ready
  val me2_fp_fwd_rs2 = FpUsesFpr2 && io.me2_fp_valid && io.me2_fp_regWrite &&
    io.me2_fp_rd === Rs2 && io.me2_fp_fwd_ready
  val me2_fp_fwd_rs3 = (FpUsesFpr3 && io.me2_fp_valid && io.me2_fp_regWrite &&
    io.me2_fp_rd === Instruction(31, 27) && io.me2_fp_fwd_ready)
  val me_fp_fwd_rs1 = FpUsesFpr1 && io.me_fp_valid && io.me_fp_regWrite &&
    io.me_fp_rd === Rs1 && io.me_fp_fwd_ready
  val me_fp_fwd_rs2 = FpUsesFpr2 && io.me_fp_valid && io.me_fp_regWrite &&
    io.me_fp_rd === Rs2 && io.me_fp_fwd_ready
  val me_fp_fwd_rs3 = (FpUsesFpr3 && io.me_fp_valid && io.me_fp_regWrite &&
    io.me_fp_rd === Instruction(31, 27) && io.me_fp_fwd_ready)
  val wb_fp_fwd_rs1 = FpUsesFpr1 && io.wb_fp_valid && io.wb_fp_regWrite &&
    io.wb_fp_rd === Rs1
  val wb_fp_fwd_rs2 = FpUsesFpr2 && io.wb_fp_valid && io.wb_fp_regWrite &&
    io.wb_fp_rd === Rs2
  val wb_fp_fwd_rs3 = FpUsesFpr3 && io.wb_fp_valid && io.wb_fp_regWrite &&
    io.wb_fp_rd === Instruction(31, 27)
  val fp_src1 = Mux(ex_fp_fwd_rs1, io.ex_fp_fwd_data,
    Mux(me2_fp_fwd_rs1, io.me2_fp_fwd_data,
      Mux(me_fp_fwd_rs1, io.me_fp_fwd_data,
        Mux(wb_fp_fwd_rs1, io.wb_fp_fwd_data, io.ReadFPR1))))
  val fp_src2 = Mux(ex_fp_fwd_rs2, io.ex_fp_fwd_data,
    Mux(me2_fp_fwd_rs2, io.me2_fp_fwd_data,
      Mux(me_fp_fwd_rs2, io.me_fp_fwd_data,
        Mux(wb_fp_fwd_rs2, io.wb_fp_fwd_data, io.ReadFPR2))))
  val fp_src3 = Mux(ex_fp_fwd_rs3, io.ex_fp_fwd_data,
    Mux(me2_fp_fwd_rs3, io.me2_fp_fwd_data,
      Mux(me_fp_fwd_rs3, io.me_fp_fwd_data,
        Mux(wb_fp_fwd_rs3, io.wb_fp_fwd_data, io.ReadFPR3))))

  val fp_ex_hazard =
    io.ex_fp_valid && io.ex_fp_regWrite &&
      ((FpUsesFpr1 && io.ex_fp_rd === Rs1) ||
        (FpUsesFpr2 && io.ex_fp_rd === Rs2) ||
        (FpUsesFpr3 && io.ex_fp_rd === Instruction(31, 27)))
  val fp_me2_hazard =
    io.me2_fp_valid && io.me2_fp_regWrite &&
      ((FpUsesFpr1 && io.me2_fp_rd === Rs1) ||
        (FpUsesFpr2 && io.me2_fp_rd === Rs2) ||
        (FpUsesFpr3 && io.me2_fp_rd === Instruction(31, 27)))
  val fp_me_hazard =
    io.me_fp_valid && io.me_fp_regWrite &&
      ((FpUsesFpr1 && io.me_fp_rd === Rs1) ||
        (FpUsesFpr2 && io.me_fp_rd === Rs2) ||
        (FpUsesFpr3 && io.me_fp_rd === Instruction(31, 27)))
  // 只有生产者的值尚未就绪时才阻塞（例如未完成的 FLW/FLD）。
  val fp_isRAW = Mux(io.pipeline_mode,
    (fp_ex_hazard && !io.ex_fp_fwd_ready) ||
      (fp_me2_hazard && !io.me2_fp_fwd_ready) ||
      (fp_me_hazard && !io.me_fp_fwd_ready),
    false.B)
  // FCVT.S.W/L 与 FMV.W.X/D.X 的整数源来自 GPR rs1，而不是 f 寄存器；
  // 其余浮点运算的三个源操作数都来自 FPR。RV32 的 src1 零扩展即可，
  // RV64 的 L/D 形式需要完整 64 位。
  val StoreDataSource = Mux(IsFPStore, fp_src2, src2)
  val FpRs1Source = Mux(FpGprSource1, src1, fp_src1)

  val ALU_A = WireDefault(src1) // 默认所有指令的第一个计算的数是寄存器值
  switch(opcode) {
    is(OPCODE_UpperImmediate_lui) {
      ALU_A := 0.U(XLEN.W)
    }
    is(OPCODE_UpperImmediate_auipc, OPCODE_Jump) {
      ALU_A := pc
    }
  }
  val ALU_B = Mux((opcode === OPCODE_Register) || (opcode === OPCODE_RegisterWord), src2, Immediate)
  val ex_hazard = io.ex_valid && io.ex_regWrite && io.ex_rd =/= 0.U &&
    ((usesRs1 && io.ex_rd === Rs1) || (needsRs2 && io.ex_rd === Rs2))
  val me2_hazard = io.me2_valid && io.me2_regWrite && io.me2_rd =/= 0.U &&
    ((usesRs1 && io.me2_rd === Rs1) || (needsRs2 && io.me2_rd === Rs2))
  val me_hazard = io.me_valid && io.me_regWrite && io.me_rd =/= 0.U &&
    ((usesRs1 && io.me_rd === Rs1) || (needsRs2 && io.me_rd === Rs2))
  // 普通 EX 冒险端口只表示最老的 MDU 指令。队列中更年轻的 MDU 生产者
  // 尚无可供前递的值。
  val hidden_mdu_hazard =
    if (config.UseM) {
      (usesRs1 && Rs1 =/= 0.U && io.ex_mdu_hidden_writes(Rs1)) ||
        (needsRs2 && Rs2 =/= 0.U && io.ex_mdu_hidden_writes(Rs2))
    } else {
      false.B
    }
  // 只有生产者的数据未就绪(load未完成)才需要阻塞,其余全部转发
  val isRAW = Mux(
    io.pipeline_mode,
    (ex_hazard && !io.ex_fwd_ready) || (me2_hazard && !io.me2_fwd_ready) ||
      (me_hazard && !io.me_fwd_ready) || hidden_mdu_hazard,
    false.B
  )
  val AnyIsRAW = isRAW || fp_isRAW
  io.in.ready := io.out.ready && !AnyIsRAW && !io.mmu_stall
  io.out.valid := io.in.valid && !AnyIsRAW && !io.mmu_stall
  // 停顿分类口径：load-use 只统计“确实由未就绪的访存生产者造成”的停顿。
  // me 端口的 load 可能已经就绪（StageDone 或 R 响应当拍），此时它只是被
  // 隐藏 MDU 写掩码顺带命中，不能算作 load-use。ex/me2 上的 load 恒未就绪，
  // 加上 !fwd_ready 只是保持口径一致。
  val raw_loaduse =
    (ex_hazard && io.ex_memop && !io.ex_fwd_ready) ||
      (me2_hazard && io.me2_memop && !io.me2_fwd_ready) ||
      (me_hazard && io.me_memop && !io.me_fwd_ready) ||
      (fp_ex_hazard && io.ex_memop && !io.ex_fp_fwd_ready) ||
      (fp_me2_hazard && io.me2_memop && !io.me2_fp_fwd_ready) ||
      (fp_me_hazard && io.me_memop && !io.me_fp_fwd_ready)
  // 注意：raw_alu 是“非 load-use 的 RAW 停顿”桶，包含 MDU 队首未就绪、
  // MDU 隐藏写掩码等非访存生产者造成的停顿（ALU 生产者恒就绪，本身不产生
  // 停顿），命名沿用历史。
  io.perf_stall_raw := io.in.valid && AnyIsRAW
  io.perf_stall_raw_loaduse := io.in.valid && AnyIsRAW && raw_loaduse
  io.perf_stall_raw_alu := io.in.valid && AnyIsRAW && !raw_loaduse
  // 退休/trace/mtval 使用原始编码；展开结果只影响控制信号。
  io.out.bits.Instruction := RawInstruction
  io.out.bits.IsCompressed := IsCompressed
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
  // 写就直接上rs2第二个寄存器；浮点 store 使用 f 寄存器读口。
  io.out.bits.StoreData := StoreDataSource
  io.out.bits.IsAtomic := IsAtomic
  io.out.bits.AtomicOp := AtomicOp
  io.out.bits.IsCsrrw := IsCsrrw
  io.out.bits.IsCsrrs := IsCsrrs
  io.out.bits.IsCsrrc := IsCsrrc
  io.out.bits.IsEcall := IsEcall
  io.out.bits.IsEbreak := IsEbreak
  io.out.bits.IsSimHalt := IsSimHalt
  io.out.bits.IsMret := IsMret
  io.out.bits.IsSret := IsSret
  io.out.bits.IsSfenceVma := IsSfenceVma
  io.out.bits.IsFence := IsFence
  io.out.bits.IsFenceI := IsFenceI
  io.out.bits.IsFP := IsFP
  io.out.bits.IsFPU := FpIsFpu
  io.out.bits.FpOp := FpOp
  io.out.bits.FpFmt := FpFmt
  io.out.bits.FpSrcFmt := FpSrcFmt
  io.out.bits.FpRm := FpRm
  io.out.bits.FpIntWord := FpIntWord
  io.out.bits.FpRs1 := FpRs1Source.pad(64)
  io.out.bits.FpRs2 := fp_src2.pad(64)
  io.out.bits.FpRs3 := fp_src3.pad(64)
  io.out.bits.FPRegisterWrite := FpWritesFPR
  io.out.bits.FPWriteData := 0.U(64.W)
  io.out.bits.FP64 := FpUseD
  io.out.bits.CSRAddress := CSRAddress
  io.out.bits.Rs1 := Rs1
  io.out.bits.Rs1Data := Mux(IsCSRImmediate, Cat(0.U((XLEN - 5).W), Rs1), src1)
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
