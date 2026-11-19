package ysyx_26030103.exu
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.fpu.{ysyx_26030103_FPUFlags, ysyx_26030103_FPURm}

// 机器/监管者 CSR 与精确异常入口。页表翻译本身在 MMU 中完成；这里负责
// satp、stvec/sepc/scause/stval 以及异常入口的特权级切换。
// HAS_S=n 时只生成 M 模式 CSR/异常入口，S 模式 CSR 和委托逻辑在
// elaboration 期直接省略；但 HAS_U=y 时仍保留 U 特权级，U 的所有
// 异常/中断直接进入 M（ecall cause=8）。HAS_U=n 时 U 级 CSR、
// ecall cause=8 和返回 U 的选择路径同样被裁掉。
class ysyx_26030103_CSR(
    val profile: _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile =
      _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile(),
    val XLEN: Int = ysyx_26030103_KconfigDefaults.NpcXlen,
    // RISCV_A 打开时 misa.A 必须置位；关闭时该位保持 0。
    val UseA: Boolean = false,
    // RISCV_C 打开时 misa.C 置位，mepc/sepc 只清 bit0（IALIGN=16）。
    val UseC: Boolean = false,
    // RISCV_F/D 打开时实现 fflags/frm/fcsr 与 mstatus.FS，misa.F/D 置位。
    val UseF: Boolean = false,
    val UseD: Boolean = false,
    // RISCV_M 打开时 misa.M 必须置位；MDU 是否例化由 EXU 单独决定。
    val UseM: Boolean = false,
    // 架构 PMU：Zihpm 的 mhpmcounter/mhpmevent 与 Sscofpmf 溢出控制。
    val PMUEnable: Boolean = false,
    val PMUZihpm: Boolean = true,
    val PMUSscofpmf: Boolean = true
) extends Module {
  import ysyx_26030103_mstatus.legalize
  require(XLEN == 32 || XLEN == 64, s"CSR XLEN 非法: $XLEN")
  require(!UseD || UseF, "CSR 的 D 扩展依赖 F 扩展")
  private val fflagsWidth = ysyx_26030103_FPUFlags.Width
  private val frmWidth = ysyx_26030103_FPURm.Width
  private val hasS = profile.hasS
  private val hasU = profile.hasU
  // medeleg 是 WARL：只有当前配置真正可能发生、且允许从 S 委托的异常
  // 对应位可写。cause 0..7 是 S 模式可处理的普通异常；cause 8 只有实现
  // U 模式时才存在；cause 9 是 ecall from S；12/13/15 是页错误，只在
  // MMU 打开时可能发生。cause 10/14、ecall from M(11)、double trap(16)
  // 以及高位保留位必须读回 0，不能被软件保存/恢复或能力探测误读。
  private val medelegMaskValue: BigInt = {
    var mask = (BigInt(1) << 10) - 1
    if (!hasU) mask &= ~(BigInt(1) << 8)
    if (profile.enabled) {
      mask |= (BigInt(1) << 12) | (BigInt(1) << 13) | (BigInt(1) << 15)
    }
    mask
  }
  private val medelegMask: UInt = medelegMaskValue.U(32.W)
  private val fullMask: BigInt =
    if (XLEN == 64) (BigInt(1) << 64) - 1 else 0xffffffffL
  // satp 在 RV64 下使用完整 64 位掩码；RV32 只保留低 32 位。
  private val satpReadMaskValue: BigInt =
    BigInt(profile.CSRSatpReadMask) & fullMask
  private val satpReadSetValue: BigInt =
    BigInt(profile.CSRSatpReadSet) & fullMask
  private val satpWriteMaskValue: BigInt =
    BigInt(profile.CSRSatpWriteMask) & fullMask
  // 32 位 CSR（mstatus/mie/mip 等）在 RV64 下零扩展到 XLEN；
  // mepc/mtvec/mtval/mscratch 等则天然使用完整 XLEN。
  private def widen32(value: UInt): UInt =
    if (XLEN == 32) value else Cat(0.U((XLEN - 32).W), value)
  // mcause/scause 的中断位在 XLEN-1，而本实现的 ExceptionCause 是 32 位
  // 编码。RV64 下必须把 bit31 搬到 bit63，而不是零扩展或普通符号扩展：
  //   0x80000009 -> 0x8000000000000009
  //   0x0000000b -> 0x000000000000000b
  // 零扩展会让 xv6 的 `scause == 0x8000000000000009` 判断失败。
  private def widenCause32(value: UInt): UInt =
    if (XLEN == 32) value
    else Cat(value(31), 0.U((XLEN - 32).W), value(30, 0))
  // RV64 的 mstatus.SXL/UXL 是 WARL，本实现只支持 64 位，因此对应特权级
  // 存在时固定读回 2。两个字段位于 bit[35:32]；未实现的 S/U 特权级必须
  // 读回 0，不能向软件暴露不存在的 XLEN 状态。
  // SD 是 FS==3 的只读汇总位：RV32 在 bit31，RV64 在 bit63；软件不能
  // 直接写它，但读回/NEMU DiffTest 必须一致。
  private def mstatusSdBit(fs: UInt): UInt =
    if (!UseF) 0.U(XLEN.W)
    else Mux(fs === 3.U,
      (BigInt(1) << (XLEN - 1)).U(XLEN.W), 0.U(XLEN.W))
  private def mstatusArchValue(value: UInt): UInt = {
    val withXlen =
      if (XLEN == 32) value
      else {
        val xlenFields =
          (if (hasS) BigInt(2) << 34 else BigInt(0)) |
            (if (hasU) BigInt(2) << 32 else BigInt(0))
        widen32(value) | xlenFields.U(XLEN.W)
      }
    withXlen | mstatusSdBit(value(14, 13))
  }
  private def csrOn(address: Int): Boolean = profile.csrSupported(address)
  private val pmuZihpm = PMUEnable && PMUZihpm
  private val pmuSscofpmf = pmuZihpm && PMUSscofpmf
  private val pmuCounterCount = ysyx_26030103_PMUEventID.CounterCount
  private val pmuMachineAddresses: Seq[Int] =
    if (pmuZihpm)
      (0 until pmuCounterCount).flatMap { index =>
        Seq(0xb03 + index, 0x323 + index) ++
          (if (XLEN == 32) Seq(0xb83 + index) else Seq.empty)
      } ++ (if (pmuSscofpmf) Seq(0xda0) else Seq.empty)
    else Seq.empty
  private val pmuUserAddresses: Seq[Int] =
    if (pmuZihpm)
      (0 until pmuCounterCount).flatMap { index =>
        Seq(0xc03 + index) ++
          (if (XLEN == 32) Seq(0xc83 + index) else Seq.empty)
      }
    else Seq.empty
  // HAS_U=n 时 mstatus 的 MPP/SPP 最低值被夹到 S；HAS_S=n 时固定为 M。
  private def legalizeMstatus(value: UInt): UInt =
    legalize(value, hasU = hasU, hasS = hasS, hasF = UseF)
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val Enable = Input(Bool())
    // 用于 minstret 自增的退休事件：过滤掉本指令自身写入
    // minstret/minstreth 的那一条退休；该指令仍然退休，PMU 的
    // Instructions 事件在顶层使用完整的 RetireCommit。
    val MinstretRetireCommit = Input(Bool())
    // EXU 把该提交拍到 WBU 的写 minstret 标记，退休时据此过滤自增。
    val MinstretWriteCommit = Output(Bool())
    val IsCSRrw = Input(Bool())
    val IsCSRrs = Input(Bool())
    val IsCSRrc = Input(Bool())
    val IsEcall = Input(Bool())
    val IsEbreak = Input(Bool())
    val IsMret = Input(Bool())
    val IsSret = Input(Bool())
    val IsSfenceVma = Input(Bool())
    val CSRAddress = Input(UInt(12.W))
    val rs1 = Input(UInt(5.W))
    val Rs1Data = Input(UInt(XLEN.W))
    val PC = Input(UInt(XLEN.W))
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
    val IRQCommit = Output(Bool())
    val IRQPending = Output(Bool())
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
    val PMPConfig = Output(Vec(16, UInt(8.W)))
    val PMPAddress = Output(Vec(16, UInt(XLEN.W)))
    val Mtime = Input(UInt(64.W))
    val McounterenOut = Output(UInt(32.W))
    val ScounterenOut = Output(UInt(32.W))
    // F/D：当前 frm 给 IDU 解析动态舍入；浮点运算在 EXU 提交时把
    // fflags 累积到 fcsr；WBU 退休 FP 指令时把 mstatus.FS 置 Dirty。
    val Frm = Output(UInt(3.W))
    // fcsr={frm,fflags} 的实时 8 位视图。DiffTest 在顶层退休沿锁存。
    val Fcsr = Output(UInt(8.W))
    val FPCommit = Input(Bool())
    val FPFlags = Input(UInt(fflagsWidth.W))
    val FPStateDirtyCommit = Input(Bool())
    // 顶层 PMU 事件总线；只有 PMUEnable 打开时才会被消费者使用。
    val PMUEvents = Input(UInt(ysyx_26030103_PMUEventID.BusWidth.W))
  })

  val CSRMstatus = 0x300.U(12.W)
  val CSRMtvec = 0x305.U(12.W)
  val CSRMepc = 0x341.U(12.W)
  val CSRMcause = 0x342.U(12.W)
  val CSRMscratch = 0x340.U(12.W)
  val CSRMedeleg = 0x302.U(12.W)
  val CSRMideleg = 0x303.U(12.W)
  val CSRMie = 0x304.U(12.W)
  val CSRMip = 0x344.U(12.W)
  val CSRSstatus = 0x100.U(12.W)
  val CSRSie = 0x104.U(12.W)
  val CSRStvec = 0x105.U(12.W)
  val CSRScounteren = 0x106.U(12.W)
  val CSRSscratch = 0x140.U(12.W)
  val CSRSepc = 0x141.U(12.W)
  val CSRScause = 0x142.U(12.W)
  val CSRStval = 0x143.U(12.W)
  val CSRSip = 0x144.U(12.W)
  val CSRSatp = 0x180.U(12.W)
  val CSRCycle = 0xc00.U(12.W)
  val CSRTime = 0xc01.U(12.W)
  val CSRInstret = 0xc02.U(12.W)
  val CSRCycleh = 0xc80.U(12.W)
  val CSRTimeh = 0xc81.U(12.W)
  val CSRInstreth = 0xc82.U(12.W)
  val CSRMcycle = 0xb00.U(12.W)
  val CSRMcycleh = 0xb80.U(12.W)
  val CSRMinstret = 0xb02.U(12.W)
  val CSRMinstreth = 0xb82.U(12.W)
  val CSRMisa = 0x301.U(12.W)
  val CSRMtval = 0x343.U(12.W)
  val CSRMcounteren = 0x306.U(12.W)
  val CSRMcountinhibit = 0x320.U(12.W)
  val CSRScountovf = 0xda0.U(12.W)
  val CSRMvendorid = 0xf11.U(12.W)
  val CSRMarchid = 0xf12.U(12.W)
  val CSRMimpid = 0xf13.U(12.W)
  val CSRMhartid = 0xf14.U(12.W)
  val CSRFflags = 0x001.U(12.W)
  val CSRFrm = 0x002.U(12.W)
  val CSRFcsr = 0x003.U(12.W)

  val mvendoridReadData = 0x79737978.U(32.W)
  val marchidReadData = "h018d3017".U(32.W)
  // 单 hart 实现：mhartid 固定为 0。mimpid 遵循与 NEMU REF 一致的
  // 约定，固定返回 0（表示不提供额外的实现版本号）。
  val mimpidReadData = 0.U(32.W)
  val mhartidReadData = 0.U(32.W)
  // SIE SPIE SPP SUM MXR；RISCV_F 时 sstatus 也包含 FS[14:13]。
  val sstatusMask = "h000c0122".U(32.W) |
    (if (UseF) "h00006000".U(32.W) else 0.U(32.W))
  // FS 是 mstatus/sstatus 的同一份架构状态；independent 视图只把 S 模式
  // 其余字段做成独立寄存器，FS/SD 仍必须跟随 mstatus 和 FP dirty。
  private val sstatusFsMaskValue = if (UseF) 0x6000L else 0L
  private val sstatusOwnMaskValue =
    (0x000c0122L | sstatusFsMaskValue) & ~sstatusFsMaskValue
  def isCSRAddress(address: UInt): Bool = io.CSRAddress === address
  private val FPCSRAddresses =
    if (UseF) Seq(0x001, 0x002, 0x003) else Seq.empty
  // 用户只读计数器始终随核实现：M 模式在任何配置下都可读；U 模式是否
  // 可读由 mcounteren/scounteren 在 IDU 检查。它们不能因为 CSR_ENABLED
  // 未列出而被裁剪，否则 rdcycle/rdtime/rdinstret 会读回 0。
  // RV32 才有高半字别名；RV64 的 mcycle/minstret 已是完整 64 位，
  // mcycleh/minstreth/cycleh/timeh/instreth 在 RV64 属于未实现 CSR。
  private val baseUserCounterAddresses =
    if (XLEN == 32) Seq(0xc00, 0xc01, 0xc02, 0xc80, 0xc81, 0xc82)
    else Seq(0xc00, 0xc01, 0xc02)
  private val userCounterAddresses =
    baseUserCounterAddresses ++ pmuUserAddresses
  private val EnabledCSRAddresses = (
    profile.CSREnabled.toSeq ++
      // identity CSR 始终存在，不依赖 CSR_ENABLED 是否列出。
      _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile
        .IdentityCSRAddresses.toSeq ++
      userCounterAddresses ++
      FPCSRAddresses ++
      pmuMachineAddresses ++
      (if (profile.PMPEnabled)
        (if (XLEN == 64) Seq(0x3a0, 0x3a2) else (0x3a0 to 0x3a3)) ++
          (0x3b0 to 0x3bf)
      else Seq.empty)
  ).filter(a => profile.csrSupported(a) || FPCSRAddresses.contains(a) ||
    userCounterAddresses.contains(a) || pmuMachineAddresses.contains(a))
    .distinct.sorted
  val CSRImplemented =
    EnabledCSRAddresses.map(a => io.CSRAddress === a.U(12.W))
      .reduceOption(_ || _).getOrElse(false.B)
  // mstatus.FS 只有在 RISCV_F 时存在；profile 默认读/写掩码没有包含
  // bit[14:13]，这里按 F 开关补上，避免软件写 mstatus 后 FS 被静默清零。
  private val mstatusFsMask = if (UseF) 0x6000L else 0L
  private val mstatusProfileReadMask =
    profile.CSRMstatusReadMask.toLong & 0xffffffffL
  private val mstatusProfileWriteMask =
    profile.CSRMstatusWriteMask.toLong & 0xffffffffL
  private val mstatusReadMaskValue = mstatusProfileReadMask | mstatusFsMask
  private val mstatusWriteMaskValue = mstatusProfileWriteMask | mstatusFsMask
  def CSRReadMask: UInt = {
    val full = fullMask.U(XLEN.W)
    // mstatus 的字段掩码已经在 MstatusReadData 里应用；这里不能再按
    // 32 位读写掩码截断，否则 SD(bit31/63) 和 RV64 的 UXL/SXL(35:32)
    // 会在 CSR 读口被清掉（NEMU REF 与架构读回都包含这些只读位）。
    val mstatusMask = full
    if (csrOn(0x180)) Mux(io.CSRAddress === CSRSatp,
      satpReadMaskValue.U(XLEN.W), mstatusMask) else mstatusMask
  }
  def CSRReadSet: UInt = {
    val mstatusSet =
      if (csrOn(0x300)) Mux(io.CSRAddress === CSRMstatus,
        (profile.CSRMstatusReadSet.toLong & 0xffffffffL).U(XLEN.W), 0.U(XLEN.W)) else 0.U(XLEN.W)
    if (csrOn(0x180)) Mux(io.CSRAddress === CSRSatp,
      satpReadSetValue.U(XLEN.W), mstatusSet) else mstatusSet
  }
  def CSRWriteMask: UInt = {
    val full = fullMask.U(XLEN.W)
    val mstatusMask =
      if (csrOn(0x300)) Mux(io.CSRAddress === CSRMstatus,
        mstatusWriteMaskValue.U(XLEN.W), full) else full
    if (csrOn(0x180)) Mux(io.CSRAddress === CSRSatp,
      satpWriteMaskValue.U(XLEN.W), mstatusMask) else mstatusMask
  }

  val csrrwWriteEnable = io.Enable && io.IsCSRrw
  val csrrsWriteEnable = io.Enable && io.IsCSRrs && io.rs1 =/= 0.U
  val csrrcWriteEnable = io.Enable && io.IsCSRrc && io.rs1 =/= 0.U
  def CSRWriteData(old: UInt): UInt = {
    val raw = Mux(io.IsCSRrw, io.Rs1Data,
      Mux(io.IsCSRrs, old | io.Rs1Data, old & ~io.Rs1Data))
    val mask = CSRWriteMask
    (old & ~mask) | (raw & mask)
  }

  val mretCommit = io.Enable && io.IsMret
  // HAS_S=n 时 SRET/SFENCE.VMA 在译码阶段已是非法指令；这里连 CSR 侧
  // 的提交/刷新路径也一并裁掉，不保留只可能为 0 的控制逻辑。
  val sretCommit = if (hasS) io.Enable && io.IsSret else false.B
  val sfenceCommit = if (hasS) io.Enable && io.IsSfenceVma else false.B

  val mstatus = Module(new ysyx_26030103_mstatus(hasS, hasU, UseF))
  val mtvec = Module(new ysyx_26030103_mtvec(XLEN))
  val mepc = Module(new ysyx_26030103_mepc(XLEN, UseC))
  val mcause = Module(new ysyx_26030103_mcause(XLEN))
  // S 模式入口寄存器只在 HAS_S=y 时例化。HAS_S=n 时顶层不生成 MMU，
  // 也不会有可返回的 S 状态，因此这些寄存器没有保留价值。
  val stvecOpt = if (hasS) Some(Module(new ysyx_26030103_mtvec(XLEN))) else None
  val sepcOpt = if (hasS) Some(Module(new ysyx_26030103_mepc(XLEN, UseC))) else None
  val scauseOpt = if (hasS) Some(Module(new ysyx_26030103_mcause(XLEN))) else None
  val mcycle = Module(new ysyx_26030103_mcycle(XLEN))
  val pmuOpt =
    if (pmuZihpm) Some(Module(new ysyx_26030103_PMU(XLEN, pmuSscofpmf)))
    else None

  mstatus.io.clock := io.clock; mstatus.io.reset := io.reset
  mtvec.io.clock := io.clock; mtvec.io.reset := io.reset
  mepc.io.clock := io.clock; mepc.io.reset := io.reset
  mcause.io.clock := io.clock; mcause.io.reset := io.reset
  stvecOpt.foreach { m => m.io.clock := io.clock; m.io.reset := io.reset }
  sepcOpt.foreach { m => m.io.clock := io.clock; m.io.reset := io.reset }
  scauseOpt.foreach { m => m.io.clock := io.clock; m.io.reset := io.reset }
  mcycle.io.clock := io.clock; mcycle.io.reset := io.reset

  val mscratch = RegInit(0.U(XLEN.W))
  val sscratchOpt = if (hasS) Some(RegInit(0.U(XLEN.W))) else None
  // independent 视图需要独立 sstatus；bound 视图直接使用 mstatus 的字段。
  val sstatusRegOpt =
    if (hasS && profile.CSRViewMode == "independent") Some(RegInit(0.U(32.W)))
    else None
  val medelegOpt = if (hasS) Some(RegInit(0.U(32.W))) else None
  val midelegOpt = if (hasS) Some(RegInit(0.U(32.W))) else None
  val mie = RegInit(0.U(32.W))
  val mip = RegInit(0.U(32.W))
  val sieOpt =
    if (hasS && profile.CSRViewMode == "independent") Some(RegInit(0.U(32.W)))
    else None
  val scounterenOpt = if (hasS) Some(RegInit(0.U(32.W))) else None
  val mcounteren = RegInit(0.U(32.W))
  val mcountinhibit = RegInit(0.U(32.W))
  mcycle.io.Enable := !mcountinhibit(0)
  val mtval = RegInit(0.U(XLEN.W))
  // fcsr = {frm[2:0], fflags[4:0]}；复位值 0（RISC-V 允许实现定义，
  // 0 使裸机/回放有确定初值）。
  val fflagsReg = RegInit(0.U(fflagsWidth.W))
  val frmReg = RegInit(0.U(3.W))
  val minstret = RegInit(0.U(64.W))
  // mcountinhibit.TM：TM=1 时 time 保持最后值，不再跟随 CLINT mtime。
  val timeCounter = RegInit(0.U(64.W))
  when(!mcountinhibit(1)) { timeCounter := io.Mtime }
  // RV64 的 pmpcfg0/pmpcfg2 是 64 位 CSR，分别容纳 8 个 8 位配置项；
  // RV32 的 pmpcfg0..3 才是 4x32 位。此前 RV64 也按 4x32 位实现，
  // 会把 OpenSBI 写入 pmpcfg0 的高 32 位（entry 4..7）丢掉，导致
  // 全内存 NAPOT entry 丢失、S 模式取指被 PMP 拒绝。
  private val PMPEntriesPerConfig = if (XLEN == 64) 8 else 4
  private val PMPConfigWords = 16 / PMPEntriesPerConfig
  private val PMPConfigWordBits = PMPEntriesPerConfig * 8
  private val PMPConfigByteMask = 0x9fL
  val pmpcfgReg = RegInit(
    VecInit(Seq.fill(PMPConfigWords)(0.U(PMPConfigWordBits.W))))
  val pmpaddrReg = RegInit(VecInit(Seq.fill(16)(0.U(XLEN.W))))
  val stvalOpt = if (hasS) Some(RegInit(0.U(XLEN.W))) else None
  val satpRegOpt = if (hasS) Some(RegInit(0.U(XLEN.W))) else None
  val satpReadData =
    if (hasS) {
      val satpReg = satpRegOpt.get
      (satpReg & satpReadMaskValue.U(XLEN.W)) | satpReadSetValue.U(XLEN.W)
    } else 0.U(XLEN.W)

  // 年长的 FP 指令可能在 WBU 退休的同一拍把 FS 置 Dirty；EXU 里的
  // 年轻 CSR 指令必须看到这个更新。用有效值参与所有 trap/CSR 运算，
  // 并在寄存器写回时持久化。仅产生非零 fflags、未写 f 寄存器
  // （例如 FCMP/FCLASS）时也属于修改浮点状态；这里必须用寄存器隔开
  // EXU 的 FPCommit（它依赖 IrqCommit），否则会形成组合环。
  val FPFlagsDirtyReg =
    RegNext(io.FPCommit && io.FPFlags =/= 0.U(fflagsWidth.W), false.B)
  private val fsDirtyValue = (mstatus.io.rdata & ~"h6000".U(32.W)) |
    (if (UseF) "h6000".U(32.W) else 0.U(32.W))
  val fsDirtyCommit = UseF.B && (io.FPStateDirtyCommit || FPFlagsDirtyReg)
  val mstatusRawReadData = Mux(fsDirtyCommit, fsDirtyValue, mstatus.io.rdata)
  // M-only 时特权级恒为 M，不生成寄存器；有 S 或 U 时需要保存可达的
  // 特权级。M+U 没有 S 级，但 MRET/异常仍会在 M 与 U 之间切换。
  val privilege: UInt =
    if (hasS || hasU) RegInit(3.U(2.W)) else 3.U(2.W)
  val mstatusReadData =
    (mstatusRawReadData & mstatusReadMaskValue.U(32.W)) |
      (profile.CSRMstatusReadSet.toLong & 0xffffffffL).U(32.W)
  // 32 位 CSR 在 RV64 下读回高 32 位为 0；mstatus 例外：已实现特权级
  // 对应的 SXL/UXL 读回 2，未实现特权级对应的字段读回 0。
  val mstatusReadDataWide = mstatusArchValue(mstatusReadData)
  val sstatusReadData =
    if (!hasS) 0.U(32.W)
    else if (profile.CSRViewMode == "independent") {
      val independentSstatus = sstatusRegOpt.get
      if (UseF) {
        // independent 只隔离 S 模式控制字段；FS 与 mstatus 共用同一状态。
        (independentSstatus & ~sstatusFsMaskValue.U(32.W)) |
          (mstatusReadData & sstatusFsMaskValue.U(32.W))
      } else independentSstatus
    } else mstatusReadData & sstatusMask
  // sstatus 是 mstatus 的受限视图，但 SD 仍是 FS==3 的只读汇总位
  // （RV32 bit31 / RV64 bit63）。RV64 的 sstatus 只包含 UXL[33:32]，
  // SXL 仅存在于 mstatus；未实现 U 级时 UXL 必须读回 0。
  val sstatusArchValue =
    widen32(sstatusReadData) |
      (if (XLEN == 64 && hasU) "h0000000200000000".U(XLEN.W)
       else 0.U(XLEN.W)) |
      mstatusSdBit(sstatusReadData(14, 13))
  val mtvecReadData = mtvec.io.rdata
  val mepcReadData = mepc.io.rdata
  val mcauseReadData = mcause.io.rdata
  val stvecReadData = stvecOpt.map(_.io.rdata).getOrElse(0.U(XLEN.W))
  val sepcReadData = sepcOpt.map(_.io.rdata).getOrElse(0.U(XLEN.W))
  val scauseReadData = scauseOpt.map(_.io.rdata).getOrElse(0.U(XLEN.W))
  // RV64 的 mcycle 直接返回完整 64 位；RV32 继续按 mcycle/mcycleh 选择。
  val mcycleReadData = if (XLEN == 64) mcycle.io.rdataFull else mcycle.io.rdata
  val mcyclehReadData = mcycle.io.rdataFull(63, 32)
  val minstretReadData =
    if (XLEN == 64) minstret else minstret(31, 0)
  val minstrethReadData = minstret(63, 32)
  // TIME 来自 CLINT 的 mtime；TM=1 时改用保持寄存器，使 time 停止递增。
  // RV64 直接返回完整 64 位；RV32 的 time 返回低 32 位，timeh 返回高 32 位。
  val timeValue = Mux(mcountinhibit(1), timeCounter, io.Mtime)
  val timeReadData = if (XLEN == 64) timeValue else timeValue(31, 0)
  val timehReadData = timeValue(63, 32)
  // misa.MXL: 1=RV32, 2=RV64；I 始终置位，A/C/F/D 随配置置位，
  // S/U 分别随 HAS_S/HAS_U 置位。位编号按 RISC-V 规范：
  // bit0=A，bit2=C，bit3=D，bit5=F，bit8=I，bit12=M，bit18=S，bit20=U。
  // misa 整体只读（IDU 拒绝任何写意图），因此这里只生成常量读口。
  val misaReadData = {
    val mxl = if (XLEN == 64) BigInt(2) << 62 else BigInt(1) << 30
    val aExt = if (UseA) BigInt(1) << 0 else BigInt(0)
    val cExt = if (UseC) BigInt(1) << 2 else BigInt(0)
    val dExt = if (UseD) BigInt(1) << 3 else BigInt(0)
    val fExt = if (UseF) BigInt(1) << 5 else BigInt(0)
    val mExt = if (UseM) BigInt(1) << 12 else BigInt(0)
    val sExt = if (hasS) BigInt(1) << 18 else BigInt(0)
    val uExt = if (hasU) BigInt(1) << 20 else BigInt(0)
    (mxl | BigInt(0x100) | aExt | cExt | dExt | fExt | mExt |
      sExt | uExt).U(XLEN.W)
  }
  val medelegReadData =
    medelegOpt.map(Reg => Reg & medelegMask).getOrElse(0.U(32.W))
  val midelegReadData = midelegOpt.getOrElse(0.U(32.W))
  val sscratchReadData = sscratchOpt.getOrElse(0.U(32.W))
  val stvalReadData = stvalOpt.getOrElse(0.U(32.W))
  val scounterenReadData = scounterenOpt.getOrElse(0.U(32.W))
  // 写 32 位 CSR 时只取 CSRWriteData 的低 32 位，高半部保持 0。
  private def write32(oldValue: UInt): UInt = CSRWriteData(widen32(oldValue))(31, 0)

  val lcofiView =
    if (pmuSscofpmf)
      Mux(pmuOpt.get.io.lcofiPending, "h00002000".U(32.W), 0.U(32.W))
    else 0.U(32.W)
  // 平台中断线可能只维持一拍；提交被更老指令阻塞时，必须像 mip 一样把
  // pending 锁存到真正接受中断的那一拍，否则脉冲会静默丢失。
  val externalIRQPending = RegInit(false.B)
  val timerIRQPending = RegInit(false.B)
  val IRQService = WireDefault(false.B)
  // SSIP/STIP/SEIP；Sscofpmf 打开时 LCOFI 也可以委托给 S。
  val sInterruptMask =
    (0x222 | (if (pmuSscofpmf) 0x2000 else 0)).U(32.W)
  // HAS_S=n 时不存在 S 级中断视图：timer/external 全部按 M 级中断处理。
  val timerView =
    if (!hasS) 0.U(32.W)
    else if (profile.timerPath == "direct_stip")
      (if (profile.HasSTimerIRQ)
        Mux(io.TimerInterrupt || timerIRQPending, "h00000020".U(32.W), 0.U(32.W))
      else 0.U(32.W))
    else
      Mux(io.TimerInterrupt || timerIRQPending, "h00000080".U(32.W), 0.U(32.W))
  val mTimerView =
    if (hasS) 0.U(32.W)
    else Mux(io.TimerInterrupt || timerIRQPending, "h00000080".U(32.W), 0.U(32.W))
  val externalSView = if (hasS && profile.HasSExternalIRQ)
    Mux(io.Interrupt || externalIRQPending, "h00000200".U(32.W), 0.U(32.W))
  else 0.U(32.W)
  val externalMView = if (!hasS || !profile.HasSExternalIRQ)
    Mux(io.Interrupt || externalIRQPending, "h00000800".U(32.W), 0.U(32.W))
  else 0.U(32.W)
  val mipState =
    mip | timerView | mTimerView | externalSView | externalMView | lcofiView
  val sieState =
    if (!hasS) 0.U(32.W)
    else if (profile.CSRViewMode == "independent") sieOpt.get
    else mie & sInterruptMask
  // sip 是 mip 中 S 级中断位视图。SSIP 与 mip.SSIP 必须是同一个 pending
  // 位：independent 视图不能再用独立 Sip 寄存器叠加，否则 sip 写 0 无法
  // 清除 mip.SSIP，sip 写 1 也不会出现在 mip 中。
  val sipState =
    if (!hasS) 0.U(32.W)
    else mipState & sInterruptMask
  val mIntPending =
    if (hasS) mipState & mie & ~midelegReadData
    else mipState & mie
  val sIntPending = if (hasS) sipState & sieState & midelegReadData else false.B
  val mIntTaken = mIntPending.orR &&
    (privilege =/= 3.U || mstatusReadData(3))
  val sStatusIE =
    if (!hasS) false.B
    else if (profile.CSRViewMode == "independent") sstatusRegOpt.get(1)
    else mstatusReadData(1)
  // sstatus.SIE 只屏蔽 S 模式自身的 S 级中断；U 模式下 S 级中断始终
  // 使能（此时 sstatus.SIE 只是保存的 S 模式使能位，不参与判断）。
  val sIntTaken =
    if (hasS) sIntPending.orR && privilege =/= 3.U &&
      (privilege === 0.U || sStatusIE)
    else false.B
  val hasInterrupt = mIntTaken || sIntTaken
  io.IRQPending := hasInterrupt
  IRQService := io.Enable && hasInterrupt && !io.MemTrap
  // CSR 写只在真正接受中断时被压掉；原始中断线为高但 MIE=0 时软件仍可写 CSR。
  val CSRWriteEnable = (csrrwWriteEnable || csrrsWriteEnable || csrrcWriteEnable) && !hasInterrupt && CSRImplemented
  // 软件写 mip 且目标 LCOFI=0 时清除 PMU 的 overflow 汇总位。
  val mipLcofiClear =
    if (pmuSscofpmf)
      CSRWriteEnable && isCSRAddress(CSRMip) &&
        !CSRWriteData(widen32(mipState))(13)
    else false.B
  pmuOpt.foreach { pmu =>
    pmu.io.events := io.PMUEvents
    pmu.io.privilege := privilege
    pmu.io.CSRAddress := io.CSRAddress
    pmu.io.CSRWriteEnable := CSRWriteEnable
    pmu.io.CSRWriteData := CSRWriteData(pmu.io.readData)
    pmu.io.mcountinhibit := mcountinhibit
    pmu.io.counteren := mcounteren
    pmu.io.lcofiClear := mipLcofiClear
  }
  val isException = io.IsEcall || io.IsEbreak || hasInterrupt || io.TrapValid

  def pendingCause(pending: UInt, sMode: Boolean): UInt = {
    val base = BigInt(0x80000000L)
    if (sMode) {
      // S 级中断优先级：SEI(9) > SSI(1) > STI(5)。旧实现先查 STIP，
      // 会把自己拉起的 SSIP/STIP 同时 pending 误报为 STI。
      Mux(pending(9), (base | 9).U(32.W),
        Mux(pending(1), (base | 1).U(32.W),
          Mux(pending(5), (base | 5).U(32.W), (base | 13).U(32.W))))
    } else {
      // M 入口也可能处理未委托的 S 级中断（mideleg 对应位为 0）；
      // cause 必须取实际 pending 位，例如未委托的 SEIP 是 cause 9，
      // 不能落进 MSI(3) 的默认分支。规范优先级：
      // MEI(11) > MSI(3) > MTI(7) > SEI(9) > SSI(1) > STI(5) > LCOFI(13)。
      Mux(pending(11), (base | 11).U(32.W),
        Mux(pending(3), (base | 3).U(32.W),
          Mux(pending(7), (base | 7).U(32.W),
            Mux(pending(9), (base | 9).U(32.W),
              Mux(pending(1), (base | 1).U(32.W),
                Mux(pending(5), (base | 5).U(32.W), (base | 13).U(32.W)))))))
    }
  }
  val MIRQCause = pendingCause(mIntPending, sMode = false)
  val SIRQCause = if (hasS) pendingCause(sIntPending, sMode = true) else 0.U(32.W)
  // M 级中断优先于已委托的 S 级中断：只要 MIntTaken 成立就走 M 入口，
  // 不能因为 SIntTaken 同时有效而改报 S cause。
  val IRQCause =
    if (hasS) Mux(mIntTaken, MIRQCause, SIRQCause) else MIRQCause
  // 只清除本次实际服务的中断源；服务拍电平仍高则重新锁存。
  val externalServiced =
    if (hasS && profile.HasSExternalIRQ) IRQCause === 9.U else IRQCause === 11.U
  val timerServiced =
    if (!hasS) IRQCause === 7.U
    else if (profile.timerPath == "direct_stip") IRQCause === 5.U
    else IRQCause === 7.U
  when(IRQService) {
    externalIRQPending := io.Interrupt || (externalIRQPending && !externalServiced)
    timerIRQPending := io.TimerInterrupt || (timerIRQPending && !timerServiced)
  }.otherwise {
    when(io.Interrupt) { externalIRQPending := true.B }
    when(io.TimerInterrupt) { timerIRQPending := true.B }
  }

  val exceptionCause = WireDefault(0.U(32.W))
  when(io.MemTrap) {
    exceptionCause := io.TrapCause
  }.elsewhen(hasInterrupt) {
    exceptionCause := IRQCause
  }.elsewhen(io.TrapValid) {
    exceptionCause := io.TrapCause
  }.elsewhen(io.IsEbreak) {
    exceptionCause := 3.U(32.W)
  }.otherwise {
    // ecall 的 cause 由当前特权级决定：U=8，S=9，M=11。
    // HAS_U=n 时 U 级不可达，直接裁掉 cause=8 的选择路径；HAS_S=n
    // 但 HAS_U=y（M+U）时 U 的 ecall 仍是 8，S 级路径不存在。
    exceptionCause := (if (!hasS) {
      if (hasU) {
        Mux(privilege === 0.U, 8.U(32.W), 11.U(32.W))
      } else {
        11.U(32.W)
      }
    } else if (hasU) {
      Mux(privilege === 0.U, 8.U(32.W),
        Mux(privilege === 1.U, 9.U(32.W), 11.U(32.W)))
    } else {
      Mux(privilege === 1.U, 9.U(32.W), 11.U(32.W))
    })
  }

  val HasIRQCommit = IRQService
  io.IRQCommit := HasIRQCommit
  val exceptionCommit = io.MemTrap || (io.Enable && isException)
  io.TrapCommit := exceptionCommit
  io.CommittedCause := exceptionCause

  // 异常委托：只有低于 M 的异常/中断才能被委托给 S。HAS_S=n 时委托
  // 寄存器和选择逻辑都不生成，TrapToS 恒为 0。
  val trapToS = if (hasS) {
    val causeIndex = exceptionCause(4, 0)
    val delegation = Mux(exceptionCause(31), midelegReadData, medelegReadData)
    val delegated = delegation(causeIndex)
    privilege =/= 3.U && delegated
  } else false.B

  val mstatusExceptionData = {
    val mTrap = legalizeMstatus(
      (mstatusReadData & ~"h00001888".U(32.W)) |
        (privilege << 11) | (mstatusReadData(3) << 7)
    )
    if (hasS) {
      val sTrap = legalizeMstatus(
        (mstatusReadData & ~"h00000122".U(32.W)) |
          (privilege(0) << 8) | (mstatusReadData(1) << 5)
      )
      Mux(trapToS, sTrap, mTrap)
    } else mTrap
  }
  // MPP 恢复；MIE <- MPIE；MPIE <- 1；MPP 保持最低支持特权级。
  // HAS_U=n 时 legalizeMstatus 已把 MPP 的最低值夹到 S。
  // 返回低于 M 的目标特权级时必须清 MPRV(bit17)；返回 M 时保留。
  val mstatusMretBase = legalizeMstatus(
    (mstatusReadData & ~"h00001888".U(32.W)) |
      (mstatusReadData(7) << 3) | (1.U << 7)
  )
  val mstatusMretData = Mux(mstatusReadData(12, 11) === 3.U,
    mstatusMretBase,
    mstatusMretBase & ~"h00020000".U(32.W))
  // SRET：SPP 决定返回 S/U；SIE <- SPIE；SPIE <- 1；SPP 回到最低支持级。
  // HAS_U=n 时 SPP 固定为 1，SRET 只能返回 S。SRET 的目标总是 S/U，
  // 因此必须无条件清 MPRV(bit17)。
  val mstatusSretDataOpt = if (hasS) Some(legalizeMstatus(
    ((mstatusReadData & ~"h00000122".U(32.W)) |
      (mstatusReadData(5) << 1) | (1.U << 5)) & ~"h00020000".U(32.W)
  )) else None
  // independent 视图下 SRET 只清 mstatus.MPRV，不写 mstatus 的 S 字段；
  // 退休快照也必须反映这一实际写入。
  val mstatusSretStateData =
    if (!hasS) 0.U(32.W)
    else if (profile.CSRViewMode == "independent")
      mstatusReadData & ~"h00020000".U(32.W)
    else mstatusSretDataOpt.get

  val mstatusWriteEnable =
    if (hasS && profile.CSRViewMode == "bound" && csrOn(0x100))
      CSRWriteEnable && (isCSRAddress(CSRMstatus) || isCSRAddress(CSRSstatus))
    else CSRWriteEnable && isCSRAddress(CSRMstatus)
  val sstatusWriteEnable =
    if (hasS && profile.CSRViewMode == "independent" && csrOn(0x100))
      CSRWriteEnable && isCSRAddress(CSRSstatus)
    else false.B
  // independent 视图只隔离 S 模式控制字段；sstatus.FS 与 mstatus.FS 是同一
  // 份状态，sstatus 写入 FS 时必须同步写入 mstatus，否则 FP dirty/读回分叉。
  val sstatusFsWriteEnable =
    if (hasS && profile.CSRViewMode == "independent" && UseF) sstatusWriteEnable
    else false.B
  val sstatusFsWriteData =
    if (hasS && profile.CSRViewMode == "independent" && UseF) {
      val sstatusReg = sstatusRegOpt.get
      val oldFsView = (sstatusReg & ~sstatusFsMaskValue.U(32.W)) |
        (mstatusReadData & sstatusFsMaskValue.U(32.W))
      val written = write32(oldFsView)
      (mstatusReadData & ~sstatusFsMaskValue.U(32.W)) |
        (written & sstatusFsMaskValue.U(32.W))
    } else 0.U(32.W)
  val mstatusWriteMask = mstatusWriteMaskValue.U(32.W)
  val MstatusCSRData = {
    val rawWrite = CSRWriteData(mstatusReadDataWide)(31, 0)
    val masked = (mstatusReadData & ~mstatusWriteMask) |
      (rawWrite & mstatusWriteMask)
    if (hasS && csrOn(0x100)) {
      Mux(
        isCSRAddress(CSRSstatus),
        legalizeMstatus((mstatusReadData & ~sstatusMask) |
          ((masked | (mstatusReadData & ~mstatusWriteMask)) & sstatusMask)),
        legalizeMstatus(masked)
      )
    } else legalizeMstatus(masked)
  }
  // 写 fflags/frm/fcsr 也属于修改浮点状态：mstatus.FS 应变 Dirty。
  val FPCSRWriteIntent =
    CSRWriteEnable && (isCSRAddress(CSRFflags) || isCSRAddress(CSRFrm) ||
      isCSRAddress(CSRFcsr))
  val fsDirtyData = fsDirtyValue
  val mstatusWriteDataSelect = Seq(
    exceptionCommit -> mstatusExceptionData,
    mretCommit -> mstatusMretData
  ) ++ (if (hasS) Seq(sretCommit -> mstatusSretStateData) else Seq.empty) ++ Seq(
    mstatusWriteEnable -> MstatusCSRData,
    sstatusFsWriteEnable -> sstatusFsWriteData,
    FPCSRWriteIntent -> fsDirtyData,
    fsDirtyCommit -> fsDirtyData
  )
  mstatus.io.wen := mstatusWriteEnable || exceptionCommit || mretCommit ||
    (if (hasS && profile.CSRViewMode == "bound") sretCommit else false.B) ||
    sstatusFsWriteEnable || FPCSRWriteIntent || fsDirtyCommit
  mstatus.io.wdata := MuxCase(MstatusCSRData, mstatusWriteDataSelect)
  if (hasS && profile.CSRViewMode == "independent") {
    // 独立 sstatus 视图下 SRET 不写 mstatus 的 S 字段，但 MPRV 属于
    // mstatus，返回 S/U 时仍必须清零；这里只覆盖 MPRV 位。SRET 被中断
    // 抢占时 ExceptionCommit 必须优先，trap-entry 的 mstatus 状态不能被
    // 这条仅清 MPRV 的路径覆盖。
    when(sretCommit && !exceptionCommit) {
      mstatus.io.wen := true.B
      mstatus.io.wdata := mstatusReadData & ~"h00020000".U(32.W)
    }
  }
  if (hasS && profile.CSRViewMode == "independent") {
    val sstatusReg = sstatusRegOpt.get
    // 读 CSRRS/CSRRC 的 old value 时，FS 必须取共享的 mstatus.FS。
    val sReadOld =
      if (UseF) (sstatusReg & ~sstatusFsMaskValue.U(32.W)) |
        (mstatusReadData & sstatusFsMaskValue.U(32.W))
      else sstatusReg
    val sWrite = legalizeMstatus(
      (sstatusReg & ~sstatusOwnMaskValue.U(32.W)) |
        (write32(sReadOld) & sstatusOwnMaskValue.U(32.W)))
    val sTrap = legalizeMstatus((sstatusReg & ~"h00000122".U(32.W)) |
      (sstatusReg(1) << 5) | (privilege(0) << 8))
    val sRet = legalizeMstatus((sstatusReg & ~"h00000122".U(32.W)) |
      (sstatusReg(5) << 1) | (1.U << 5))
    when(sstatusWriteEnable) { sstatusReg := sWrite }
    // trap entry 必须优先于同拍的 SRET（SRET 已被中断抢占）。
    when(sretCommit && !exceptionCommit) { sstatusReg := sRet }
    when(exceptionCommit && trapToS) { sstatusReg := sTrap }
  }

  mtvec.io.wen := CSRWriteEnable && isCSRAddress(CSRMtvec)
  mtvec.io.wdata := CSRWriteData(mtvecReadData)
  stvecOpt.foreach { stvec =>
    stvec.io.wen := CSRWriteEnable && isCSRAddress(CSRStvec)
    stvec.io.wdata := CSRWriteData(stvecReadData)
  }

  // 只有 M 入口写 mepc/mcause；委托到 S 时写 sepc/scause/stval。
  val mepcWriteEnable = CSRWriteEnable && isCSRAddress(CSRMepc) && !trapToS
  mepc.io.wen := mepcWriteEnable || (exceptionCommit && !trapToS)
  mepc.io.ExceptionWE := exceptionCommit && !trapToS
  mepc.io.ExceptionData := io.PC
  mepc.io.wdata := Mux(exceptionCommit && !trapToS, io.PC, CSRWriteData(mepcReadData))
  val mcauseWriteEnable = CSRWriteEnable && isCSRAddress(CSRMcause) && !trapToS
  mcause.io.wen := mcauseWriteEnable || (exceptionCommit && !trapToS)
  mcause.io.wdata := Mux(exceptionCommit && !trapToS, widenCause32(exceptionCause),
    CSRWriteData(mcauseReadData))

  sepcOpt.foreach { sepc =>
    val sepcWriteEnable = CSRWriteEnable && isCSRAddress(CSRSepc)
    sepc.io.wen := sepcWriteEnable || (exceptionCommit && trapToS)
    sepc.io.ExceptionWE := exceptionCommit && trapToS
    sepc.io.ExceptionData := io.PC
    sepc.io.wdata := Mux(exceptionCommit && trapToS, io.PC, CSRWriteData(sepcReadData))
  }
  scauseOpt.foreach { scause =>
    val scauseWriteEnable = CSRWriteEnable && isCSRAddress(CSRScause)
    scause.io.wen := scauseWriteEnable || (exceptionCommit && trapToS)
    scause.io.wdata := Mux(exceptionCommit && trapToS, widenCause32(exceptionCause),
      CSRWriteData(scauseReadData))
  }

  // trap 写 tval 的统一取值：有明确 tval 的异常（访存地址、压缩非法
  // 指令编码等）直接使用输入；没有专门 tval 的异常回退到故障 PC。
  // 不能用“tval != 0”判断，因为全零的非法压缩编码是合法的 tval=0。
  // ecall 的 mtval 按规范固定为 0；只有当前提交的 ecall 生效，
  // MemTrap/其他异常优先级更高，仍走原来的 tval 路径。
  // 中断没有 tval，mtval 同样固定为 0。
  // breakpoint(cause=3) 没有专门 tval，继续回退到故障 PC。
  val ecallTvalCommit = io.Enable && io.IsEcall && !io.MemTrap &&
    !hasInterrupt && !io.TrapValid && !io.IsEbreak
  val interruptTvalCommit = io.Enable && hasInterrupt && !io.MemTrap
  val trapTvalValue =
    Mux(interruptTvalCommit || ecallTvalCommit, 0.U(XLEN.W),
      Mux(io.TrapTvalValid, io.TrapTval, io.PC))
  // IALIGN=16 时 mepc/sepc 只清 bit0；IALIGN=32 清低两位。
  private def alignEPC(value: UInt): UInt =
    if (UseC) Cat(value(XLEN - 1, 1), 0.U(1.W))
    else Cat(value(XLEN - 1, 2), 0.U(2.W))
  if (hasS) {
    val stval = stvalOpt.get
    when(exceptionCommit && trapToS) {
      stval := trapTvalValue
    }.elsewhen(exceptionCommit) {
      mtval := trapTvalValue
    }.elsewhen(CSRWriteEnable && isCSRAddress(CSRStval)) {
      stval := CSRWriteData(stval)
    }
  } else {
    when(exceptionCommit) {
      mtval := trapTvalValue
    }
  }
  when(CSRWriteEnable && isCSRAddress(CSRMtval)) { mtval := CSRWriteData(mtval) }
  when(CSRWriteEnable && isCSRAddress(CSRMcounteren)) { mcounteren := write32(mcounteren) }
  when(CSRWriteEnable && isCSRAddress(CSRMcountinhibit)) { mcountinhibit := write32(mcountinhibit) }
  // 浮点运算在 EXU 提交时累积 fflags；CSR 写覆盖顺序放在后面，保证
  // 同一拍即使出现（架构上单发射不会同时发生）也以软件写为准。
  when(io.FPCommit) { fflagsReg := fflagsReg | io.FPFlags }
  when(CSRWriteEnable && isCSRAddress(CSRFflags)) {
    fflagsReg := CSRWriteData(widen32(fflagsReg))(
      ysyx_26030103_FPUFlags.NV, ysyx_26030103_FPUFlags.NX)
  }
  when(CSRWriteEnable && isCSRAddress(CSRFrm)) {
    frmReg := CSRWriteData(widen32(frmReg))(2, 0)
  }
  when(CSRWriteEnable && isCSRAddress(CSRFcsr)) {
    val fcsrWrite = CSRWriteData(widen32(Cat(frmReg, fflagsReg)))
    frmReg := fcsrWrite(fflagsWidth + frmWidth - 1, fflagsWidth)
    fflagsReg := fcsrWrite(
      ysyx_26030103_FPUFlags.NV, ysyx_26030103_FPUFlags.NX)
  }
  val frmNext = Mux(CSRWriteEnable && isCSRAddress(CSRFcsr),
    CSRWriteData(widen32(Cat(frmReg, fflagsReg)))(
      fflagsWidth + frmWidth - 1, fflagsWidth),
    Mux(CSRWriteEnable && isCSRAddress(CSRFrm),
      CSRWriteData(widen32(frmReg))(2, 0), frmReg))
  io.Frm := frmNext
  io.Fcsr := Cat(frmReg, fflagsReg)
  for (word <- 0 until PMPConfigWords) {
    // RV64：pmpcfg0=0x3a0（entry 0..7），pmpcfg2=0x3a2（entry 8..15）。
    // RV32：pmpcfg0..3=0x3a0..0x3a3（每个 4 项）。
    val configAddress =
      if (XLEN == 64) 0x3a0 + word * 2 else 0x3a0 + word
    // 每个 8 位表项保留 L/A[1:0]/X/W/R，清掉保留位 [6:5]；已经被 L
    // 锁定的表项忽略本次写入（包括试图把 L 清 0 的写）。
    val writableBytes = VecInit(
      (0 until PMPEntriesPerConfig).map { entry =>
        Mux(pmpcfgReg(word)(8 * entry + 7),
          0.U(8.W), PMPConfigByteMask.U(8.W))
      }
    ).asUInt
    when(CSRWriteEnable && io.CSRAddress === configAddress.U(12.W)) {
      val writeData = CSRWriteData(pmpcfgReg(word))
      pmpcfgReg(word) :=
        (pmpcfgReg(word) & ~writableBytes) | (writeData & writableBytes)
    }
  }
  for (entry <- 0 until 16) {
    // 锁定表项的 pmpaddr 同样不可写；另外，若下一项是 locked TOR，
    // 它的下界 pmpaddr[entry] 也必须保持不可写。
    val entryLocked = pmpcfgReg(entry / PMPEntriesPerConfig)(
      8 * (entry % PMPEntriesPerConfig) + 7)
    val nextTorLocked =
      if (entry < 15) {
        val next = entry + 1
        val nextConfig = pmpcfgReg(next / PMPEntriesPerConfig)
        val base = 8 * (next % PMPEntriesPerConfig)
        // PMP A 编码：1=TOR，3=NAPOT；locked TOR 的邻居下界受保护。
        nextConfig(base + 7) && nextConfig(base + 4, base + 3) === 1.U
      } else false.B
    when(CSRWriteEnable && io.CSRAddress === (0x3b0 + entry).U(12.W) &&
      !entryLocked && !nextTorLocked) {
      // RV32 PMP 地址为 30 位；RV64 pmpaddr 为标准 54 位。
      if (XLEN == 64) {
        pmpaddrReg(entry) := CSRWriteData(pmpaddrReg(entry)) &
          "h003fffffffffffff".U(64.W)
      } else {
        pmpaddrReg(entry) := write32(pmpaddrReg(entry)) & "h3fffffff".U(32.W)
      }
    }
  }
  // 标准 mcountinhibit：bit0=CY，bit2=IR。
  // minstret 只统计 WBU 真正退休的指令：EXU 的提交拍不能作为计数依据，
  // 普通 load/store 不在 EXU 访问 CSR，MDU 后续项也可能在队列中等待。
  // 同步异常/中断和被 MEM 故障取消的指令 Retire=0，因此不会计数。
  // minstret/minstreth 是可写计数器；写口优先于本拍退休自增。
  // MinstretWriteCommit 随指令带到 WBU：该指令自身退休时不得再自增。
  val minstretWriteEnable = CSRWriteEnable &&
    (isCSRAddress(CSRMinstret) || isCSRAddress(CSRMinstreth))
  io.MinstretWriteCommit := minstretWriteEnable
  when(minstretWriteEnable) {
    if (XLEN == 64) {
      minstret := CSRWriteData(minstret)
    } else {
      // RV32 的 minstreth 是 64 位计数器的独立高半字；CSRRS/CSRRC 必须
      // 以高半字作为 old value 计算，不能复用低半字。
      val writtenLow = CSRWriteData(minstret(31, 0))
      val writtenHigh = CSRWriteData(minstret(63, 32))
      minstret := Cat(
        Mux(isCSRAddress(CSRMinstreth), writtenHigh(31, 0), minstret(63, 32)),
        Mux(isCSRAddress(CSRMinstret), writtenLow(31, 0), minstret(31, 0))
      )
    }
  }.elsewhen(io.MinstretRetireCommit && !mcountinhibit(2)) {
    minstret := minstret + 1.U
  }
  sscratchOpt.foreach { sscratch =>
    when(CSRWriteEnable && isCSRAddress(CSRSscratch)) { sscratch := CSRWriteData(sscratch) }
  }
  when(CSRWriteEnable && isCSRAddress(CSRMscratch)) { mscratch := CSRWriteData(mscratch) }
  medelegOpt.foreach { medeleg =>
    when(CSRWriteEnable && isCSRAddress(CSRMedeleg)) {
      medeleg := write32(medeleg) & medelegMask
    }
  }
  midelegOpt.foreach { mideleg =>
    // M 级中断（MSIP/MTIP/MEIP，bit3/7/11）不能委托给 S，mideleg 中
    // 对应位是只读零。若把软件误写值原样保存，MIntPending 里的
    // `~Mideleg` 会把 M 中断从 M 入口排除，而 SipState 又只看 S 位，
    // 最终把中断整个吞掉。
    when(CSRWriteEnable && isCSRAddress(CSRMideleg)) {
      mideleg := write32(mideleg) & sInterruptMask
    }
  }
  // mip 的软件可写位：SSIP(bit1)、STIP(bit5)、SEIP(bit9)。
  // 本核没有 stimecmp，STIP 必须允许 M 模式软件写入；SEIP 是软件位与
  // PLIC 外部信号的逻辑或。MSIP/MTIP/MEIP 只读；LCOFI 不存进 Mip 寄存器，
  // 写 0 时通过 PMU 清 OF 位（事件状态本身才是 pending 源）。
  val mipWriteMask =
    "h00000222".U(32.W)
  // sip 中只有 SSIP(bit1) 可由软件写；STIP/SEIP 是硬件置位的只读位。
  // independent 视图下 sip.SSIP 直接读写 mip 的同一个 SSIP 位。
  val sipWriteMask =
    sInterruptMask & "h00000002".U(32.W)
  // mie/sie 只保留已实现的中断位，未实现位读回 0。
  val mieWriteMask =
    ("h00000888".U(32.W) |
      (if (hasS) "h00000222".U(32.W) else 0.U(32.W)) |
      (if (pmuSscofpmf) "h00002000".U(32.W) else 0.U(32.W)))
  val sieWriteMask =
    sInterruptMask
  when(CSRWriteEnable && isCSRAddress(CSRMie)) {
    mie := (mie & ~mieWriteMask) | (write32(mie) & mieWriteMask)
  }
  when(CSRWriteEnable && isCSRAddress(CSRMip)) {
    mip := (mip & ~mipWriteMask) | (write32(mip) & mipWriteMask)
  }
  if (hasS && profile.CSRViewMode == "independent") {
    val sie = sieOpt.get
    when(CSRWriteEnable && isCSRAddress(CSRSie)) {
      sie := (sie & ~sieWriteMask) | (write32(sie) & sieWriteMask)
    }
    // SSIP 是 mip/sip 共享的 pending 位；写 sip 必须更新 Mip 寄存器。
    when(CSRWriteEnable && isCSRAddress(CSRSip)) {
      mip := (mip & ~sipWriteMask) | (write32(mip) & sipWriteMask)
    }
  } else if (hasS) {
    when(CSRWriteEnable && isCSRAddress(CSRSie)) {
      mie := (mie & ~sInterruptMask) | (write32(mie) & sInterruptMask)
    }
    when(CSRWriteEnable && isCSRAddress(CSRSip)) {
      mip := (mip & ~sipWriteMask) | (write32(mip) & sipWriteMask)
    }
  }
  scounterenOpt.foreach { scounteren =>
    when(CSRWriteEnable && isCSRAddress(CSRScounteren)) {
      scounteren := write32(scounteren)
    }
  }
  val satpWriteEnable = if (hasS) CSRWriteEnable && isCSRAddress(CSRSatp) else false.B
  val satpWriteRaw =
    satpRegOpt.map(r => CSRWriteData(r)).getOrElse(0.U(XLEN.W))
  // 规范：写入不支持的 satp.MODE 时整笔写入无效，不能只把 MODE 收敛到
  // Bare 却仍提交 ASID/PPN。RV32 的两种 MODE 都支持。
  val satpWriteLegal =
    if (!hasS) false.B
    else if (XLEN == 32) true.B
    else {
      val requested = satpWriteRaw(63, 60)
      requested === 0.U || requested === profile.satpMode.U
    }
  satpRegOpt.foreach { satpReg =>
    when(satpWriteEnable && satpWriteLegal) {
      val raw = CSRWriteData(satpReg)
      val mask = satpWriteMaskValue.U(XLEN.W)
      val data = (satpReg & ~mask) | (raw & mask)
      if (XLEN == 32) {
        // Sv32 satp.MODE 只支持 0/1。
        satpReg := Cat(Mux(data(31), 1.U(1.W), 0.U(1.W)), data(30, 0))
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
        satpReg := Cat(legalMode, data(59, 0))
      }
    }
  }
  io.Satp := satpRegOpt.getOrElse(0.U(XLEN.W))
  if (hasS || hasU) {
    val mretPrivilege: UInt =
      if (hasU) mstatusReadData(12, 11)
      else if (hasS) Mux(mstatusReadData(12), 3.U(2.W), 1.U(2.W))
      else 3.U(2.W)
    if (hasS) {
      val sretPrivilege =
        if (hasU) Mux(mstatusReadData(8), 1.U(2.W), 0.U(2.W))
        else 1.U(2.W)
      when(exceptionCommit) {
        privilege := Mux(trapToS, 1.U, 3.U)
      }.elsewhen(mretCommit) {
        privilege := mretPrivilege
      }.elsewhen(sretCommit) {
        privilege := sretPrivilege
      }
    } else {
      // M+U：没有 S 入口，所有异常/中断都进入 M；MRET 按 MPP 返回 M/U。
      when(exceptionCommit) {
        privilege := 3.U
      }.elsewhen(mretCommit) {
        privilege := mretPrivilege
      }
    }
  }
  io.Privilege := privilege
  // CSR 写提交与紧随其后的指令译码发生在同一拍：IDU 在译码级用
  // mstatus/frm 判断 F 指令、DYN 舍入以及 TVM/TSR/TW 陷门。
  // CSR 寄存器在时钟沿才更新，若这里不旁路本拍的写数据，紧邻 CSR 写的
  // 第一条指令会看到旧值（例如清 FS 后的 fadd.s 不陷入、写 frm 后的
  // DYN 指令仍用旧舍入模式）。异常/MRET/SRET 会冲刷年轻指令，只有软件
  // CSR 写需要旁路。
  io.CurrentMstatus := Mux(sstatusFsWriteEnable,
    mstatusArchValue(sstatusFsWriteData),
    Mux(mstatusWriteEnable,
      mstatusArchValue(MstatusCSRData), mstatusArchValue(mstatusReadData)))
  io.FlushTLB :=
    (if (hasS) sfenceCommit || (satpWriteEnable && satpWriteLegal) else false.B)

  val mcycleAccess = isCSRAddress(CSRMcycle) || isCSRAddress(CSRMcycleh)
  mcycle.io.wen := CSRWriteEnable && mcycleAccess
  mcycle.io.SelectHigh := isCSRAddress(CSRMcycleh)
  mcycle.io.wdata := CSRWriteData(mcycleReadData)

  // CSR 读多路选择只包含当前配置真正实现的地址。HAS_S=n/HAS_U=n 时
  // 对应的 S/U CSR 不会留下读 mux 和只读常量。
  val CSRMap = scala.collection.mutable.ArrayBuffer[(UInt, UInt)]()
  def addCSR(address: UInt, data: => UInt, enabled: Boolean): Unit = {
    if (enabled) { CSRMap += (address -> data) }
  }
  addCSR(CSRMstatus, mstatusReadDataWide, csrOn(0x300))
  addCSR(CSRSstatus, sstatusArchValue, csrOn(0x100))
  addCSR(CSRFflags, widen32(fflagsReg), UseF)
  addCSR(CSRFrm, widen32(frmReg), UseF)
  addCSR(CSRFcsr, widen32(Cat(frmReg, fflagsReg)), UseF)
  addCSR(CSRMtvec, mtvecReadData, csrOn(0x305))
  addCSR(CSRStvec, stvecReadData, csrOn(0x105))
  addCSR(CSRMepc, mepcReadData, csrOn(0x341))
  addCSR(CSRSepc, sepcReadData, csrOn(0x141))
  addCSR(CSRMcause, mcauseReadData, csrOn(0x342))
  addCSR(CSRScause, scauseReadData, csrOn(0x142))
  addCSR(CSRMscratch, mscratch, csrOn(0x340))
  addCSR(CSRSscratch, sscratchReadData, csrOn(0x140))
  addCSR(CSRMedeleg, widen32(medelegReadData), csrOn(0x302))
  addCSR(CSRMideleg, widen32(midelegReadData), csrOn(0x303))
  addCSR(CSRMie, widen32(mie), csrOn(0x304))
  // mip 读口必须包含 CLINT/PLIC 实时拉起的 pending 位，不能只返回
  // 软件可写寄存器 Mip。否则 M 模式读取 mip 时看不到 MTIP，S 模式
  // 通过 mip 观察 STIP 的路径也会与 sip 不一致。
  addCSR(CSRMip, widen32(mipState), csrOn(0x344))
  addCSR(CSRSie, widen32(sieState), csrOn(0x104))
  addCSR(CSRSip, widen32(sipState), csrOn(0x144))
  addCSR(CSRScounteren, widen32(scounterenReadData), csrOn(0x106))
  addCSR(CSRStval, stvalReadData, csrOn(0x143))
  addCSR(CSRSatp, satpReadData, csrOn(0x180))
  addCSR(CSRMcycle, mcycleReadData, csrOn(0xb00))
  addCSR(CSRMcycleh, widen32(mcyclehReadData), csrOn(0xb80))
  addCSR(CSRMinstret, minstretReadData, csrOn(0xb02))
  addCSR(CSRMinstreth, widen32(minstrethReadData), csrOn(0xb82))
  // 用户计数器别名：rdcycle/rdtime/rdinstret 及其 RV32 高半字。
  // CYCLE/CYCLEH 跟随 mcycle，INSTRET/INSTRETH 跟随 minstret；
  // TIME/TIMEH 直接来自 CLINT mtime，不需要对应的机器计数器。
  addCSR(CSRCycle, mcycleReadData, enabled = true)
  addCSR(CSRCycleh, widen32(mcyclehReadData), enabled = XLEN == 32)
  addCSR(CSRTime, timeReadData, enabled = true)
  addCSR(CSRTimeh, widen32(timehReadData), enabled = XLEN == 32)
  addCSR(CSRInstret, minstretReadData, enabled = true)
  addCSR(CSRInstreth, widen32(minstrethReadData), enabled = XLEN == 32)
  addCSR(CSRMisa, misaReadData, csrOn(0x301))
  addCSR(CSRMtval, mtval, csrOn(0x343))
  addCSR(CSRMcounteren, widen32(mcounteren), csrOn(0x306))
  addCSR(CSRMcountinhibit, widen32(mcountinhibit), csrOn(0x320))
  pmuOpt.foreach { pmu =>
    for (index <- 0 until pmuCounterCount) {
      addCSR((0xb03 + index).U(12.W), pmu.io.readData, enabled = true)
      addCSR((0x323 + index).U(12.W), pmu.io.readData, enabled = true)
      addCSR((0xc03 + index).U(12.W), pmu.io.readData, enabled = true)
      if (XLEN == 32) {
        addCSR((0xb83 + index).U(12.W), pmu.io.readData, enabled = true)
        addCSR((0xc83 + index).U(12.W), pmu.io.readData, enabled = true)
      }
    }
    if (pmuSscofpmf) {
      addCSR(CSRScountovf, widen32(pmu.io.scountovf), enabled = true)
    }
  }
  addCSR(CSRMvendorid, widen32(mvendoridReadData), csrOn(0xf11))
  addCSR(CSRMarchid, widen32(marchidReadData), csrOn(0xf12))
  addCSR(CSRMimpid, widen32(mimpidReadData), csrOn(0xf13))
  addCSR(CSRMhartid, widen32(mhartidReadData), csrOn(0xf14))
  for (index <- 0 until 4) {
    val configAddress = if (XLEN == 64) 0x3a0 + index * 2 else 0x3a0 + index
    addCSR(
      configAddress.U(12.W),
      pmpcfgReg(index),
      csrOn(configAddress)
    )
  }
  for (index <- 0 until 16) {
    addCSR((0x3b0 + index).U(12.W), pmpaddrReg(index), csrOn(0x3b0 + index))
  }
  val CSRRawReadData =
    if (CSRMap.isEmpty) 0.U(XLEN.W)
    else MuxLookup(io.CSRAddress, 0.U(XLEN.W))(CSRMap.toSeq)
  io.CSR_rdata := Mux(CSRImplemented,
    (CSRRawReadData & CSRReadMask) | CSRReadSet, 0.U(XLEN.W))

  val mtvecBase = Cat(mtvecReadData(XLEN - 1, 2), 0.U(2.W))
  val causeOffsetWide = exceptionCause(29, 0) << 2
  val causeOffset = widen32(causeOffsetWide(31, 0))
  val mtvecTrapTarget = Mux(
    mtvecReadData(1, 0) === 1.U && exceptionCause(31),
    mtvecBase + causeOffset, mtvecBase)
  val stvecTrapTarget =
    if (hasS) {
      val stvecBase = Cat(stvecReadData(XLEN - 1, 2), 0.U(2.W))
      Mux(stvecReadData(1, 0) === 1.U && exceptionCause(31),
        stvecBase + causeOffset, stvecBase)
    } else 0.U(XLEN.W)

  io.ExceptionTaken := exceptionCommit || mretCommit ||
    (if (hasS) sretCommit else false.B)
  io.ExceptionTarget := (
    if (hasS) {
      Mux(
        exceptionCommit,
        Mux(trapToS, stvecTrapTarget, mtvecTrapTarget),
        Mux(mretCommit, mepcReadData, sepcReadData)
      )
    } else {
      // HAS_S=n：不存在 S 入口；异常/中断进入 M，MRET 返回 mepc。
      Mux(exceptionCommit, mtvecTrapTarget, mepcReadData)
    }
  )

  // 退休级快照表示本指令执行后的 CSR 状态。高半区也要带上 RV64
  // mstatus.SXL/UXL，保证 DiffTest 的 CSR 视图与 CSR 读口一致。
  io.StateMstatus := mstatusArchValue(MuxCase(mstatusReadData,
    Seq(
      exceptionCommit -> mstatusExceptionData,
      mretCommit -> mstatusMretData
    ) ++ (if (hasS) Seq(sretCommit -> mstatusSretStateData) else Seq.empty) ++
      Seq(mstatusWriteEnable -> MstatusCSRData, sstatusFsWriteEnable -> sstatusFsWriteData,
        FPCSRWriteIntent -> fsDirtyValue,
        fsDirtyCommit -> fsDirtyValue)))
  val MtvecCSRData = CSRWriteData(mtvecReadData)
  val mtvecLegalMode = Mux(MtvecCSRData(1, 0) === 1.U, 1.U(2.W), 0.U(2.W))
  io.StateMtvec := Mux(CSRWriteEnable && isCSRAddress(CSRMtvec),
    Cat(MtvecCSRData(XLEN - 1, 2), mtvecLegalMode), mtvecReadData)
  io.StateMepc := Mux(exceptionCommit && !trapToS,
    alignEPC(io.PC),
    Mux(mepcWriteEnable, alignEPC(CSRWriteData(mepcReadData)), mepcReadData))
  io.StateMcause := Mux(exceptionCommit && !trapToS, widenCause32(exceptionCause),
    Mux(mcauseWriteEnable, CSRWriteData(mcauseReadData), mcauseReadData))
  // 只在 trap 提交拍给出本次写入的 tval；普通指令/软件写 CSR 不参与
  // DiffTest 的 trap 注入。
  io.StateTval := Mux(exceptionCommit, trapTvalValue, 0.U(XLEN.W))
  io.McounterenOut := mcounteren
  io.ScounterenOut := scounterenReadData
  for (entry <- 0 until 16) {
    io.PMPConfig(entry) := pmpcfgReg(entry / PMPEntriesPerConfig)(
      8 * (entry % PMPEntriesPerConfig) + 7,
      8 * (entry % PMPEntriesPerConfig)
    )
    io.PMPAddress(entry) := pmpaddrReg(entry)
  }
}
