package ysyx_26030103.exu
import chisel3._
import chisel3.util._

/** 机器/监管者 CSR 与精确异常入口。
  *
  * 默认实现支持 M/S/U 三个特权级、S 模式 CSR、异常委托和 SRET。
  * 页表翻译本身在 MMU 中完成；这里负责 satp、stvec/sepc/scause/stval
  * 以及异常入口的特权级切换。
  *
  * HAS_S=n 时只生成 M 模式 CSR/异常入口，S 模式寄存器和委托逻辑在
  * elaboration 期直接省略；但 HAS_U=y 时仍保留 U 特权级，U 的所有
  * 异常/中断直接进入 M（ecall cause=8），不生成任何 S CSR 或委托。
  * HAS_U=n 时 U 级 CSR、ecall cause=8 和返回 U 的选择路径同样被裁掉。
  * 默认 HAS_S=y/HAS_U=y 的行为保持不变。
  */
class ysyx_26030103_CSR(
    val profile: _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile =
      _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile(),
    val XLEN: Int = 32,
    // RISCV_A 打开时 misa.A 必须置位；关闭时该位保持 0。
    val UseA: Boolean = false,
    // RISCV_C 打开时 misa.C 置位，mepc/sepc 只清 bit0（IALIGN=16）。
    val UseC: Boolean = false,
    // RISCV_F/D 打开时实现 fflags/frm/fcsr 与 mstatus.FS，misa.F/D 置位。
    val UseF: Boolean = false,
    val UseD: Boolean = false
) extends Module {
  import ysyx_26030103_mstatus.legalize
  require(XLEN == 32 || XLEN == 64, s"CSR XLEN 非法: $XLEN")
  require(!UseD || UseF, "CSR 的 D 扩展依赖 F 扩展")
  private val hasS = profile.hasS
  private val hasU = profile.hasU
  private val FullMask: BigInt =
    if (XLEN == 64) (BigInt(1) << 64) - 1 else 0xffffffffL
  // satp 在 RV64 下使用完整 64 位掩码；RV32 只保留低 32 位。
  private val SatpReadMaskValue: BigInt =
    BigInt(profile.csrSatpReadMask) & FullMask
  private val SatpReadSetValue: BigInt =
    BigInt(profile.csrSatpReadSet) & FullMask
  private val SatpWriteMaskValue: BigInt =
    BigInt(profile.csrSatpWriteMask) & FullMask
  // 32 位 CSR（mstatus/mie/mip 等）在 RV64 下零扩展到 XLEN；
  // mepc/mtvec/mtval/mscratch 等则天然使用完整 XLEN。
  private def Widen32(Value: UInt): UInt =
    if (XLEN == 32) Value else Cat(0.U((XLEN - 32).W), Value)
  // mcause/scause 的中断位在 XLEN-1，而本实现的 ExceptionCause 是 32 位
  // 编码。RV64 下必须把 bit31 搬到 bit63，而不是零扩展或普通符号扩展：
  //   0x80000009 -> 0x8000000000000009
  //   0x0000000b -> 0x000000000000000b
  // 零扩展会让 xv6 的 `scause == 0x8000000000000009` 判断失败。
  private def WidenCause32(Value: UInt): UInt =
    if (XLEN == 32) Value
    else Cat(Value(31), 0.U((XLEN - 32).W), Value(30, 0))
  // RV64 的 mstatus.SXL/UXL 是 WARL，本实现只支持 64 位，因此固定读回 2。
  // 这两个字段位于 bit[35:32]，必须和 NEMU REF/架构 CSR 视图一起保留，
  // 否则 RV64 DiffTest 会在任何一次 mstatus 写入后看到高半区差异。
  // SD 是 FS==3 的只读汇总位：RV32 在 bit31，RV64 在 bit63；软件不能
  // 直接写它，但读回/NEMU DiffTest 必须一致。
  private def MstatusSdBit(Fs: UInt): UInt =
    if (!UseF) 0.U(XLEN.W)
    else Mux(Fs === 3.U,
      (BigInt(1) << (XLEN - 1)).U(XLEN.W), 0.U(XLEN.W))
  private def MstatusArchValue(Value: UInt): UInt = {
    val WithXlen =
      if (XLEN == 32) Value
      else Widen32(Value) | "h0000000a00000000".U(XLEN.W)
    WithXlen | MstatusSdBit(Value(14, 13))
  }
  private def csrOn(addr: Int): Boolean = profile.csrSupported(addr)
  // HAS_U=n 时 mstatus 的 MPP/SPP 最低值被夹到 S；HAS_S=n 时固定为 M。
  private def legalizeMstatus(value: UInt): UInt =
    legalize(value, hasU = hasU, hasS = hasS, hasF = UseF)
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val Enable = Input(Bool())
    val IsCsrrw = Input(Bool())
    val IsCsrrs = Input(Bool())
    val IsCsrrc = Input(Bool())
    val IsEcall = Input(Bool())
    val IsEbreak = Input(Bool())
    val IsMret = Input(Bool())
    val IsSret = Input(Bool())
    val IsSfenceVma = Input(Bool())
    val CSRAddress = Input(UInt(12.W))
    val rs1 = Input(UInt(5.W))
    val Rs1Data = Input(UInt(XLEN.W))
    val pc = Input(UInt(XLEN.W))
    val TrapTval = Input(UInt(XLEN.W))
    // TrapTval 本身可能合法地为 0（例如全零的非法压缩编码），因此
    // 必须显式区分“使用给定 tval”与“回退到故障 PC”。
    val TrapTvalValid = Input(Bool())
    val CSR_rdata = Output(UInt(XLEN.W))
    val ExceptionTaken = Output(Bool())
    val ExceptionTarget = Output(UInt(XLEN.W))
    val FlushTLB = Output(Bool())
    val Privilege = Output(UInt(2.W))
    val Satp = Output(UInt(XLEN.W))

    val Interrupt = Input(Bool())
    val TimerInterrupt = Input(Bool())
    val TrapValid = Input(Bool())
    val TrapCause = Input(UInt(32.W))
    val MemTrap = Input(Bool())
    val IrqCommit = Output(Bool())
    val IrqPending = Output(Bool())
    val TrapCommit = Output(Bool())
    val CommittedCause = Output(UInt(32.W))

    val CurrentMstatus = Output(UInt(XLEN.W))
    val StateMstatus = Output(UInt(XLEN.W))
    val StateMtvec = Output(UInt(XLEN.W))
    val StateMepc = Output(UInt(XLEN.W))
    val StateMcause = Output(UInt(XLEN.W))
    // 本拍 trap 写入 mtval/stval 的最终值：访存/非法压缩/misaligned
    // 使用明确 tval，ecall/中断固定为 0，其余回退到 PC。
    // DiffTest 用它把 DUT 的真实 tval 注入 REF，不参与 CSR 快照 ABI。
    val StateTval = Output(UInt(XLEN.W))
    val PmpCfg = Output(Vec(16, UInt(8.W)))
    val PmpAddr = Output(Vec(16, UInt(XLEN.W)))
    val Mtime = Input(UInt(64.W))
    val McounterenOut = Output(UInt(32.W))
    val ScounterenOut = Output(UInt(32.W))
    // F/D：当前 frm 给 IDU 解析动态舍入；浮点运算在 EXU 提交时把
    // fflags 累积到 fcsr；WBU 退休 FP 指令时把 mstatus.FS 置 Dirty。
    val Frm = Output(UInt(3.W))
    // fcsr={frm,fflags} 的实时 8 位视图。DiffTest 在顶层退休沿锁存。
    val Fcsr = Output(UInt(8.W))
    val FPCommit = Input(Bool())
    val FPFlags = Input(UInt(5.W))
    val FPStateDirtyCommit = Input(Bool())
  })

  val CSR_MSTATUS = 0x300.U(12.W)
  val CSR_MTVEC = 0x305.U(12.W)
  val CSR_MEPC = 0x341.U(12.W)
  val CSR_MCAUSE = 0x342.U(12.W)
  val CSR_MSCRATCH = 0x340.U(12.W)
  val CSR_MEDELEG = 0x302.U(12.W)
  val CSR_MIDELEG = 0x303.U(12.W)
  val CSR_MIE = 0x304.U(12.W)
  val CSR_MIP = 0x344.U(12.W)
  val CSR_SSTATUS = 0x100.U(12.W)
  val CSR_SIE = 0x104.U(12.W)
  val CSR_STVEC = 0x105.U(12.W)
  val CSR_SCOUNTEREN = 0x106.U(12.W)
  val CSR_SSCRATCH = 0x140.U(12.W)
  val CSR_SEPC = 0x141.U(12.W)
  val CSR_SCAUSE = 0x142.U(12.W)
  val CSR_STVAL = 0x143.U(12.W)
  val CSR_SIP = 0x144.U(12.W)
  val CSR_SATP = 0x180.U(12.W)
  val CSR_CYCLE = 0xc00.U(12.W)
  val CSR_TIME = 0xc01.U(12.W)
  val CSR_INSTRET = 0xc02.U(12.W)
  val CSR_CYCLEH = 0xc80.U(12.W)
  val CSR_TIMEH = 0xc81.U(12.W)
  val CSR_INSTRETH = 0xc82.U(12.W)
  val CSR_MCYCLE = 0xb00.U(12.W)
  val CSR_MCYCLEH = 0xb80.U(12.W)
  val CSR_MINSTRET = 0xb02.U(12.W)
  val CSR_MINSTRETH = 0xb82.U(12.W)
  val CSR_MISA = 0x301.U(12.W)
  val CSR_MTVAL = 0x343.U(12.W)
  val CSR_MCOUNTEREN = 0x306.U(12.W)
  val CSR_MCOUNTINHIBIT = 0x320.U(12.W)
  val CSR_MVENDORID = 0xf11.U(12.W)
  val CSR_MARCHID = 0xf12.U(12.W)
  val CSR_MIMPID = 0xf13.U(12.W)
  val CSR_MHARTID = 0xf14.U(12.W)
  val CSR_FFLAGS = 0x001.U(12.W)
  val CSR_FRM = 0x002.U(12.W)
  val CSR_FCSR = 0x003.U(12.W)

  val Mvendorid_rdata = 0x79737978.U(32.W)
  val Marchid_rdata = "h018d3017".U(32.W)
  // 单 hart 实现：mhartid 固定为 0。mimpid 遵循与 NEMU REF 一致的
  // 约定，固定返回 0（表示不提供额外的实现版本号）。
  val Mimpid_rdata = 0.U(32.W)
  val Mhartid_rdata = 0.U(32.W)
  // SIE SPIE SPP SUM MXR；RISCV_F 时 sstatus 也包含 FS[14:13]。
  val SstatusMask = "h000c0122".U(32.W) |
    (if (UseF) "h00006000".U(32.W) else 0.U(32.W))
  def IsCSRAddress(addr: UInt): Bool = io.CSRAddress === addr
  private val FpCsrAddresses =
    if (UseF) Seq(0x001, 0x002, 0x003) else Seq.empty
  // 用户只读计数器始终随核实现：M 模式在任何配置下都可读；U 模式是否
  // 可读由 mcounteren/scounteren 在 IDU 检查。它们不能因为 CSR_ENABLED
  // 未列出而被裁剪，否则 rdcycle/rdtime/rdinstret 会读回 0。
  private val UserCounterAddresses =
    Seq(0xc00, 0xc01, 0xc02, 0xc80, 0xc81, 0xc82)
  private val enabledCsrAddresses = (
    profile.csrEnabled.toSeq ++
      // identity CSR 始终存在，不依赖 CSR_ENABLED 是否列出。
      _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile
        .IdentityCsrAddresses.toSeq ++
      UserCounterAddresses ++
      FpCsrAddresses ++
      (if (profile.pmpEnabled) (0x3a0 to 0x3a3) ++ (0x3b0 to 0x3bf) else Seq.empty)
  ).filter(a => profile.csrSupported(a) || FpCsrAddresses.contains(a) ||
    UserCounterAddresses.contains(a))
    .distinct.sorted
  val CsrImplemented =
    enabledCsrAddresses.map(a => io.CSRAddress === a.U(12.W))
      .reduceOption(_ || _).getOrElse(false.B)
  val CsrUseSstatusView = profile.csrViewMode == "bound"
  // mstatus.FS 只有在 RISCV_F 时存在；profile 默认读/写掩码没有包含
  // bit[14:13]，这里按 F 开关补上，避免软件写 mstatus 后 FS 被静默清零。
  private val MstatusFsMask = if (UseF) 0x6000L else 0L
  private val MstatusProfileReadMask =
    profile.csrMstatusReadMask.toLong & 0xffffffffL
  private val MstatusProfileWriteMask =
    profile.csrMstatusWriteMask.toLong & 0xffffffffL
  private val MstatusReadMaskValue = MstatusProfileReadMask | MstatusFsMask
  private val MstatusWriteMaskValue = MstatusProfileWriteMask | MstatusFsMask
  def CsrReadMask: UInt = {
    val full = FullMask.U(XLEN.W)
    // mstatus 的字段掩码已经在 MstatusReadData 里应用；这里不能再按
    // 32 位读写掩码截断，否则 SD(bit31/63) 和 RV64 的 UXL/SXL(35:32)
    // 会在 CSR 读口被清掉（NEMU REF 与架构读回都包含这些只读位）。
    val mstatusMask = full
    if (csrOn(0x180)) Mux(io.CSRAddress === CSR_SATP,
      SatpReadMaskValue.U(XLEN.W), mstatusMask) else mstatusMask
  }
  def CsrReadSet: UInt = {
    val mstatusSet =
      if (csrOn(0x300)) Mux(io.CSRAddress === CSR_MSTATUS,
        (profile.csrMstatusReadSet.toLong & 0xffffffffL).U(XLEN.W), 0.U(XLEN.W)) else 0.U(XLEN.W)
    if (csrOn(0x180)) Mux(io.CSRAddress === CSR_SATP,
      SatpReadSetValue.U(XLEN.W), mstatusSet) else mstatusSet
  }
  def CsrWriteMask: UInt = {
    val full = FullMask.U(XLEN.W)
    val mstatusMask =
      if (csrOn(0x300)) Mux(io.CSRAddress === CSR_MSTATUS,
        MstatusWriteMaskValue.U(XLEN.W), full) else full
    if (csrOn(0x180)) Mux(io.CSRAddress === CSR_SATP,
      SatpWriteMaskValue.U(XLEN.W), mstatusMask) else mstatusMask
  }

  val csrrwWen = io.Enable && io.IsCsrrw
  val csrrsWen = io.Enable && io.IsCsrrs && io.rs1 =/= 0.U
  val csrrcWen = io.Enable && io.IsCsrrc && io.rs1 =/= 0.U
  def CSRWriteData(old: UInt): UInt = {
    val raw = Mux(io.IsCsrrw, io.Rs1Data,
      Mux(io.IsCsrrs, old | io.Rs1Data, old & ~io.Rs1Data))
    val mask = CsrWriteMask
    (old & ~mask) | (raw & mask)
  }

  val MretCommit = io.Enable && io.IsMret
  // HAS_S=n 时 SRET/SFENCE.VMA 在译码阶段已是非法指令；这里连 CSR 侧
  // 的提交/刷新路径也一并裁掉，不保留只可能为 0 的控制逻辑。
  val SretCommit = if (hasS) io.Enable && io.IsSret else false.B
  val SfenceCommit = if (hasS) io.Enable && io.IsSfenceVma else false.B

  val Mstatus = Module(new ysyx_26030103_mstatus(hasS, hasU, UseF))
  val Mtvec = Module(new ysyx_26030103_mtvec(XLEN))
  val Mepc = Module(new ysyx_26030103_mepc(XLEN, UseC))
  val Mcause = Module(new ysyx_26030103_mcause(XLEN))
  // S 模式入口寄存器只在 HAS_S=y 时例化。HAS_S=n 时顶层不生成 MMU，
  // 也不会有可返回的 S 状态，因此这些寄存器没有保留价值。
  val StvecOpt = if (hasS) Some(Module(new ysyx_26030103_mtvec(XLEN))) else None
  val SepcOpt = if (hasS) Some(Module(new ysyx_26030103_mepc(XLEN, UseC))) else None
  val ScauseOpt = if (hasS) Some(Module(new ysyx_26030103_mcause(XLEN))) else None
  val Mcycle = Module(new ysyx_26030103_mcycle(XLEN))

  Mstatus.io.clk := io.clk; Mstatus.io.rst := io.rst
  Mtvec.io.clk := io.clk; Mtvec.io.rst := io.rst
  Mepc.io.clk := io.clk; Mepc.io.rst := io.rst
  Mcause.io.clk := io.clk; Mcause.io.rst := io.rst
  StvecOpt.foreach { m => m.io.clk := io.clk; m.io.rst := io.rst }
  SepcOpt.foreach { m => m.io.clk := io.clk; m.io.rst := io.rst }
  ScauseOpt.foreach { m => m.io.clk := io.clk; m.io.rst := io.rst }
  Mcycle.io.clk := io.clk; Mcycle.io.rst := io.rst

  val Mscratch = RegInit(0.U(XLEN.W))
  val SscratchOpt = if (hasS) Some(RegInit(0.U(XLEN.W))) else None
  // independent 视图需要独立 sstatus；bound 视图直接使用 mstatus 的字段。
  val SstatusRegOpt =
    if (hasS && profile.csrViewMode == "independent") Some(RegInit(0.U(32.W)))
    else None
  val MedelegOpt = if (hasS) Some(RegInit(0.U(32.W))) else None
  val MidelegOpt = if (hasS) Some(RegInit(0.U(32.W))) else None
  val Mie = RegInit(0.U(32.W))
  val Mip = RegInit(0.U(32.W))
  val SieOpt =
    if (hasS && profile.csrViewMode == "independent") Some(RegInit(0.U(32.W)))
    else None
  val SipOpt =
    if (hasS && profile.csrViewMode == "independent") Some(RegInit(0.U(32.W)))
    else None
  val ScounterenOpt = if (hasS) Some(RegInit(0.U(32.W))) else None
  val Mcounteren = RegInit(0.U(32.W))
  val Mcountinhibit = RegInit(0.U(32.W))
  Mcycle.io.Enable := !Mcountinhibit(0)
  val Mtval = RegInit(0.U(XLEN.W))
  // fcsr = {frm[2:0], fflags[4:0]}；复位值 0（RISC-V 允许实现定义，
  // 0 使裸机/回放有确定初值）。
  val FFlagsReg = RegInit(0.U(5.W))
  val FrmReg = RegInit(0.U(3.W))
  val Minstret = RegInit(0.U(64.W))
  // RV64 的 pmpcfg0/pmpcfg2 是 64 位 CSR，分别容纳 8 个 8 位配置项；
  // RV32 的 pmpcfg0..3 才是 4x32 位。此前 RV64 也按 4x32 位实现，
  // 会把 OpenSBI 写入 pmpcfg0 的高 32 位（entry 4..7）丢掉，导致
  // 全内存 NAPOT entry 丢失、S 模式取指被 PMP 拒绝。
  private val PmpEntriesPerCfg = if (XLEN == 64) 8 else 4
  private val PmpCfgWords = 16 / PmpEntriesPerCfg
  private val PmpCfgWordBits = PmpEntriesPerCfg * 8
  private val PmpCfgMask =
    BigInt("0f" * PmpEntriesPerCfg, 16)
  val PmpcfgReg = RegInit(
    VecInit(Seq.fill(PmpCfgWords)(0.U(PmpCfgWordBits.W))))
  val PmpaddrReg = RegInit(VecInit(Seq.fill(16)(0.U(XLEN.W))))
  val StvalOpt = if (hasS) Some(RegInit(0.U(XLEN.W))) else None
  val SatpRegOpt = if (hasS) Some(RegInit(0.U(XLEN.W))) else None
  val SatpReadData =
    if (hasS) {
      val SatpReg = SatpRegOpt.get
      (SatpReg & SatpReadMaskValue.U(XLEN.W)) | SatpReadSetValue.U(XLEN.W)
    } else 0.U(XLEN.W)

  // 年长的 FP 指令可能在 WBU 退休的同一拍把 FS 置 Dirty；EXU 里的
  // 年轻 CSR 指令必须看到这个更新。用有效值参与所有 trap/CSR 运算，
  // 并在寄存器写回时持久化。仅产生非零 fflags、未写 f 寄存器
  // （例如 FCMP/FCLASS）时也属于修改浮点状态；这里必须用寄存器隔开
  // EXU 的 FPCommit（它依赖 IrqCommit），否则会形成组合环。
  val FpFlagsDirtyReg =
    RegNext(io.FPCommit && io.FPFlags =/= 0.U(5.W), false.B)
  private val FsDirtyValue = (Mstatus.io.rdata & ~"h6000".U(32.W)) |
    (if (UseF) "h6000".U(32.W) else 0.U(32.W))
  val FsDirtyCommit = UseF.B && (io.FPStateDirtyCommit || FpFlagsDirtyReg)
  val Mstatus_rdata = Mux(FsDirtyCommit, FsDirtyValue, Mstatus.io.rdata)
  // M-only 时特权级恒为 M，不生成寄存器；有 S 或 U 时需要保存可达的
  // 特权级。M+U 没有 S 级，但 MRET/异常仍会在 M 与 U 之间切换。
  val Privilege: UInt =
    if (hasS || hasU) RegInit(3.U(2.W)) else 3.U(2.W)
  val MstatusReadData =
    (Mstatus_rdata & MstatusReadMaskValue.U(32.W)) |
      (profile.csrMstatusReadSet.toLong & 0xffffffffL).U(32.W)
  // 32 位 CSR 在 RV64 下读回高 32 位为 0；mstatus 例外：SXL/UXL 固定为 2。
  val MstatusReadDataWide = MstatusArchValue(MstatusReadData)
  val Sstatus_rdata =
    if (!hasS) 0.U(32.W)
    else if (profile.csrViewMode == "independent") SstatusRegOpt.get
    else MstatusReadData & SstatusMask
  // sstatus 是 mstatus 的受限视图，但 SD 仍是 FS==3 的只读汇总位
  // （RV32 bit31 / RV64 bit63）；读口必须和 mstatus/NEMU 一致。
  // RV64 下还要补 SXL/UXL=2（bit[35:32]=0xA），与 MstatusArchValue 一致。
  val SstatusArchValue =
    Widen32(Sstatus_rdata) |
      (if (XLEN == 64) "h0000000a00000000".U(XLEN.W) else 0.U(XLEN.W)) |
      MstatusSdBit(Sstatus_rdata(14, 13))
  val Mtvec_rdata = Mtvec.io.rdata
  val Mepc_rdata = Mepc.io.rdata
  val Mcause_rdata = Mcause.io.rdata
  val Stvec_rdata = StvecOpt.map(_.io.rdata).getOrElse(0.U(XLEN.W))
  val Sepc_rdata = SepcOpt.map(_.io.rdata).getOrElse(0.U(XLEN.W))
  val Scause_rdata = ScauseOpt.map(_.io.rdata).getOrElse(0.U(XLEN.W))
  // RV64 的 mcycle 直接返回完整 64 位；RV32 继续按 mcycle/mcycleh 选择。
  val Mcycle_rdata = if (XLEN == 64) Mcycle.io.rdataFull else Mcycle.io.rdata
  val Mcycleh_rdata = Mcycle.io.rdataFull(63, 32)
  val Minstret_rdata =
    if (XLEN == 64) Minstret else Minstret(31, 0)
  val Minstreth_rdata = Minstret(63, 32)
  // TIME 来自 CLINT 的 mtime。RV64 直接返回完整 64 位；RV32 的 time
  // 返回低 32 位，timeh 返回 bit[63:32]。
  val Time_rdata = if (XLEN == 64) io.Mtime else io.Mtime(31, 0)
  val Timeh_rdata = io.Mtime(63, 32)
  // misa.MXL: 1=RV32, 2=RV64；I 始终置位，A/C/F/D 随配置置位，
  // S/U 分别随 HAS_S/HAS_U 置位。位编号按 RISC-V 规范：
  // bit0=A，bit2=C，bit3=D，bit5=F，bit8=I，bit18=S，bit20=U。
  // misa 整体只读（IDU 拒绝任何写意图），因此这里只生成常量读口。
  val Misa_rdata = {
    val Mxl = if (XLEN == 64) BigInt(2) << 62 else BigInt(1) << 30
    val AExt = if (UseA) BigInt(1) << 0 else BigInt(0)
    val CExt = if (UseC) BigInt(1) << 2 else BigInt(0)
    val DExt = if (UseD) BigInt(1) << 3 else BigInt(0)
    val FExt = if (UseF) BigInt(1) << 5 else BigInt(0)
    val SExt = if (hasS) BigInt(1) << 18 else BigInt(0)
    val UExt = if (hasU) BigInt(1) << 20 else BigInt(0)
    (Mxl | BigInt(0x100) | AExt | CExt | DExt | FExt |
      SExt | UExt).U(XLEN.W)
  }
  val Medeleg_rdata = MedelegOpt.getOrElse(0.U(32.W))
  val Mideleg_rdata = MidelegOpt.getOrElse(0.U(32.W))
  val Sscratch_rdata = SscratchOpt.getOrElse(0.U(32.W))
  val Stval_rdata = StvalOpt.getOrElse(0.U(32.W))
  val Scounteren_rdata = ScounterenOpt.getOrElse(0.U(32.W))
  // 写 32 位 CSR 时只取 CSRWriteData 的低 32 位，高半部保持 0。
  private def Write32(Old: UInt): UInt = CSRWriteData(Widen32(Old))(31, 0)

  val IrqLatch = RegInit(false.B)
  when(io.Interrupt || io.TimerInterrupt) { IrqLatch := true.B }
  val SInterruptMask = "h00000222".U(32.W) // SSIP/STIP/SEIP
  // HAS_S=n 时不存在 S 级中断视图：timer/external 全部按 M 级中断处理。
  val TimerView =
    if (!hasS) 0.U(32.W)
    else if (profile.timerPath == "direct_stip")
      (if (profile.hasSTimerIrq)
        Mux(io.TimerInterrupt, "h00000020".U(32.W), 0.U(32.W))
      else 0.U(32.W))
    else
      Mux(io.TimerInterrupt, "h00000080".U(32.W), 0.U(32.W))
  val MTimerView =
    if (hasS) 0.U(32.W)
    else Mux(io.TimerInterrupt, "h00000080".U(32.W), 0.U(32.W))
  val ExternalSView = if (hasS && profile.hasSExternalIrq)
    Mux(io.Interrupt, "h00000200".U(32.W), 0.U(32.W))
  else 0.U(32.W)
  val ExternalMView = if (!hasS || !profile.hasSExternalIrq)
    Mux(io.Interrupt, "h00000800".U(32.W), 0.U(32.W))
  else 0.U(32.W)
  val MipState = Mip | TimerView | MTimerView | ExternalSView | ExternalMView
  val SieState =
    if (!hasS) 0.U(32.W)
    else if (profile.csrViewMode == "independent") SieOpt.get
    else Mie & SInterruptMask
  val SipState =
    if (!hasS) 0.U(32.W)
    else if (profile.csrViewMode == "independent") SipOpt.get
    else MipState & SInterruptMask
  val MIntPending =
    if (hasS) MipState & Mie & ~Mideleg_rdata
    else MipState & Mie
  val SIntPending = if (hasS) SipState & SieState & Mideleg_rdata else false.B
  val MIntTaken = MIntPending.orR && Mstatus_rdata(3)
  val SStatusIE =
    if (!hasS) false.B
    else if (profile.csrViewMode == "independent") SstatusRegOpt.get(1)
    else Mstatus_rdata(1)
  val SIntTaken =
    if (hasS) SIntPending.orR && SStatusIE && Privilege =/= 3.U
    else false.B
  val HasInterrupt = MIntTaken || SIntTaken
  io.IrqPending := HasInterrupt
  // CSR 写只在真正接受中断时被压掉；原始中断线为高但 MIE=0 时软件仍可写 CSR。
  val CSRWen = (csrrwWen || csrrsWen || csrrcWen) && !HasInterrupt && CsrImplemented
  val IsException = io.IsEcall || io.IsEbreak || HasInterrupt || io.TrapValid

  def pendingCause(pending: UInt, sMode: Boolean): UInt = {
    val base = BigInt(0x80000000L)
    if (sMode) {
      Mux(pending(9), (base | 9).U(32.W),
        Mux(pending(5), (base | 5).U(32.W), (base | 1).U(32.W)))
    } else {
      Mux(pending(11), (base | 11).U(32.W),
        Mux(pending(7), (base | 7).U(32.W), (base | 3).U(32.W)))
    }
  }
  val MIrqCause = pendingCause(MIntPending, sMode = false)
  val SIrqCause = if (hasS) pendingCause(SIntPending, sMode = true) else 0.U(32.W)
  val IrqCause = if (hasS) Mux(SIntTaken, SIrqCause, MIrqCause) else MIrqCause

  val ExceptionCause = WireDefault(0.U(32.W))
  when(io.MemTrap) {
    ExceptionCause := io.TrapCause
  }.elsewhen(HasInterrupt) {
    ExceptionCause := IrqCause
  }.elsewhen(io.TrapValid) {
    ExceptionCause := io.TrapCause
  }.elsewhen(io.IsEbreak) {
    ExceptionCause := 3.U(32.W)
  }.otherwise {
    // ecall 的 cause 由当前特权级决定：U=8，S=9，M=11。
    // HAS_U=n 时 U 级不可达，直接裁掉 cause=8 的选择路径；HAS_S=n
    // 但 HAS_U=y（M+U）时 U 的 ecall 仍是 8，S 级路径不存在。
    ExceptionCause := (if (!hasS) {
      if (hasU) {
        Mux(Privilege === 0.U, 8.U(32.W), 11.U(32.W))
      } else {
        11.U(32.W)
      }
    } else if (hasU) {
      Mux(Privilege === 0.U, 8.U(32.W),
        Mux(Privilege === 1.U, 9.U(32.W), 11.U(32.W)))
    } else {
      Mux(Privilege === 1.U, 9.U(32.W), 11.U(32.W))
    })
  }

  val HasIrqCommit = io.Enable && HasInterrupt && !io.MemTrap
  when(HasIrqCommit) { IrqLatch := false.B }
  io.IrqCommit := HasIrqCommit
  val ExceptionCommit = io.MemTrap || (io.Enable && IsException)
  io.TrapCommit := ExceptionCommit
  io.CommittedCause := ExceptionCause

  // 异常委托：只有低于 M 的异常/中断才能被委托给 S。HAS_S=n 时委托
  // 寄存器和选择逻辑都不生成，TrapToS 恒为 0。
  val TrapToS = if (hasS) {
    val CauseIndex = ExceptionCause(4, 0)
    val Delegation = Mux(ExceptionCause(31), Mideleg_rdata, Medeleg_rdata)
    val Delegated = Delegation(CauseIndex)
    Privilege =/= 3.U && Delegated
  } else false.B

  val MstatusExceptionData = {
    val mTrap = legalizeMstatus(
      (Mstatus_rdata & ~"h00001888".U(32.W)) |
        (Privilege << 11) | (Mstatus_rdata(3) << 7)
    )
    if (hasS) {
      val sTrap = legalizeMstatus(
        (Mstatus_rdata & ~"h00000122".U(32.W)) |
          (Privilege(0) << 8) | (Mstatus_rdata(1) << 5)
      )
      Mux(TrapToS, sTrap, mTrap)
    } else mTrap
  }
  // MPP 恢复；MIE <- MPIE；MPIE <- 1；MPP 保持最低支持特权级。
  // HAS_U=n 时 legalizeMstatus 已把 MPP 的最低值夹到 S。
  val MstatusMretData = legalizeMstatus(
    (Mstatus_rdata & ~"h00001888".U(32.W)) |
      (Mstatus_rdata(7) << 3) | (1.U << 7)
  )
  // SRET：SPP 决定返回 S/U；SIE <- SPIE；SPIE <- 1；SPP 回到最低支持级。
  // HAS_U=n 时 SPP 固定为 1，SRET 只能返回 S。
  val MstatusSretDataOpt = if (hasS) Some(legalizeMstatus(
    (Mstatus_rdata & ~"h00000122".U(32.W)) |
      (Mstatus_rdata(5) << 1) | (1.U << 5)
  )) else None

  val MstatusWen =
    if (hasS && profile.csrViewMode == "bound" && csrOn(0x100))
      CSRWen && (IsCSRAddress(CSR_MSTATUS) || IsCSRAddress(CSR_SSTATUS))
    else CSRWen && IsCSRAddress(CSR_MSTATUS)
  val SstatusWen =
    if (hasS && profile.csrViewMode == "independent" && csrOn(0x100))
      CSRWen && IsCSRAddress(CSR_SSTATUS)
    else false.B
  val MstatusWriteMask = MstatusWriteMaskValue.U(32.W)
  val MstatusCSRData = {
    val rawWrite = CSRWriteData(MstatusReadDataWide)(31, 0)
    val masked = (Mstatus_rdata & ~MstatusWriteMask) |
      (rawWrite & MstatusWriteMask)
    if (hasS && csrOn(0x100)) {
      Mux(
        IsCSRAddress(CSR_SSTATUS),
        legalizeMstatus((Mstatus_rdata & ~SstatusMask) |
          ((masked | (Mstatus_rdata & ~MstatusWriteMask)) & SstatusMask)),
        legalizeMstatus(masked)
      )
    } else legalizeMstatus(masked)
  }
  // 写 fflags/frm/fcsr 也属于修改浮点状态：mstatus.FS 应变 Dirty。
  val FpCsrWriteIntent =
    CSRWen && (IsCSRAddress(CSR_FFLAGS) || IsCSRAddress(CSR_FRM) ||
      IsCSRAddress(CSR_FCSR))
  val FsDirtyData = FsDirtyValue
  val MstatusWdataSel = Seq(
    ExceptionCommit -> MstatusExceptionData,
    MretCommit -> MstatusMretData
  ) ++ (if (hasS) Seq(SretCommit -> MstatusSretDataOpt.get) else Seq.empty) ++ Seq(
    MstatusWen -> MstatusCSRData,
    FpCsrWriteIntent -> FsDirtyData,
    FsDirtyCommit -> FsDirtyData
  )
  Mstatus.io.wen := MstatusWen || ExceptionCommit || MretCommit ||
    (if (hasS && profile.csrViewMode == "bound") SretCommit else false.B) ||
    FpCsrWriteIntent || FsDirtyCommit
  Mstatus.io.wdata := MuxCase(MstatusCSRData, MstatusWdataSel)
  if (hasS && profile.csrViewMode == "independent") {
    val SstatusReg = SstatusRegOpt.get
    val sWrite = legalizeMstatus((SstatusReg & ~SstatusMask) |
      (Write32(SstatusReg) & SstatusMask))
    val sTrap = legalizeMstatus((SstatusReg & ~"h00000122".U(32.W)) |
      (SstatusReg(1) << 5) | (Privilege(0) << 8))
    val sRet = legalizeMstatus((SstatusReg & ~"h00000122".U(32.W)) |
      (SstatusReg(5) << 1) | (1.U << 5))
    when(SstatusWen) { SstatusReg := sWrite }
    when(ExceptionCommit && TrapToS) { SstatusReg := sTrap }
    when(SretCommit) { SstatusReg := sRet }
  }

  Mtvec.io.wen := CSRWen && IsCSRAddress(CSR_MTVEC)
  Mtvec.io.wdata := CSRWriteData(Mtvec_rdata)
  StvecOpt.foreach { Stvec =>
    Stvec.io.wen := CSRWen && IsCSRAddress(CSR_STVEC)
    Stvec.io.wdata := CSRWriteData(Stvec_rdata)
  }

  // 只有 M 入口写 mepc/mcause；委托到 S 时写 sepc/scause/stval。
  val MepcWen = CSRWen && IsCSRAddress(CSR_MEPC) && !TrapToS
  Mepc.io.wen := MepcWen || (ExceptionCommit && !TrapToS)
  Mepc.io.ExceptionWE := ExceptionCommit && !TrapToS
  Mepc.io.ExceptionData := io.pc
  Mepc.io.wdata := Mux(ExceptionCommit && !TrapToS, io.pc, CSRWriteData(Mepc_rdata))
  val McauseWen = CSRWen && IsCSRAddress(CSR_MCAUSE) && !TrapToS
  Mcause.io.wen := McauseWen || (ExceptionCommit && !TrapToS)
  Mcause.io.wdata := Mux(ExceptionCommit && !TrapToS, WidenCause32(ExceptionCause),
    CSRWriteData(Mcause_rdata))

  SepcOpt.foreach { Sepc =>
    val SepcWen = CSRWen && IsCSRAddress(CSR_SEPC)
    Sepc.io.wen := SepcWen || (ExceptionCommit && TrapToS)
    Sepc.io.ExceptionWE := ExceptionCommit && TrapToS
    Sepc.io.ExceptionData := io.pc
    Sepc.io.wdata := Mux(ExceptionCommit && TrapToS, io.pc, CSRWriteData(Sepc_rdata))
  }
  ScauseOpt.foreach { Scause =>
    val ScauseWen = CSRWen && IsCSRAddress(CSR_SCAUSE)
    Scause.io.wen := ScauseWen || (ExceptionCommit && TrapToS)
    Scause.io.wdata := Mux(ExceptionCommit && TrapToS, WidenCause32(ExceptionCause),
      CSRWriteData(Scause_rdata))
  }

  // trap 写 tval 的统一取值：有明确 tval 的异常（访存地址、压缩非法
  // 指令编码等）直接使用输入；没有专门 tval 的异常回退到故障 PC。
  // 不能用“tval != 0”判断，因为全零的非法压缩编码是合法的 tval=0。
  // ecall 的 mtval 按规范固定为 0；只有当前提交的 ecall 生效，
  // MemTrap/其他异常优先级更高，仍走原来的 tval 路径。
  // 中断没有 tval，mtval 同样固定为 0。
  // breakpoint(cause=3) 没有专门 tval，继续回退到故障 PC。
  val EcallTvalCommit = io.Enable && io.IsEcall && !io.MemTrap &&
    !HasInterrupt && !io.TrapValid && !io.IsEbreak
  val InterruptTvalCommit = io.Enable && HasInterrupt && !io.MemTrap
  val TrapTvalValue =
    Mux(InterruptTvalCommit || EcallTvalCommit, 0.U(XLEN.W),
      Mux(io.TrapTvalValid, io.TrapTval, io.pc))
  // IALIGN=16 时 mepc/sepc 只清 bit0；IALIGN=32 清低两位。
  private def AlignEPC(Value: UInt): UInt =
    if (UseC) Cat(Value(XLEN - 1, 1), 0.U(1.W))
    else Cat(Value(XLEN - 1, 2), 0.U(2.W))
  if (hasS) {
    val Stval = StvalOpt.get
    when(ExceptionCommit && TrapToS) {
      Stval := TrapTvalValue
    }.elsewhen(ExceptionCommit) {
      Mtval := TrapTvalValue
    }.elsewhen(CSRWen && IsCSRAddress(CSR_STVAL)) {
      Stval := CSRWriteData(Stval)
    }
  } else {
    when(ExceptionCommit) {
      Mtval := TrapTvalValue
    }
  }
  when(CSRWen && IsCSRAddress(CSR_MTVAL)) { Mtval := CSRWriteData(Mtval) }
  when(CSRWen && IsCSRAddress(CSR_MCOUNTEREN)) { Mcounteren := Write32(Mcounteren) }
  when(CSRWen && IsCSRAddress(CSR_MCOUNTINHIBIT)) { Mcountinhibit := Write32(Mcountinhibit) }
  // 浮点运算在 EXU 提交时累积 fflags；CSR 写覆盖顺序放在后面，保证
  // 同一拍即使出现（架构上单发射不会同时发生）也以软件写为准。
  when(io.FPCommit) { FFlagsReg := FFlagsReg | io.FPFlags }
  when(CSRWen && IsCSRAddress(CSR_FFLAGS)) {
    FFlagsReg := CSRWriteData(Widen32(FFlagsReg))(4, 0)
  }
  when(CSRWen && IsCSRAddress(CSR_FRM)) {
    FrmReg := CSRWriteData(Widen32(FrmReg))(2, 0)
  }
  when(CSRWen && IsCSRAddress(CSR_FCSR)) {
    val FcsrWrite = CSRWriteData(Widen32(Cat(FrmReg, FFlagsReg)))
    FrmReg := FcsrWrite(7, 5)
    FFlagsReg := FcsrWrite(4, 0)
  }
  io.Frm := FrmReg
  io.Fcsr := Cat(FrmReg, FFlagsReg)
  for (word <- 0 until PmpCfgWords) {
    // RV64：pmpcfg0=0x3a0（entry 0..7），pmpcfg2=0x3a2（entry 8..15）。
    // RV32：pmpcfg0..3=0x3a0..0x3a3（每个 4 项）。
    val cfgAddress =
      if (XLEN == 64) 0x3a0 + word * 2 else 0x3a0 + word
    when(CSRWen && io.CSRAddress === cfgAddress.U(12.W)) {
      PmpcfgReg(word) :=
        CSRWriteData(PmpcfgReg(word)) & PmpCfgMask.U(PmpCfgWordBits.W)
    }
  }
  for (entry <- 0 until 16) {
    when(CSRWen && io.CSRAddress === (0x3b0 + entry).U(12.W)) {
      // RV32 PMP 地址为 30 位；RV64 pmpaddr 为标准 54 位。
      if (XLEN == 64) {
        PmpaddrReg(entry) := CSRWriteData(PmpaddrReg(entry)) &
          "h003fffffffffffff".U(64.W)
      } else {
        PmpaddrReg(entry) := Write32(PmpaddrReg(entry)) & "h3fffffff".U(32.W)
      }
    }
  }
  // 标准 mcountinhibit：bit0=CY，bit2=IR。
  // minstret 只统计真正提交的指令：中断提交（IdleIrqCommit 或抢占当前
  // 指令的 IrqCommit）没有指令退休，不能自增。minstret/minstreth 是
  // 可写计数器；写口优先于本拍自增，避免写入指令再被计数一次。
  val MinstretWen = CSRWen &&
    (IsCSRAddress(CSR_MINSTRET) || IsCSRAddress(CSR_MINSTRETH))
  when(MinstretWen) {
    val Written = CSRWriteData(Minstret)
    if (XLEN == 64) {
      Minstret := Written
    } else {
      Minstret := Cat(
        Mux(IsCSRAddress(CSR_MINSTRETH), Written(31, 0), Minstret(63, 32)),
        Mux(IsCSRAddress(CSR_MINSTRET), Written(31, 0), Minstret(31, 0))
      )
    }
  }.elsewhen(io.Enable && !HasIrqCommit && !Mcountinhibit(2) && !io.MemTrap) {
    Minstret := Minstret + 1.U
  }
  SscratchOpt.foreach { Sscratch =>
    when(CSRWen && IsCSRAddress(CSR_SSCRATCH)) { Sscratch := CSRWriteData(Sscratch) }
  }
  when(CSRWen && IsCSRAddress(CSR_MSCRATCH)) { Mscratch := CSRWriteData(Mscratch) }
  MedelegOpt.foreach { Medeleg =>
    when(CSRWen && IsCSRAddress(CSR_MEDELEG)) { Medeleg := Write32(Medeleg) }
  }
  MidelegOpt.foreach { Mideleg =>
    when(CSRWen && IsCSRAddress(CSR_MIDELEG)) { Mideleg := Write32(Mideleg) }
  }
  when(CSRWen && IsCSRAddress(CSR_MIE)) { Mie := Write32(Mie) }
  when(CSRWen && IsCSRAddress(CSR_MIP)) { Mip := Write32(Mip) }
  if (hasS && profile.csrViewMode == "independent") {
    val Sie = SieOpt.get
    val Sip = SipOpt.get
    when(CSRWen && IsCSRAddress(CSR_SIE)) { Sie := Write32(Sie) }
    when(CSRWen && IsCSRAddress(CSR_SIP)) { Sip := Write32(Sip) }
  } else if (hasS) {
    when(CSRWen && IsCSRAddress(CSR_SIE)) {
      Mie := (Mie & ~SInterruptMask) | (Write32(Mie) & SInterruptMask)
    }
    when(CSRWen && IsCSRAddress(CSR_SIP)) {
      Mip := (Mip & ~SInterruptMask) | (Write32(Mip) & SInterruptMask)
    }
  }
  ScounterenOpt.foreach { Scounteren =>
    when(CSRWen && IsCSRAddress(CSR_SCOUNTEREN)) {
      Scounteren := Write32(Scounteren)
    }
  }
  val SatpWen = if (hasS) CSRWen && IsCSRAddress(CSR_SATP) else false.B
  SatpRegOpt.foreach { SatpReg =>
    when(SatpWen) {
      val raw = CSRWriteData(SatpReg)
      val mask = SatpWriteMaskValue.U(XLEN.W)
      val data = (SatpReg & ~mask) | (raw & mask)
      if (XLEN == 32) {
        // Sv32 satp.MODE 只支持 0/1。
        SatpReg := Cat(Mux(data(31), 1.U(1.W), 0.U(1.W)), data(30, 0))
      } else {
        // RV64 satp：MODE[63:60]。标准模式只有 0/8(Sv39)/9(Sv48)；
        // 显式 CUSTOM 几何使用独立的保留编码 10..15（非标准扩展）。
        // 未配置/不匹配的 MODE 按 WARL 收敛到 Bare(0)，不会在几何
        // 不匹配时静默产生错误翻译，也不会把 CUSTOM 伪装成 Sv39/Sv48。
        val requested = data(63, 60)
        val legalMode = Mux(
          requested === 0.U, 0.U(4.W),
          Mux(requested === profile.satpMode.U, profile.satpMode.U(4.W),
            0.U(4.W)))
        SatpReg := Cat(legalMode, data(59, 0))
      }
    }
  }
  io.Satp := SatpRegOpt.getOrElse(0.U(XLEN.W))
  if (hasS || hasU) {
    val MretPrivilege: UInt =
      if (hasU) Mstatus_rdata(12, 11)
      else if (hasS) Mux(Mstatus_rdata(12), 3.U(2.W), 1.U(2.W))
      else 3.U(2.W)
    if (hasS) {
      val SretPrivilege =
        if (hasU) Mux(Mstatus_rdata(8), 1.U(2.W), 0.U(2.W))
        else 1.U(2.W)
      when(ExceptionCommit) {
        Privilege := Mux(TrapToS, 1.U, 3.U)
      }.elsewhen(MretCommit) {
        Privilege := MretPrivilege
      }.elsewhen(SretCommit) {
        Privilege := SretPrivilege
      }
    } else {
      // M+U：没有 S 入口，所有异常/中断都进入 M；MRET 按 MPP 返回 M/U。
      when(ExceptionCommit) {
        Privilege := 3.U
      }.elsewhen(MretCommit) {
        Privilege := MretPrivilege
      }
    }
  }
  io.Privilege := Privilege
  io.CurrentMstatus := MstatusArchValue(Mstatus_rdata)
  io.FlushTLB := (if (hasS) SfenceCommit || SatpWen else false.B)

  val McycleAccess = IsCSRAddress(CSR_MCYCLE) || IsCSRAddress(CSR_MCYCLEH)
  Mcycle.io.wen := CSRWen && McycleAccess
  Mcycle.io.SelectHigh := IsCSRAddress(CSR_MCYCLEH)
  Mcycle.io.wdata := CSRWriteData(Mcycle_rdata)

  // CSR 读多路选择只包含当前配置真正实现的地址。HAS_S=n/HAS_U=n 时
  // 对应的 S/U CSR 不会留下读 mux 和只读常量。
  val CSRMap = scala.collection.mutable.ArrayBuffer[(UInt, UInt)]()
  def addCsr(addr: UInt, data: => UInt, enabled: Boolean): Unit = {
    if (enabled) { CSRMap += (addr -> data) }
  }
  addCsr(CSR_MSTATUS, MstatusReadDataWide, csrOn(0x300))
  addCsr(CSR_SSTATUS, SstatusArchValue, csrOn(0x100))
  addCsr(CSR_FFLAGS, Widen32(FFlagsReg), UseF)
  addCsr(CSR_FRM, Widen32(FrmReg), UseF)
  addCsr(CSR_FCSR, Widen32(Cat(FrmReg, FFlagsReg)), UseF)
  addCsr(CSR_MTVEC, Mtvec_rdata, csrOn(0x305))
  addCsr(CSR_STVEC, Stvec_rdata, csrOn(0x105))
  addCsr(CSR_MEPC, Mepc_rdata, csrOn(0x341))
  addCsr(CSR_SEPC, Sepc_rdata, csrOn(0x141))
  addCsr(CSR_MCAUSE, Mcause_rdata, csrOn(0x342))
  addCsr(CSR_SCAUSE, Scause_rdata, csrOn(0x142))
  addCsr(CSR_MSCRATCH, Mscratch, csrOn(0x340))
  addCsr(CSR_SSCRATCH, Sscratch_rdata, csrOn(0x140))
  addCsr(CSR_MEDELEG, Widen32(Medeleg_rdata), csrOn(0x302))
  addCsr(CSR_MIDELEG, Widen32(Mideleg_rdata), csrOn(0x303))
  addCsr(CSR_MIE, Widen32(Mie), csrOn(0x304))
  // mip 读口必须包含 CLINT/PLIC 实时拉起的 pending 位，不能只返回
  // 软件可写寄存器 Mip。否则 M 模式读取 mip 时看不到 MTIP，S 模式
  // 通过 mip 观察 STIP 的路径也会与 sip 不一致。
  addCsr(CSR_MIP, Widen32(MipState), csrOn(0x344))
  addCsr(CSR_SIE, Widen32(SieState), csrOn(0x104))
  addCsr(CSR_SIP, Widen32(SipState), csrOn(0x144))
  addCsr(CSR_SCOUNTEREN, Widen32(Scounteren_rdata), csrOn(0x106))
  addCsr(CSR_STVAL, Stval_rdata, csrOn(0x143))
  addCsr(CSR_SATP, SatpReadData, csrOn(0x180))
  addCsr(CSR_MCYCLE, Mcycle_rdata, csrOn(0xb00))
  addCsr(CSR_MCYCLEH, Widen32(Mcycleh_rdata), csrOn(0xb80))
  addCsr(CSR_MINSTRET, Minstret_rdata, csrOn(0xb02))
  addCsr(CSR_MINSTRETH, Widen32(Minstreth_rdata), csrOn(0xb82))
  // 用户计数器别名：rdcycle/rdtime/rdinstret 及其 RV32 高半字。
  // CYCLE/CYCLEH 跟随 mcycle，INSTRET/INSTRETH 跟随 minstret；
  // TIME/TIMEH 直接来自 CLINT mtime，不需要对应的机器计数器。
  addCsr(CSR_CYCLE, Mcycle_rdata, enabled = true)
  addCsr(CSR_CYCLEH, Widen32(Mcycleh_rdata), enabled = true)
  addCsr(CSR_TIME, Time_rdata, enabled = true)
  addCsr(CSR_TIMEH, Widen32(Timeh_rdata), enabled = true)
  addCsr(CSR_INSTRET, Minstret_rdata, enabled = true)
  addCsr(CSR_INSTRETH, Widen32(Minstreth_rdata), enabled = true)
  addCsr(CSR_MISA, Misa_rdata, csrOn(0x301))
  addCsr(CSR_MTVAL, Mtval, csrOn(0x343))
  addCsr(CSR_MCOUNTEREN, Widen32(Mcounteren), csrOn(0x306))
  addCsr(CSR_MCOUNTINHIBIT, Widen32(Mcountinhibit), csrOn(0x320))
  addCsr(CSR_MVENDORID, Widen32(Mvendorid_rdata), csrOn(0xf11))
  addCsr(CSR_MARCHID, Widen32(Marchid_rdata), csrOn(0xf12))
  addCsr(CSR_MIMPID, Widen32(Mimpid_rdata), csrOn(0xf13))
  addCsr(CSR_MHARTID, Widen32(Mhartid_rdata), csrOn(0xf14))
  for (i <- 0 until 4) {
    val cfgAddress = if (XLEN == 64) 0x3a0 + i * 2 else 0x3a0 + i
    addCsr(
      cfgAddress.U(12.W),
      PmpcfgReg(i),
      csrOn(cfgAddress)
    )
  }
  for (i <- 0 until 16) {
    addCsr((0x3b0 + i).U(12.W), PmpaddrReg(i), csrOn(0x3b0 + i))
  }
  val CsrRawRdata =
    if (CSRMap.isEmpty) 0.U(XLEN.W)
    else MuxLookup(io.CSRAddress, 0.U(XLEN.W))(CSRMap.toSeq)
  io.CSR_rdata := Mux(CsrImplemented,
    (CsrRawRdata & CsrReadMask) | CsrReadSet, 0.U(XLEN.W))

  val MtvecBase = Cat(Mtvec_rdata(XLEN - 1, 2), 0.U(2.W))
  val CauseOffsetWide = ExceptionCause(29, 0) << 2
  val CauseOffset = Widen32(CauseOffsetWide(31, 0))
  val MtvecTrapTarget = Mux(
    Mtvec_rdata(1, 0) === 1.U && ExceptionCause(31),
    MtvecBase + CauseOffset, MtvecBase)
  val StvecTrapTarget =
    if (hasS) {
      val StvecBase = Cat(Stvec_rdata(XLEN - 1, 2), 0.U(2.W))
      Mux(Stvec_rdata(1, 0) === 1.U && ExceptionCause(31),
        StvecBase + CauseOffset, StvecBase)
    } else 0.U(XLEN.W)

  io.ExceptionTaken := ExceptionCommit || MretCommit ||
    (if (hasS) SretCommit else false.B)
  io.ExceptionTarget := (
    if (hasS) {
      Mux(
        ExceptionCommit,
        Mux(TrapToS, StvecTrapTarget, MtvecTrapTarget),
        Mux(MretCommit, Mepc_rdata, Sepc_rdata)
      )
    } else {
      // HAS_S=n：不存在 S 入口；异常/中断进入 M，MRET 返回 mepc。
      Mux(ExceptionCommit, MtvecTrapTarget, Mepc_rdata)
    }
  )

  // 退休级快照表示本指令执行后的 CSR 状态。高半区也要带上 RV64
  // mstatus.SXL/UXL，保证 DiffTest 的 CSR 视图与 CSR 读口一致。
  io.StateMstatus := MstatusArchValue(MuxCase(Mstatus_rdata,
    Seq(
      ExceptionCommit -> MstatusExceptionData,
      MretCommit -> MstatusMretData
    ) ++ (if (hasS) Seq(SretCommit -> MstatusSretDataOpt.get) else Seq.empty) ++
      Seq(MstatusWen -> MstatusCSRData, FpCsrWriteIntent -> FsDirtyValue,
        FsDirtyCommit -> FsDirtyValue)))
  val MtvecCSRData = CSRWriteData(Mtvec_rdata)
  val MtvecLegalMode = Mux(MtvecCSRData(1, 0) === 1.U, 1.U(2.W), 0.U(2.W))
  io.StateMtvec := Mux(CSRWen && IsCSRAddress(CSR_MTVEC),
    Cat(MtvecCSRData(XLEN - 1, 2), MtvecLegalMode), Mtvec_rdata)
  io.StateMepc := Mux(ExceptionCommit && !TrapToS,
    AlignEPC(io.pc),
    Mux(MepcWen, AlignEPC(CSRWriteData(Mepc_rdata)), Mepc_rdata))
  io.StateMcause := Mux(ExceptionCommit && !TrapToS, WidenCause32(ExceptionCause),
    Mux(McauseWen, CSRWriteData(Mcause_rdata), Mcause_rdata))
  // 只在 trap 提交拍给出本次写入的 tval；普通指令/软件写 CSR 不参与
  // DiffTest 的 trap 注入。
  io.StateTval := Mux(ExceptionCommit, TrapTvalValue, 0.U(XLEN.W))
  io.McounterenOut := Mcounteren
  io.ScounterenOut := Scounteren_rdata
  for (entry <- 0 until 16) {
    io.PmpCfg(entry) := PmpcfgReg(entry / PmpEntriesPerCfg)(
      8 * (entry % PmpEntriesPerCfg) + 7,
      8 * (entry % PmpEntriesPerCfg)
    )
    io.PmpAddr(entry) := PmpaddrReg(entry)
  }
}
