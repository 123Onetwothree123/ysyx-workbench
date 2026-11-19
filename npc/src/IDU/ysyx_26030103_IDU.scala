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
  private val useF = config.UseF
  private val useD = config.UseD
  private val pmuZihpm = config.RiscvArchPMU && config.RiscvArchPMUZihpm
  private val pmuSscofpmf = pmuZihpm && config.RiscvArchPMUSscofpmf
  private val pmuCounterCount =
    _root_.ysyx_26030103.common.ysyx_26030103_PMUEventID.CounterCount
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
    val ExFPValid = Input(Bool())
    val ExFPRd = Input(UInt(5.W))
    val ExFPRegWrite = Input(Bool())
    val ExFPForwardReady = Input(Bool())
    val ExFPForwardData = Input(UInt(64.W))
    val MeFPValid = Input(Bool())
    val MeFPRd = Input(UInt(5.W))
    val MeFPRegWrite = Input(Bool())
    val MeFPForwardReady = Input(Bool())
    val MeFPForwardData = Input(UInt(64.W))
    val Me2FPValid = Input(Bool())
    val Me2FPRd = Input(UInt(5.W))
    val Me2FPRegWrite = Input(Bool())
    val Me2FPForwardReady = Input(Bool())
    val Me2FPForwardData = Input(UInt(64.W))
    val WbFPValid = Input(Bool())
    val WbFPRd = Input(UInt(5.W))
    val WbFPRegWrite = Input(Bool())
    val WbFPForwardData = Input(UInt(64.W))
    val ExValid = Input(Bool())
    val ExRd = Input(UInt(5.W))
    val ExRegWrite = Input(Bool())
    val WbValid = Input(Bool())
    val WbRd = Input(UInt(5.W))
    val WbRegWrite = Input(Bool())
    val ExMemop = Input(Bool())
    // 转发: EX阶段的最终写回值及其就绪标志, WB阶段的写回值(总是就绪)
    val ExForwardReady = Input(Bool())
    val ExForwardData = Input(UInt(XLEN.W))
    val ExMDUHiddenWrites = Input(UInt(32.W))
    val WbForwardData = Input(UInt(XLEN.W))
    // MEM级(MEM)的冒险检测与转发
    val MeValid = Input(Bool())
    val MeRd = Input(UInt(5.W))
    val MeRegWrite = Input(Bool())
    val MeMemop = Input(Bool())
    val MeForwardReady = Input(Bool())
    val MeForwardData = Input(UInt(XLEN.W))
    // MEM级等待槽(EX/MEM流水寄存器里等待的指令,比MEM级年轻)
    val Me2Valid = Input(Bool())
    val Me2Rd = Input(UInt(5.W))
    val Me2RegWrite = Input(Bool())
    val Me2Memop = Input(Bool())
    val Me2ForwardReady = Input(Bool())
    val Me2ForwardData = Input(UInt(XLEN.W))
    val PipelineMode = Input(Bool())
    // DMMU 正在翻译一条访存指令时阻塞 IDU，避免后续消费者带着陈旧寄存器值
    // 越过尚未进入 LSU 的 load/store。
    val MMUStall = Input(Bool())
    val Privilege = Input(UInt(2.W))
    val Mstatus = Input(UInt(XLEN.W))
    val Mcounteren = Input(UInt(32.W))
    val Scounteren = Input(UInt(32.W))
    val perf_stall_raw = Output(Bool())
    val perf_stall_raw_loaduse = Output(Bool())
    val perf_stall_raw_alu = Output(Bool())
    val PerfWfi = Output(Bool())
  })
  // 保留 perf 端口，供叶子 Verilator 探针独立实例化。
  dontTouch(io.perf_stall_raw)
  dontTouch(io.perf_stall_raw_loaduse)
  dontTouch(io.perf_stall_raw_alu)
  // 取指消息保留原始编码；RVC 展开后的 32 位形式只用于本模块译码。
  // 这样 EXU/trace/非法指令 mtval 仍能看到真实的 16 位编码。
  val rawInstruction = io.in.bits.Instruction
  val isCompressed = io.in.bits.IsCompressed
  val instruction = WireDefault(rawInstruction)
  val rvcIllegal = WireDefault(false.B)
  if (config.UseC) {
    val rvcDecoderModule =
      Module(new ysyx_26030103_RVCDecoder(XLEN, useF, useD))
    rvcDecoderModule.io.in := rawInstruction
    when(isCompressed) {
      instruction := rvcDecoderModule.io.out
    }
    rvcIllegal := isCompressed && rvcDecoderModule.io.illegal
  }
  // C 扩展关闭时，任何低两位不是 11 的取指槽都是压缩指令，必须明确
  // 按非法指令陷入，不能把相邻的半字误拼成一条 32 位指令执行。
  val cExtensionDisabledIllegal =
    if (config.UseC) false.B
    else rawInstruction(1, 0) =/= "b11".U(2.W)
  val PC = io.in.bits.PC
  // 压缩指令只前进 2 字节；这也是分支/JAL 链接值和 trap 返回地址的
  // 顺序后继语义。
  val snpc = PC + Mux(isCompressed, 2.U(XLEN.W), 4.U(XLEN.W))
  val rs1 = instruction(19, 15)
  val rs2 = instruction(24, 20)
  io.Read1SELECT := rs1
  io.Read2SELECT := rs2
  // 浮点读口：rs1/rs2/rs3 都是 5 位字段；无效时读取结果不会被使用。
  io.Read1FSELECT := rs1
  io.Read2FSELECT := rs2
  io.Read3FSELECT := instruction(31, 27)
  val rd = instruction(11, 7)
  val opcode = instruction(6, 0)
  val funct3 = instruction(14, 12)
  val funct7 = instruction(31, 25)
  // OP-32 在 RV64 下复用同一套 R 型/立即数数据通路；RV32 时这些 opcode
  // 会被 ALUDecoder 判为非法指令。
  val isRType =
    (opcode === OPCODE_Register) || (opcode === OPCODE_RegisterWord)
  val isIType =
    (opcode === OPCODE_Immediate) || (opcode === OPCODE_ImmediateWord) ||
      (opcode === OPCODE_Immediate_Lxxx) || (opcode === OPCODE_Immediate_Bxxx)
  val isSType = (opcode === OPCODE_Store)
  val isBType = (opcode === OPCODE_Branch)
  val isUType =
    (opcode === OPCODE_UpperImmediate_lui) || (opcode === OPCODE_UpperImmediate_auipc)
  val isJType = (opcode === OPCODE_Jump)
  val isLoad = (opcode === OPCODE_Immediate_Lxxx)
  val isSystem = (opcode === OPCODE_System)
  // 两个中间变量，他妈了个逼的，Verilog的wire既能读还能写，chisel3的输出端口还他妈的只能写不能读，还得搞中间变量
  // Zicsr 的寄存器型和立即数型编码只在 funct3[2] 上有区别；低两位
  // 分别选择 RW/RS/RC。funct3=000 仍留给 ECALL/EBREAK/MRET。
  val IsCSRInstruction = isSystem && funct3(1, 0) =/= 0.U
  val IsCSRImmediate = IsCSRInstruction && funct3(2)
  val isCSRrw = IsCSRInstruction && funct3(1, 0) === "b01".U(2.W)
  val isCSRrs = IsCSRInstruction && funct3(1, 0) === "b10".U(2.W)
  val isCSRrc = IsCSRInstruction && funct3(1, 0) === "b11".U(2.W)
  val isEbreak = instruction === "h00100073".U(32.W)
  val isEcall = instruction === "h00000073".U(32.W)
  val isMret = instruction === "h30200073".U(32.W)
  // HAS_S=n 时不存在 S 状态，SRET/SFENCE.VMA 连译码匹配都不生成，
  // 直接落入非法指令路径。
  val isSret =
    if (hasS) instruction === "h10200073".U(32.W) else false.B
  // sfence.vma 编码：funct7=0001001, funct3=000, rd=0, SYSTEM。
  // rs1/rs2 的取值由软件决定，MMU 侧当前统一做全量 TLB 刷新。
  val isSfenceVma =
    if (hasS)
      (opcode === OPCODE_System) && (funct7 === "b0001001".U(7.W)) &&
        (funct3 === "b000".U(3.W)) && (instruction(11, 7) === 0.U)
    else false.B
  // 本核仅实现 M-mode，可将 WFI 合法地实现为 NOP。必须精确匹配整个
  // 标准编码，避免把其它 funct3=000 的保留 SYSTEM 编码一并放行。
  val isWfi = instruction === "h10500073".U(32.W)
  // custom-0 编码只用于仿真器的 AM halt ABI。标准 EBREAK 始终保留其
  // breakpoint 异常语义，不能再被宿主机无条件当成退出请求。
  val isSimHalt = instruction === "h0000000b".U(32.W)
  // FENCE 和 FENCE.I 都属于 MISC-MEM 操作码类别，但在下游具有不同的
  // 顺序约束和冲刷语义。
  // 基础实现可以保守地把所有fm/pred/succ配置都执行成完整FENCE。
  // 为向前兼容，保留的fm编码以及rd/rs1都必须被忽略；FENCE.TSO也可
  // 合法地退化为更强的FENCE RW,RW，不能因fm非零报非法指令。
  val isFence =
    (opcode === OPCODE_MiscMem) && (funct3 === "b000".U(3.W))
  val isFenceI = (opcode === OPCODE_MiscMem) && (funct3 === "b001".U(3.W))
  // A 扩展：funct3=010 为 .W，RV64 另外允许 .D(funct3=011)。
  // 其余 funct3 以及 RISCV_A=n 时整个 AMO 操作码都按非法指令处理。
  val amoFunct5 = instruction(31, 27)
  // 只放行标准定义的 11 个 funct5；其余保留编码必须落入非法指令。
  val amoFunct5Valid = Seq(
    ysyx_26030103_AtomicOp.ADD, ysyx_26030103_AtomicOp.SWAP,
    ysyx_26030103_AtomicOp.LR, ysyx_26030103_AtomicOp.SC,
    ysyx_26030103_AtomicOp.XOR, ysyx_26030103_AtomicOp.OR,
    ysyx_26030103_AtomicOp.AND, ysyx_26030103_AtomicOp.MIN,
    ysyx_26030103_AtomicOp.MAX, ysyx_26030103_AtomicOp.MINU,
    ysyx_26030103_AtomicOp.MAXU
  ).map(amoFunct5 === _).reduce(_ || _)
  // LR 的编码把 rs2 固定为 0；rs2 非零属于保留编码，必须按非法指令
  // 陷入（NEMU REF 的 lr_w/lr_d 模式也只匹配 rs2=00000）。
  val atomicRs2Legal =
    amoFunct5 =/= ysyx_26030103_AtomicOp.LR || rs2 === 0.U
  val isAtomic =
    if (config.UseA)
      (opcode === OPCODE_Amo) && amoFunct5Valid && atomicRs2Legal &&
        ((funct3 === "b010".U(3.W)) ||
          (if (XLEN == 64) funct3 === "b011".U(3.W) else false.B))
    else false.B
  // funct5 直接作为原子操作号（00010=LR，00011=SC，其余为 AMO）。
  val atomicOp = Mux(isAtomic, amoFunct5, 0.U(5.W))

  // F/D 译码
  // 所有 F/D 编码在这里集中解析；vendor 内部操作码不会进入上层。
  val FPLoadOpcode = opcode === OPCODE_LoadFP
  val FPStoreOpcode = opcode === OPCODE_StoreFP
  val FPOpcode = opcode === OPCODE_OpFP
  val FPFmaOpcode = (opcode === OPCODE_Fmadd) || (opcode === OPCODE_Fmsub) ||
    (opcode === OPCODE_Fnmsub) || (opcode === OPCODE_Fnmadd)
  val FPMemOpcode = FPLoadOpcode || FPStoreOpcode
  // FLW=010, FLD=011 / FSW=010, FSD=011。
  val FPMemFunct3Legal = funct3 === "b010".U(3.W) ||
    (useD.B && funct3 === "b011".U(3.W))
  // 格式：load/store 由 funct3 bit0 选择，OP-FP/FMA 由指令 bit[26:25] 选择。
  val FPFmt = Mux(FPMemOpcode, Mux(funct3(0), ysyx_26030103_FPUFmt.D,
    ysyx_26030103_FPUFmt.S), instruction(26, 25))
  val FPUseD = FPFmt === ysyx_26030103_FPUFmt.D
  val FPOp5 = funct7(6, 2)
  val FPRs2 = instruction(24, 20)
  val IsFPLoad = useF.B && FPLoadOpcode && FPMemFunct3Legal
  val IsFPStore = useF.B && FPStoreOpcode && FPMemFunct3Legal
  val IsFPOp = useF.B && FPOpcode
  val IsFPFma = useF.B && FPFmaOpcode
  val IsFP = IsFPLoad || IsFPStore || IsFPOp || IsFPFma
  val FPIsFPU = IsFPOp || IsFPFma
  // 使用 rm 的编码：FADD/FSUB/FMUL/FDIV/FSQRT/FCVT/FMA。
  val FPUsesRm = IsFPFma || (IsFPOp && (
    (FPOp5 === 0.U) || (FPOp5 === 1.U) || (FPOp5 === 2.U) ||
      (FPOp5 === 3.U) || (FPOp5 === 11.U) || (FPOp5 === 8.U) ||
      (FPOp5 === 24.U) || (FPOp5 === 26.U)))
  val FPRmRaw = Mux(FPUsesRm, funct3, 0.U(3.W))
  val FPRm = Mux(FPRmRaw === ysyx_26030103_FPURm.DYN, io.Frm, FPRmRaw)
  // FCVT.W/WU/L/LU 的宽度由 rs2[1] 选择；FMV.X.W/D 与 FMV.W.X/D.X
  // 的宽度由 fmt 决定（rs2 恒为 0）。
  val FPMvOp = IsFPOp && ((FPOp5 === 28.U) || (FPOp5 === 30.U))
  val FPIntWord = Mux(FPMvOp, FPFmt === ysyx_26030103_FPUFmt.S,
    FPRs2(1) === 0.U)
  val FPIntSigned = FPRs2(0) === 0.U
  // FCVT.S.D 的源是 D，FCVT.D.S 的源是 S；其余指令源/目的同格式。
  val FPSourceFmt = Mux(IsFPOp && FPOp5 === 8.U,
    Mux(FPFmt === ysyx_26030103_FPUFmt.S, ysyx_26030103_FPUFmt.D,
      ysyx_26030103_FPUFmt.S), FPFmt)

  // 编码合法性。未识别/保留编码必须精确落入 illegal instruction。
  val FPFmtLegal = (FPFmt === ysyx_26030103_FPUFmt.S) ||
    (useD.B && FPFmt === ysyx_26030103_FPUFmt.D)
  // FCVT.S.D 的源格式是 D：D 扩展关闭时即使目的格式是 S 也必须陷入。
  // FCVT.D.S 由目的格式 D 的 FpFmtLegal 拒绝，不走这里。
  val FPSourceFmtIllegal = IsFPOp && FPSourceFmt === ysyx_26030103_FPUFmt.D &&
    !useD.B
  // FMV.X.D / FMV.D.X 只在 RV64D 中定义；RV32 的 64 位浮点寄存器无法
  // 与 32 位整数寄存器整字搬移。FCLASS.D 是 funct3=1，不受影响。
  val FPMvDoubleIllegal = (if (XLEN == 32) {
    IsFPOp && FPFmt === ysyx_26030103_FPUFmt.D && funct3 === 0.U &&
      (FPOp5 === 28.U || FPOp5 === 30.U)
  } else false.B)
  val FPMemEncodingLegal =
    !FPMemOpcode || (funct3 === "b010".U(3.W) ||
      (useD.B && funct3 === "b011".U(3.W)))
  val FPOp5Legal = !IsFPOp || (
    (FPOp5 === 0.U) || (FPOp5 === 1.U) || (FPOp5 === 2.U) ||
      (FPOp5 === 3.U) || (FPOp5 === 4.U) || (FPOp5 === 5.U) ||
      (FPOp5 === 11.U) || (FPOp5 === 8.U) || (FPOp5 === 20.U) ||
      (FPOp5 === 24.U) || (FPOp5 === 26.U) || (FPOp5 === 28.U) ||
      (FPOp5 === 30.U))
  val FPFunct3Legal = !IsFPOp || (
    (FPOp5 =/= 4.U || (funct3 === 0.U || funct3 === 1.U || funct3 === 2.U)) &&
      (FPOp5 =/= 5.U || (funct3 === 0.U || funct3 === 1.U)) &&
      (FPOp5 =/= 20.U || (funct3 === 0.U || funct3 === 1.U ||
        funct3 === 2.U)) &&
      (FPOp5 =/= 28.U || (funct3 === 0.U || funct3 === 1.U)) &&
      (FPOp5 =/= 30.U || funct3 === 0.U))
  val FPRs2Legal = !IsFPOp || MuxCase(false.B, Seq(
    (FPOp5 === 0.U) -> true.B,
    (FPOp5 === 1.U) -> true.B,
    (FPOp5 === 2.U) -> true.B,
    (FPOp5 === 3.U) -> true.B,
    (FPOp5 === 4.U) -> true.B,
    (FPOp5 === 5.U) -> true.B,
    (FPOp5 === 11.U) -> (FPRs2 === 0.U),
    // FCVT.S.D: fmt=S, rs2=1；FCVT.D.S: fmt=D, rs2=0。
    (FPOp5 === 8.U) -> (FPRs2 === Mux(FPFmt === ysyx_26030103_FPUFmt.S,
      1.U, 0.U)),
    (FPOp5 === 20.U) -> true.B,
    (FPOp5 === 24.U) -> (FPRs2 <= 3.U && (if (XLEN == 64) true.B else FPRs2 <= 1.U)),
    (FPOp5 === 26.U) -> (FPRs2 <= 3.U && (if (XLEN == 64) true.B else FPRs2 <= 1.U)),
    (FPOp5 === 28.U) -> (FPRs2 === 0.U),
    (FPOp5 === 30.U) -> (FPRs2 === 0.U)
  ))
  val FPRmLegal = !FPUsesRm ||
    (FPRmRaw <= 4.U) || (FPRmRaw === ysyx_26030103_FPURm.DYN && io.Frm <= 4.U)
  // mstatus.FS=Off 时任何 F/D 指令都是非法指令；fflags/frm/fcsr 属于
  // 浮点状态，访问它们同样必须陷入，不能只拦截算术/访存指令。
  val FPStateEnabled = !useF.B || io.Mstatus(14, 13) =/= 0.U(2.W)
  val FPCSRIllegal = IsCSRInstruction && useF.B &&
    (instruction(31, 20) === 1.U || instruction(31, 20) === 2.U ||
      instruction(31, 20) === 3.U) && !FPStateEnabled
  val FPIllegal = FPCSRIllegal || (IsFP && (!FPFmtLegal || !FPMemEncodingLegal ||
    !FPOp5Legal || !FPFunct3Legal || !FPRs2Legal ||
    !FPRmLegal || !FPStateEnabled || FPSourceFmtIllegal || FPMvDoubleIllegal))

  // 指令 -> 后端无关的 FpuOp。
  val FPOp = WireDefault(0.U(ysyx_26030103_FPUOp.Width.W))
  when(IsFPFma) {
    FPOp := Mux(opcode === OPCODE_Fmadd, ysyx_26030103_FPUOp.FFma,
      Mux(opcode === OPCODE_Fmsub, ysyx_26030103_FPUOp.FFms,
        Mux(opcode === OPCODE_Fnmsub, ysyx_26030103_FPUOp.FFnms,
          ysyx_26030103_FPUOp.FFnma)))
  }.elsewhen(IsFPOp) {
    switch(FPOp5) {
      is(0.U) { FPOp := ysyx_26030103_FPUOp.FAdd }
      is(1.U) { FPOp := ysyx_26030103_FPUOp.FSub }
      is(2.U) { FPOp := ysyx_26030103_FPUOp.FMul }
      is(3.U) { FPOp := ysyx_26030103_FPUOp.FDiv }
      is(4.U) {
        FPOp := Mux(funct3 === 0.U, ysyx_26030103_FPUOp.FSgnj,
          Mux(funct3 === 1.U, ysyx_26030103_FPUOp.FSgnjn,
            ysyx_26030103_FPUOp.FSgnjx))
      }
      is(5.U) {
        FPOp := Mux(funct3 === 0.U, ysyx_26030103_FPUOp.FMin,
          ysyx_26030103_FPUOp.FMax)
      }
      is(11.U) { FPOp := ysyx_26030103_FPUOp.FSqrt }
      is(8.U) { FPOp := ysyx_26030103_FPUOp.FCvtFPToFP }
      is(20.U) {
        FPOp := Mux(funct3 === 2.U, ysyx_26030103_FPUOp.FEq,
          Mux(funct3 === 1.U, ysyx_26030103_FPUOp.FLt,
            ysyx_26030103_FPUOp.FLe))
      }
      is(24.U) {
        FPOp := Mux(FPIntSigned, ysyx_26030103_FPUOp.FCvtFPToInt,
          ysyx_26030103_FPUOp.FCvtFPToUInt)
      }
      is(26.U) {
        FPOp := Mux(FPIntSigned, ysyx_26030103_FPUOp.FCvtIntToFP,
          ysyx_26030103_FPUOp.FCvtUIntToFP)
      }
      is(28.U) {
        FPOp := Mux(funct3 === 1.U, ysyx_26030103_FPUOp.FClass,
          ysyx_26030103_FPUOp.FMvFPToInt)
      }
      is(30.U) { FPOp := ysyx_26030103_FPUOp.FMvIntToFP }
    }
  }
  // 源寄存器语义。
  val FPGPRSource1 = IsFPLoad || IsFPStore ||
    (IsFPOp && (FPOp5 === 26.U || FPOp5 === 30.U))
  val FPUses1 = IsFP && !FPGPRSource1
  val FPUses2 = IsFPStore || IsFPFma || (IsFPOp &&
    ((FPOp5 === 0.U) || (FPOp5 === 1.U) || (FPOp5 === 2.U) ||
      (FPOp5 === 3.U) || (FPOp5 === 4.U) || (FPOp5 === 5.U) ||
      (FPOp5 === 20.U)))
  val FPUses3 = IsFPFma
  // 写回目标。浮点 load/算术写 f 寄存器；比较/转换到整数/FCLASS 写 GPR。
  val FPWritesFPR = IsFPLoad || IsFPFma || (IsFPOp && (
    (FPOp5 === 0.U) || (FPOp5 === 1.U) || (FPOp5 === 2.U) ||
      (FPOp5 === 3.U) || (FPOp5 === 4.U) || (FPOp5 === 5.U) ||
      (FPOp5 === 8.U) || (FPOp5 === 11.U) || (FPOp5 === 26.U) ||
      (FPOp5 === 30.U)))
  val FPWritesGPR = IsFPOp && ((FPOp5 === 20.U) || (FPOp5 === 24.U) ||
    (FPOp5 === 28.U))

  val registerWrite =
    isRType || isIType || isUType || isJType || IsCSRInstruction || isAtomic ||
      FPWritesGPR
  val memoryValid = isLoad || isSType || isAtomic || IsFPLoad || IsFPStore
  // AMO 同时需要读/写权限，SC 只需要写权限；LR 只读。
  val memoryWrite = isSType ||
    (isAtomic && ysyx_26030103_AtomicOp.Writes(atomicOp)) || IsFPStore
  /*
  又忘记了为什么这样做，就是想要减少代码量，主要是代码量多是负担，就是只看末尾的两位，我怀疑可能是RISCV故意这么设计的，也可以简化
  000 LB和SB 1byte
  001 LH和SH 2byte
  010 LW和SW 4byte
  U是没有符号的意思
  100 LBU 1byte（没有符号）
  101 LHU 2byte（没有符号）
   */
  val widthSelect = Mux(isLoad || isSType || isAtomic || IsFPLoad || IsFPStore,
    funct3(1, 0), 2.U(2.W))
  // 原子结果在 LSU 内按 ISA 规则完成符号扩展，WBU 只负责写寄存器。
  val loadSigned = Mux(isLoad, (~funct3(2)).asBool, false.B)
  // 控制ysyx_26030103_WBU的，不知道该不该让ysyx_26030103_IDU来控制ysyx_26030103_WBU，本来想手搓一个控制模块的，结果模块多了，还分不清
  val WbALU = 0.U(2.W)
  val wbMemory = 1.U(2.W)
  val wbSnpc = 2.U(2.W)
  val WbCSR = 3.U(2.W)
  val wbSelect = Wire(UInt(2.W))
  when(IsCSRInstruction) {
    wbSelect := WbCSR // ysyx_26030103_CSR指令写回ysyx_26030103_CSR读出值
  }.elsewhen(isLoad || isAtomic || IsFPLoad) {
    wbSelect := wbMemory // load/AMO/LR/SC/FLW/FLD 都写回 LSU 提供的结果
  }.elsewhen(
    (opcode === OPCODE_Immediate_Bxxx) && (funct3 === "b000".U(3.W))
  ) { // 间接跳转并链接（JALR）
    wbSelect := wbSnpc
  }.elsewhen(isJType) { // 跳转并链接（JAL）
    wbSelect := wbSnpc
  }.otherwise { // 正常写回的普通指令
    wbSelect := WbALU
  }
  val immediateGeneratorModule = Module(new ysyx_26030103_ImmediateGenerator(XLEN))
  immediateGeneratorModule.io.Instruction := instruction
  val immediate = immediateGeneratorModule.io.Immediate
  // ALU 控制解码已经合并为一个模块，直接使用 opcode/funct3/funct7 完成译码。
  val ALUDecoderModule = Module(new ysyx_26030103_ALUDecoder(config))
  ALUDecoderModule.io.opcode := opcode
  ALUDecoderModule.io.funct3 := funct3
  ALUDecoderModule.io.funct7 := funct7
  val ALUCtrl = ALUDecoderModule.io.ALUCtrl
  val ALUCdIllegal = ALUDecoderModule.io.Illegal
  val IsMDU = ALUDecoderModule.io.IsMDU
  val MDUOp = ALUDecoderModule.io.MDUOp
  // ALUCDIllegal只覆盖了已知指令类别里funct3/funct7非法的情况,这里补上"不属于任何已知指令"的检测:
  // System 实现完整的六条 Zicsr 指令以及 ecall/ebreak/mret/wfi；WFI
  // 作为无副作用 NOP 正常退休。MiscMem 中 fence/fence.i 分别处理。
  val isKnownInstruction =
    isRType || isIType || isSType || isBType || isUType || isJType ||
      IsCSRInstruction || isEcall || isEbreak || isSimHalt || isMret ||
      isSret || isSfenceVma || isWfi || isFenceI || isFence || isAtomic ||
      IsFP
  // CSR 访问合法性在译码时检查，而不是依赖 CSRUnit 的读取数据多路选择器。
  // 本核心目前只实现下面明确列出的机器态 CSR 集合，访问集合外的 CSR
  // 将被视为非法指令。
  val CSRAddress = instruction(31, 20)
  private val baseUserCounterAddresses =
    if (XLEN == 32) Seq(0xc00, 0xc01, 0xc02, 0xc80, 0xc81, 0xc82)
    else Seq(0xc00, 0xc01, 0xc02)
  private val pmuUserCounterAddresses =
    if (pmuZihpm)
      (0 until pmuCounterCount).flatMap { index =>
        Seq(0xc03 + index) ++
          (if (XLEN == 32) Seq(0xc83 + index) else Seq.empty)
      }
    else Seq.empty
  private val userCounterAddresses =
    baseUserCounterAddresses ++ pmuUserCounterAddresses
  private val pmuMachineAddresses =
    if (pmuZihpm)
      (0 until pmuCounterCount).flatMap { index =>
        Seq(0xb03 + index, 0x323 + index) ++
          (if (XLEN == 32) Seq(0xb83 + index) else Seq.empty)
      } ++ (if (pmuSscofpmf) Seq(0xda0) else Seq.empty)
    else Seq.empty
  // F/D 的 fflags(0x001)/frm(0x002)/fcsr(0x003) 是 U 级可访问 CSR；
  // 是否实现由 RISCV_F 决定，不依赖 MMU profile 的整数 CSR 集合。
  private val FPCSRAddresses =
    if (useF) Seq(0x001, 0x002, 0x003) else Seq.empty
  private val CSRAddresses = (
    config.MMU.CSREnabled.toSeq ++
      // identity CSR 始终实现，不依赖 CSR_ENABLED 是否列出。
      _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile
        .IdentityCSRAddresses.toSeq ++
      userCounterAddresses ++ FPCSRAddresses ++ pmuMachineAddresses ++
      // RV64 只有 pmpcfg0/pmpcfg2，0x3a1/0x3a3 是 RV32 专用编码。
      (if (config.MMU.PMPEnabled)
        (if (XLEN == 64) Seq(0x3a0, 0x3a2) else (0x3a0 to 0x3a3)) ++
          (0x3b0 to 0x3bf)
       else Seq.empty)
  ).filter(a => config.MMU.csrSupported(a) || userCounterAddresses.contains(a) ||
    FPCSRAddresses.contains(a) || pmuMachineAddresses.contains(a))
    .distinct.sorted
  val CSRAddressValid =
    if (config.MMU.CSRMissingMode == "zero") true.B
    else CSRAddresses.map(a => CSRAddress === a.U(12.W)).reduceOption(_ || _).getOrElse(false.B)
  // 地址位 [9:8] 编码最低可访问特权级：00=U, 01=S, 11=M。
  // HAS_U=n 时 U 级不再存在：防御性地把 0 级请求提升为 S，并显式拒绝
  // U 级 CSR，避免上游异常状态重新打开 U 模式访问路径。HAS_S=n 但
  // HAS_U=y（M+U）时仍按真实 U 特权级检查；S 级地址已由 csrSupported
  // 从 CSRAddresses 裁剪，因此 U/M 都不会看到 S CSR。
  val effectivePrivilege =
    if (!hasS && !hasU) 3.U(2.W)
    else if (hasU) io.Privilege
    else Mux(io.Privilege === 0.U, 1.U(2.W), io.Privilege)
  // fcsr/fflags/frm 是 U 级 CSR，但 M 模式始终可访问；M-only 配置不能
  // 因为不存在 U 级就把它们判成非法。
  val isFPCSR = if (useF)
    (CSRAddress === 0x001.U) || (CSRAddress === 0x002.U) ||
      (CSRAddress === 0x003.U)
  else false.B
  // 用户只读计数器是 U 级地址，但 M 模式始终可以访问；S 模式由
  // mcounteren 控制，U 模式再由 scounteren 控制。HAS_U=n 时它们仍
  // 由 CSR 模块实现，不能因为地址位 [9:8]=00 就被判成非法。
  val isUserCounter = userCounterAddresses.map(a => CSRAddress === a.U(12.W))
    .reduceOption(_ || _).getOrElse(false.B)
  val CSRPrivilegeOk = isFPCSR || isUserCounter || (if (!hasS && !hasU)
    CSRAddress(9, 8) === "b11".U(2.W)
  else
    (effectivePrivilege >= CSRAddress(9, 8)) &&
      (if (hasU) true.B else CSRAddress(9, 8) =/= 0.U(2.W)))
  val CSRReadOnly = CSRAddress(11, 10) === "b11".U(2.W) ||
    CSRAddress === "h301".U(12.W) // misa 当前只读 */
  // 读只读 CSR 合法；CSRRW/带非零源的 CSRRS/CSRRC 写只读 CSR 非法。
  val CSRWriteIntent = isCSRrw || ((isCSRrs || isCSRrc) && (rs1 =/= 0.U))
  // mcounteren/scounteren 的第 N 位对应 CSR 的计数器编号 N；
  // 5 位索引覆盖 hpmcounter3..31 以及 cycle/time/instret 别名。
  val counterBit = CSRAddress(4, 0)
  val counterAccessOk = !isUserCounter || effectivePrivilege === 3.U ||
    (io.Mcounteren(counterBit) &&
      (if (hasS) effectivePrivilege === 1.U || io.Scounteren(counterBit)
       else true.B))
  val CSRIllegal =
    IsCSRInstruction &&
      (!CSRAddressValid || !CSRPrivilegeOk || !counterAccessOk ||
        (CSRWriteIntent && CSRReadOnly))
  // mstatus.TVM/TSR/TW 的 S 模式陷门。HAS_S=n 时相关状态和陷门都不存在。
  val tvmFault =
    if (hasS)
      (effectivePrivilege === 1.U) && io.Mstatus(20) &&
        (isSfenceVma || (IsCSRInstruction && CSRAddress === "h180".U(12.W)))
    else false.B
  val tsrFault =
    if (hasS) (effectivePrivilege === 1.U) && io.Mstatus(22) && isSret
    else false.B
  val twFault =
    if (hasS) (effectivePrivilege =/= 3.U) && io.Mstatus(21) && isWfi
    else false.B
  // 返回指令的固定特权级约束：MRET 只能在 M；SRET 在 S/M 合法，仅在 U
  // 非法（mstatus.TSR 的 S 模式陷门由 TsrFault 单独处理）。
  // M+U 没有 S，U 若执行 MRET 必须作为非法指令陷入 M，而不能提权。
  // HAS_S=n 时不存在 SRET 编码，必须作为非法指令处理。
  val mretFault = isMret && effectivePrivilege =/= 3.U
  val sretFault =
    if (!hasS) isSret else isSret && effectivePrivilege === 0.U
  // SFENCE.VMA 在 S/M 都可用，只禁止 U。
  val sfenceFault =
    if (hasS) isSfenceVma && effectivePrivilege === 0.U else false.B
  // custom-0 不属于通用 ALU decoder 的 opcode 集合；精确匹配的仿真
  // halt 编码在这里显式豁免，custom-0 的其它编码仍然是非法指令。
  val illegalInstruction =
    (ALUCdIllegal && !isSimHalt) || !isKnownInstruction || CSRIllegal ||
      tvmFault || tsrFault || twFault ||
      mretFault || sretFault || sfenceFault ||
      rvcIllegal || cExtensionDisabledIllegal || FPIllegal
  // LR 的 rs2 字段必须为 0，但统一按 A 扩展读 rs1/rs2 不会引入错误转发，
  // 反而让 SC/AMO 的源操作数冒险检测与普通指令完全一致。
  // GPR rs2 只有整数寄存器型/分支/整数 store/AMO 使用；浮点 store 的
  // rs2 字段是 f 寄存器，不能触发 GPR 转发/冒险。
  val needsRs2 = isRType || isBType || isSType || isAtomic
  // 源操作数真正被使用的判断(lui/auipc/jal的rs1字段是立即数,不算使用)
  val usesRs1 = isRType || isIType || isSType || isBType || isAtomic ||
    (IsCSRInstruction && !IsCSRImmediate) || FPGPRSource1
  // 转发命中(EXU > MEM等待槽 > MEM级 > WBU: 多条同时命中时选最年轻生产者)
  // 显式检查生产者 valid：虽然顶层契约把 valid 折进了 *_fwd_ready
  // （EXU/LSU 的 FwdReady 含 HazardValid），这里保持与下方冒险谓词同口径，
  // 避免未来新增生产者端口只拉 ready 时静默转发陈旧数据。
  val exForwardRs1 =
    usesRs1 && io.ExValid && io.ExForwardReady && io.ExRd =/= 0.U &&
      io.ExRd === rs1
  val exForwardRs2 =
    io.ExValid && io.ExForwardReady && io.ExRd =/= 0.U && needsRs2 &&
      io.ExRd === rs2
  val me2ForwardRs1 =
    usesRs1 && io.Me2Valid && io.Me2ForwardReady && io.Me2Rd =/= 0.U &&
      io.Me2Rd === rs1
  val me2ForwardRs2 =
    io.Me2Valid && io.Me2ForwardReady && io.Me2Rd =/= 0.U && needsRs2 &&
      io.Me2Rd === rs2
  val meForwardRs1 =
    usesRs1 && io.MeValid && io.MeForwardReady && io.MeRd =/= 0.U &&
      io.MeRd === rs1
  val meForwardRs2 =
    io.MeValid && io.MeForwardReady && io.MeRd =/= 0.U && needsRs2 &&
      io.MeRd === rs2
  val wbForwardRs1 =
    usesRs1 && io.WbValid && io.WbRegWrite && io.WbRd =/= 0.U &&
      io.WbRd === rs1
  val wbForwardRs2 =
    io.WbValid && io.WbRegWrite && io.WbRd =/= 0.U && needsRs2 && io.WbRd === rs2
  val source1 = Mux(
    exForwardRs1,
    io.ExForwardData,
    Mux(
      me2ForwardRs1,
      io.Me2ForwardData,
      Mux(
        meForwardRs1,
        io.MeForwardData,
        Mux(wbForwardRs1, io.WbForwardData, io.ReadDATA1)
      )
    )
  )
  val source2 = Mux(
    exForwardRs2,
    io.ExForwardData,
    Mux(
      me2ForwardRs2,
      io.Me2ForwardData,
      Mux(
        meForwardRs2,
        io.MeForwardData,
        Mux(wbForwardRs2, io.WbForwardData, io.ReadDATA2)
      )
    )
  )
  // FPR 读/转发
  // 三个 f 源操作数与 GPR 使用同一优先级：EXU > MEM 等待槽 > MEM > WBU。
  // f0 是普通寄存器，不做零硬连线，因此这里不排除 rd=0。
  val ExFPForwardRs1 = FPUses1 && io.ExFPValid && io.ExFPRegWrite &&
    io.ExFPRd === rs1 && io.ExFPForwardReady
  val ExFPForwardRs2 = FPUses2 && io.ExFPValid && io.ExFPRegWrite &&
    io.ExFPRd === rs2 && io.ExFPForwardReady
  val ExFPForwardRs3 = (FPUses3 && io.ExFPValid && io.ExFPRegWrite &&
    io.ExFPRd === instruction(31, 27) && io.ExFPForwardReady)
  val Me2FPForwardRs1 = FPUses1 && io.Me2FPValid && io.Me2FPRegWrite &&
    io.Me2FPRd === rs1 && io.Me2FPForwardReady
  val Me2FPForwardRs2 = FPUses2 && io.Me2FPValid && io.Me2FPRegWrite &&
    io.Me2FPRd === rs2 && io.Me2FPForwardReady
  val Me2FPForwardRs3 = (FPUses3 && io.Me2FPValid && io.Me2FPRegWrite &&
    io.Me2FPRd === instruction(31, 27) && io.Me2FPForwardReady)
  val MeFPForwardRs1 = FPUses1 && io.MeFPValid && io.MeFPRegWrite &&
    io.MeFPRd === rs1 && io.MeFPForwardReady
  val MeFPForwardRs2 = FPUses2 && io.MeFPValid && io.MeFPRegWrite &&
    io.MeFPRd === rs2 && io.MeFPForwardReady
  val MeFPForwardRs3 = (FPUses3 && io.MeFPValid && io.MeFPRegWrite &&
    io.MeFPRd === instruction(31, 27) && io.MeFPForwardReady)
  val WbFPForwardRs1 = FPUses1 && io.WbFPValid && io.WbFPRegWrite &&
    io.WbFPRd === rs1
  val WbFPForwardRs2 = FPUses2 && io.WbFPValid && io.WbFPRegWrite &&
    io.WbFPRd === rs2
  val WbFPForwardRs3 = FPUses3 && io.WbFPValid && io.WbFPRegWrite &&
    io.WbFPRd === instruction(31, 27)
  val FPSource1 = Mux(ExFPForwardRs1, io.ExFPForwardData,
    Mux(Me2FPForwardRs1, io.Me2FPForwardData,
      Mux(MeFPForwardRs1, io.MeFPForwardData,
        Mux(WbFPForwardRs1, io.WbFPForwardData, io.ReadFPR1))))
  val FPSource2 = Mux(ExFPForwardRs2, io.ExFPForwardData,
    Mux(Me2FPForwardRs2, io.Me2FPForwardData,
      Mux(MeFPForwardRs2, io.MeFPForwardData,
        Mux(WbFPForwardRs2, io.WbFPForwardData, io.ReadFPR2))))
  val FPSource3 = Mux(ExFPForwardRs3, io.ExFPForwardData,
    Mux(Me2FPForwardRs3, io.Me2FPForwardData,
      Mux(MeFPForwardRs3, io.MeFPForwardData,
        Mux(WbFPForwardRs3, io.WbFPForwardData, io.ReadFPR3))))

  val FPExHazard =
    io.ExFPValid && io.ExFPRegWrite &&
      ((FPUses1 && io.ExFPRd === rs1) ||
        (FPUses2 && io.ExFPRd === rs2) ||
        (FPUses3 && io.ExFPRd === instruction(31, 27)))
  val FPMe2Hazard =
    io.Me2FPValid && io.Me2FPRegWrite &&
      ((FPUses1 && io.Me2FPRd === rs1) ||
        (FPUses2 && io.Me2FPRd === rs2) ||
        (FPUses3 && io.Me2FPRd === instruction(31, 27)))
  val FPMeHazard =
    io.MeFPValid && io.MeFPRegWrite &&
      ((FPUses1 && io.MeFPRd === rs1) ||
        (FPUses2 && io.MeFPRd === rs2) ||
        (FPUses3 && io.MeFPRd === instruction(31, 27)))
  // 只有生产者的值尚未就绪时才阻塞（例如未完成的 FLW/FLD）。
  val FPIsRaw = Mux(io.PipelineMode,
    (FPExHazard && !io.ExFPForwardReady) ||
      (FPMe2Hazard && !io.Me2FPForwardReady) ||
      (FPMeHazard && !io.MeFPForwardReady),
    false.B)
  // FCVT.S.W/L 与 FMV.W.X/D.X 的整数源来自 GPR rs1，而不是 f 寄存器；
  // 其余浮点运算的三个源操作数都来自 FPR。RV32 的 src1 零扩展即可，
  // RV64 的 L/D 形式需要完整 64 位。
  val storeDataSource = Mux(IsFPStore, FPSource2, source2)
  val FPRs1Source = Mux(FPGPRSource1, source1, FPSource1)

  val ALUA = WireDefault(source1) // 默认所有指令的第一个计算的数是寄存器值
  switch(opcode) {
    is(OPCODE_UpperImmediate_lui) {
      ALUA := 0.U(XLEN.W)
    }
    is(OPCODE_UpperImmediate_auipc, OPCODE_Jump) {
      ALUA := PC
    }
  }
  val ALUB = Mux((opcode === OPCODE_Register) || (opcode === OPCODE_RegisterWord), source2, immediate)
  val exHazard = io.ExValid && io.ExRegWrite && io.ExRd =/= 0.U &&
    ((usesRs1 && io.ExRd === rs1) || (needsRs2 && io.ExRd === rs2))
  val me2Hazard = io.Me2Valid && io.Me2RegWrite && io.Me2Rd =/= 0.U &&
    ((usesRs1 && io.Me2Rd === rs1) || (needsRs2 && io.Me2Rd === rs2))
  val meHazard = io.MeValid && io.MeRegWrite && io.MeRd =/= 0.U &&
    ((usesRs1 && io.MeRd === rs1) || (needsRs2 && io.MeRd === rs2))
  // 普通 EX 冒险端口只表示最老的 MDU 指令。队列中更年轻的 MDU 生产者
  // 尚无可供前递的值。
  val HiddenMDUHazard =
    if (config.UseM) {
      (usesRs1 && rs1 =/= 0.U && io.ExMDUHiddenWrites(rs1)) ||
        (needsRs2 && rs2 =/= 0.U && io.ExMDUHiddenWrites(rs2))
    } else {
      false.B
    }
  // 只有生产者的数据未就绪(load未完成)才需要阻塞,其余全部转发
  val isRaw = Mux(
    io.PipelineMode,
    (exHazard && !io.ExForwardReady) || (me2Hazard && !io.Me2ForwardReady) ||
      (meHazard && !io.MeForwardReady) || HiddenMDUHazard,
    false.B
  )
  val anyIsRaw = isRaw || FPIsRaw
  io.in.ready := io.out.ready && !anyIsRaw && !io.MMUStall
  io.out.valid := io.in.valid && !anyIsRaw && !io.MMUStall
  // 停顿分类口径：load-use 只统计“确实由未就绪的访存生产者造成”的停顿。
  // me 端口的 load 可能已经就绪（StageDone 或 R 响应当拍），此时它只是被
  // 隐藏 MDU 写掩码顺带命中，不能算作 load-use。ex/me2 上的 load 恒未就绪，
  // 加上 !fwd_ready 只是保持口径一致。
  val rawLoadUse =
    (exHazard && io.ExMemop && !io.ExForwardReady) ||
      (me2Hazard && io.Me2Memop && !io.Me2ForwardReady) ||
      (meHazard && io.MeMemop && !io.MeForwardReady) ||
      (FPExHazard && io.ExMemop && !io.ExFPForwardReady) ||
      (FPMe2Hazard && io.Me2Memop && !io.Me2FPForwardReady) ||
      (FPMeHazard && io.MeMemop && !io.MeFPForwardReady)
  // 注意：raw_alu 是“非 load-use 的 RAW 停顿”桶，包含 MDU 队首未就绪、
  // MDU 隐藏写掩码等非访存生产者造成的停顿（ALU 生产者恒就绪，本身不产生
  // 停顿），命名沿用历史。
  io.perf_stall_raw := io.in.valid && anyIsRaw
  io.perf_stall_raw_loaduse := io.in.valid && anyIsRaw && rawLoadUse
  io.perf_stall_raw_alu := io.in.valid && anyIsRaw && !rawLoadUse
  io.PerfWfi := io.in.valid && isWfi
  // 退休/trace/mtval 使用原始编码；展开结果只影响控制信号。
  io.out.bits.Instruction := rawInstruction
  io.out.bits.IsCompressed := isCompressed
  io.out.bits.PC := PC
  io.out.bits.snpc := snpc
  io.out.bits.ALUCtrl := ALUCtrl
  io.out.bits.IsMDU := IsMDU
  io.out.bits.MDUOp := MDUOp
  io.out.bits.ALU_A := ALUA
  io.out.bits.ALU_B := ALUB
  io.out.bits.BranchA := source1
  io.out.bits.BranchB := source2
  io.out.bits.BranchFunct3 := funct3
  io.out.bits.IsBranch := isBType
  io.out.bits.IsJal := isJType
  io.out.bits.IsJalr := (opcode === OPCODE_Immediate_Bxxx) && (funct3 === "b000"
    .U(3.W))
  io.out.bits.Immediate := immediate
  io.out.bits.Rd := rd
  io.out.bits.RegisterWrite := registerWrite
  io.out.bits.WBSelect := wbSelect
  io.out.bits.MemoryValid := memoryValid
  io.out.bits.MemoryWrite := memoryWrite
  io.out.bits.WidthSelect := widthSelect
  io.out.bits.LoadSigned := loadSigned
  // 写就直接上rs2第二个寄存器；浮点 store 使用 f 寄存器读口。
  io.out.bits.StoreData := storeDataSource
  io.out.bits.IsAtomic := isAtomic
  io.out.bits.AtomicOp := atomicOp
  io.out.bits.IsCSRrw := isCSRrw
  io.out.bits.IsCSRrs := isCSRrs
  io.out.bits.IsCSRrc := isCSRrc
  io.out.bits.IsEcall := isEcall
  io.out.bits.IsEbreak := isEbreak
  io.out.bits.IsSimHalt := isSimHalt
  io.out.bits.IsMret := isMret
  io.out.bits.IsSret := isSret
  io.out.bits.IsSfenceVma := isSfenceVma
  io.out.bits.IsFence := isFence
  io.out.bits.IsFenceI := isFenceI
  io.out.bits.IsFP := IsFP
  io.out.bits.IsFPU := FPIsFPU
  io.out.bits.FPOp := FPOp
  io.out.bits.FPFmt := FPFmt
  io.out.bits.FPSrcFmt := FPSourceFmt
  io.out.bits.FPRm := FPRm
  io.out.bits.FPIntWord := FPIntWord
  io.out.bits.FPRs1 := FPRs1Source.pad(64)
  io.out.bits.FPRs2 := FPSource2.pad(64)
  io.out.bits.FPRs3 := FPSource3.pad(64)
  io.out.bits.FPRegisterWrite := FPWritesFPR
  io.out.bits.FPWriteData := 0.U(64.W)
  io.out.bits.FP64 := FPUseD
  io.out.bits.CSRAddress := CSRAddress
  io.out.bits.Rs1 := rs1
  io.out.bits.Rs1Data := Mux(IsCSRImmediate, Cat(0.U((XLEN - 5).W), rs1), source1)
  // 异常传递:IFU的取指错优先(此时指令本身是垃圾,IDU的译码结果不可信),否则报非法指令(cause=2)
  io.out.bits.ExceptionValid := io.in.bits.ExceptionValid || illegalInstruction
  io.out.bits.ExceptionCause := Mux(
    io.in.bits.ExceptionValid,
    io.in.bits.ExceptionCause,
    2.U(4.W)
  )
  io.out.bits.AccessFaultResponse := io.in.bits.AccessFaultResponse
  // 分支预测信息透传
  io.out.bits.PredTaken := io.in.bits.PredTaken
  io.out.bits.PredTarget := io.in.bits.PredTarget
}
