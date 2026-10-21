package ysyx_26030103.exu
import chisel3._
import chisel3.util._

/** 机器/监管者 CSR 与精确异常入口。
  *
  * 当前实现支持 M/S/U 三个特权级、S 模式 CSR、异常委托和 SRET。
  * 页表翻译本身在 MMU 中完成；这里负责 satp、stvec/sepc/scause/stval
  * 以及异常入口的特权级切换。
  */
class ysyx_26030103_CSR(
    val profile: _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile =
      _root_.ysyx_26030103.config.ysyx_26030103_MMUProfile()
) extends Module {
  import ysyx_26030103_mstatus.legalize
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
    val Rs1Data = Input(UInt(32.W))
    val pc = Input(UInt(32.W))
    val TrapTval = Input(UInt(32.W))
    val CSR_rdata = Output(UInt(32.W))
    val ExceptionTaken = Output(Bool())
    val ExceptionTarget = Output(UInt(32.W))
    val FlushTLB = Output(Bool())
    val Privilege = Output(UInt(2.W))
    val Satp = Output(UInt(32.W))

    val Interrupt = Input(Bool())
    val TimerInterrupt = Input(Bool())
    val TrapValid = Input(Bool())
    val TrapCause = Input(UInt(32.W))
    val MemTrap = Input(Bool())
    val IrqCommit = Output(Bool())
    val IrqPending = Output(Bool())
    val TrapCommit = Output(Bool())
    val CommittedCause = Output(UInt(32.W))

    val CurrentMstatus = Output(UInt(32.W))
    val StateMstatus = Output(UInt(32.W))
    val StateMtvec = Output(UInt(32.W))
    val StateMepc = Output(UInt(32.W))
    val StateMcause = Output(UInt(32.W))
    val PmpCfg = Output(Vec(16, UInt(8.W)))
    val PmpAddr = Output(Vec(16, UInt(32.W)))
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

  val Mvendorid_rdata = 0x79737978.U(32.W)
  val Marchid_rdata = "h018d3017".U(32.W)
  val SstatusMask = "h000c0122".U(32.W) // SIE SPIE SPP SUM MXR
  def IsCSRAddress(addr: UInt): Bool = io.CSRAddress === addr
  private val enabledCsrAddresses = (
    profile.csrEnabled.toSeq ++
      (if (profile.pmpEnabled) (0x3a0 to 0x3a3) ++ (0x3b0 to 0x3bf) else Seq.empty)
  ).filter(profile.csrSupported).distinct.sorted
  val CsrImplemented =
    enabledCsrAddresses.map(a => io.CSRAddress === a.U(12.W))
      .reduceOption(_ || _).getOrElse(false.B)
  val CsrUseSstatusView = profile.csrViewMode == "bound"
  def CsrReadMask: UInt = {
    val full = 0xffffffffL.U(32.W)
    val mstatusMask =
      if (profile.csrSupported(0x300)) Mux(io.CSRAddress === CSR_MSTATUS,
        (profile.csrMstatusReadMask.toLong & 0xffffffffL).U(32.W), full) else full
    if (profile.csrSupported(0x180)) Mux(io.CSRAddress === CSR_SATP,
      (profile.csrSatpReadMask.toLong & 0xffffffffL).U(32.W), mstatusMask) else mstatusMask
  }
  def CsrReadSet: UInt = {
    val mstatusSet =
      if (profile.csrSupported(0x300)) Mux(io.CSRAddress === CSR_MSTATUS,
        (profile.csrMstatusReadSet.toLong & 0xffffffffL).U(32.W), 0.U(32.W)) else 0.U(32.W)
    if (profile.csrSupported(0x180)) Mux(io.CSRAddress === CSR_SATP,
      (profile.csrSatpReadSet.toLong & 0xffffffffL).U(32.W), mstatusSet) else mstatusSet
  }
  def CsrWriteMask: UInt = {
    val full = 0xffffffffL.U(32.W)
    val mstatusMask =
      if (profile.csrSupported(0x300)) Mux(io.CSRAddress === CSR_MSTATUS,
        (profile.csrMstatusWriteMask.toLong & 0xffffffffL).U(32.W), full) else full
    if (profile.csrSupported(0x180)) Mux(io.CSRAddress === CSR_SATP,
      (profile.csrSatpWriteMask.toLong & 0xffffffffL).U(32.W), mstatusMask) else mstatusMask
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
  val SretCommit = io.Enable && io.IsSret
  val SfenceCommit = io.Enable && io.IsSfenceVma

  val Mstatus = Module(new ysyx_26030103_mstatus)
  val Mtvec = Module(new ysyx_26030103_mtvec)
  val Mepc = Module(new ysyx_26030103_mepc)
  val Mcause = Module(new ysyx_26030103_mcause)
  val Stvec = Module(new ysyx_26030103_mtvec)
  val Sepc = Module(new ysyx_26030103_mepc)
  val Scause = Module(new ysyx_26030103_mcause)
  val Mcycle = Module(new ysyx_26030103_mcycle)

  Mstatus.io.clk := io.clk; Mstatus.io.rst := io.rst
  Mtvec.io.clk := io.clk; Mtvec.io.rst := io.rst
  Mepc.io.clk := io.clk; Mepc.io.rst := io.rst
  Mcause.io.clk := io.clk; Mcause.io.rst := io.rst
  Stvec.io.clk := io.clk; Stvec.io.rst := io.rst
  Sepc.io.clk := io.clk; Sepc.io.rst := io.rst
  Scause.io.clk := io.clk; Scause.io.rst := io.rst
  Mcycle.io.clk := io.clk; Mcycle.io.rst := io.rst

  val Mscratch = RegInit(0.U(32.W))
  val Sscratch = RegInit(0.U(32.W))
  val SstatusReg = RegInit(0.U(32.W))
  val Medeleg = RegInit(0.U(32.W))
  val Mideleg = RegInit(0.U(32.W))
  val Mie = RegInit(0.U(32.W))
  val Mip = RegInit(0.U(32.W))
  val Sie = RegInit(0.U(32.W))
  val Sip = RegInit(0.U(32.W))
  val Scounteren = RegInit(0.U(32.W))
  val Mcounteren = RegInit(0.U(32.W))
  val Mcountinhibit = RegInit(0.U(32.W))
  val Mtval = RegInit(0.U(32.W))
  val Minstret = RegInit(0.U(64.W))
  val PmpcfgReg = RegInit(VecInit(Seq.fill(4)(0.U(32.W))))
  val PmpaddrReg = RegInit(VecInit(Seq.fill(16)(0.U(32.W))))
  val Stval = RegInit(0.U(32.W))
  val SatpReg = RegInit(0.U(32.W))
  val SatpReadData =
    (SatpReg & (profile.csrSatpReadMask & 0xffffffffL).U(32.W)) | (profile.csrSatpReadSet.toLong & 0xffffffffL).U(32.W)
  val Privilege = RegInit(3.U(2.W))

  val Mstatus_rdata = Mstatus.io.rdata
  val MstatusReadData =
    (Mstatus_rdata & (profile.csrMstatusReadMask.toLong & 0xffffffffL).U(32.W)) |
      (profile.csrMstatusReadSet.toLong & 0xffffffffL).U(32.W)
  val Sstatus_rdata =
    if (profile.csrViewMode == "independent") SstatusReg else MstatusReadData & SstatusMask
  val Mtvec_rdata = Mtvec.io.rdata
  val Mepc_rdata = Mepc.io.rdata
  val Mcause_rdata = Mcause.io.rdata
  val Stvec_rdata = Stvec.io.rdata
  val Sepc_rdata = Sepc.io.rdata
  val Scause_rdata = Scause.io.rdata
  val Mcycle_rdata = Mcycle.io.rdata
  val Minstret_rdata = Minstret(31, 0)
  val Minstreth_rdata = Minstret(63, 32)
  val Misa_rdata = "h40000100".U(32.W)

  val IrqLatch = RegInit(false.B)
  when(io.Interrupt || io.TimerInterrupt) { IrqLatch := true.B }
  val SInterruptMask = "h00000222".U(32.W) // SSIP/STIP/SEIP
  val TimerView = if (profile.timerPath == "direct_stip")
    (if (profile.hasSTimerIrq)
      Mux(io.TimerInterrupt, "h00000020".U(32.W), 0.U(32.W))
    else 0.U(32.W))
  else
    Mux(io.TimerInterrupt, "h00000080".U(32.W), 0.U(32.W))
  val ExternalSView = if (profile.hasSExternalIrq)
    Mux(io.Interrupt, "h00000200".U(32.W), 0.U(32.W))
  else 0.U(32.W)
  val ExternalMView = if (!profile.hasSExternalIrq)
    Mux(io.Interrupt, "h00000800".U(32.W), 0.U(32.W))
  else 0.U(32.W)
  val MipState = Mip | TimerView | ExternalSView | ExternalMView
  val SieState = if (profile.csrViewMode == "independent") Sie else Mie & SInterruptMask
  val SipState = if (profile.csrViewMode == "independent") Sip else MipState & SInterruptMask
  val MIntPending = MipState & Mie & ~Mideleg
  val SIntPending = SipState & SieState & Mideleg
  val MIntTaken = MIntPending.orR && Mstatus_rdata(3)
  val SStatusIE =
    if (profile.csrViewMode == "independent") SstatusReg(1) else Mstatus_rdata(1)
  val SIntTaken = SIntPending.orR && SStatusIE && Privilege =/= 3.U
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
  val SIrqCause = pendingCause(SIntPending, sMode = true)
  val IrqCause = Mux(SIntTaken, SIrqCause, MIrqCause)
  val IrqTargetS = SIntTaken

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
    ExceptionCause := Mux(Privilege === 0.U, 8.U(32.W),
      Mux(Privilege === 1.U, 9.U(32.W), 11.U(32.W)))
  }

  val HasIrqCommit = io.Enable && HasInterrupt && !io.MemTrap
  when(HasIrqCommit) { IrqLatch := false.B }
  io.IrqCommit := HasIrqCommit
  val ExceptionCommit = io.MemTrap || (io.Enable && IsException)
  io.TrapCommit := ExceptionCommit
  io.CommittedCause := ExceptionCause

  // 异常委托：只有低于 M 的异常/中断才能被委托给 S。
  val CauseIndex = ExceptionCause(4, 0)
  val Delegation = Mux(ExceptionCause(31), Mideleg, Medeleg)
  val Delegated = Delegation(CauseIndex)
  val TrapToS = Privilege =/= 3.U && Delegated

  val MstatusExceptionData = {
    val mTrap = legalize(
      (Mstatus_rdata & ~"h00001888".U(32.W)) |
        (Privilege << 11) | (Mstatus_rdata(3) << 7)
    )
    val sTrap = legalize(
      (Mstatus_rdata & ~"h00000122".U(32.W)) |
        (Privilege(0) << 8) | (Mstatus_rdata(1) << 5)
    )
    Mux(TrapToS, sTrap, mTrap)
  }
  // MPP 恢复；MIE <- MPIE；MPIE <- 1；MPP <- U（最低支持特权级）。
  val MstatusMretData = legalize(
    (Mstatus_rdata & ~"h00001888".U(32.W)) |
      (Mstatus_rdata(7) << 3) | (1.U << 7)
  )
  // SRET：SPP 决定返回 S/U；SIE <- SPIE；SPIE <- 1；SPP <- 0。
  val MstatusSretData = legalize(
    (Mstatus_rdata & ~"h00000122".U(32.W)) |
      (Mstatus_rdata(5) << 1) | (1.U << 5)
  )

  val MstatusWen =
    if (profile.csrViewMode == "bound")
      CSRWen && (IsCSRAddress(CSR_MSTATUS) || IsCSRAddress(CSR_SSTATUS))
    else CSRWen && IsCSRAddress(CSR_MSTATUS)
  val SstatusWen =
    if (profile.csrViewMode == "independent")
      CSRWen && IsCSRAddress(CSR_SSTATUS)
    else false.B
  val MstatusWriteMask = (profile.csrMstatusWriteMask.toLong & 0xffffffffL).U(32.W)
  val MstatusCSRData = {
    val rawWrite = CSRWriteData(Mstatus_rdata)
    val masked = (Mstatus_rdata & ~MstatusWriteMask) |
      (rawWrite & MstatusWriteMask)
    Mux(
      IsCSRAddress(CSR_SSTATUS),
      legalize((Mstatus_rdata & ~SstatusMask) |
        ((masked | (Mstatus_rdata & ~MstatusWriteMask)) & SstatusMask)),
      legalize(masked)
    )
  }
  Mstatus.io.wen := MstatusWen || ExceptionCommit || MretCommit ||
    (if (profile.csrViewMode == "bound") SretCommit else false.B)
  Mstatus.io.wdata := MuxCase(MstatusCSRData, Seq(
    ExceptionCommit -> MstatusExceptionData,
    MretCommit -> MstatusMretData,
    SretCommit -> MstatusSretData
  ))
  if (profile.csrViewMode == "independent") {
    val sWrite = legalize((SstatusReg & ~SstatusMask) |
      (CSRWriteData(SstatusReg) & SstatusMask))
    val sTrap = legalize((SstatusReg & ~"h00000122".U(32.W)) |
      (SstatusReg(1) << 5) | (Privilege(0) << 8))
    val sRet = legalize((SstatusReg & ~"h00000122".U(32.W)) |
      (SstatusReg(5) << 1) | (1.U << 5))
    when(SstatusWen) { SstatusReg := sWrite }
    when(ExceptionCommit && TrapToS) { SstatusReg := sTrap }
    when(SretCommit) { SstatusReg := sRet }
  }

  Mtvec.io.wen := CSRWen && IsCSRAddress(CSR_MTVEC)
  Mtvec.io.wdata := CSRWriteData(Mtvec_rdata)
  Stvec.io.wen := CSRWen && IsCSRAddress(CSR_STVEC)
  Stvec.io.wdata := CSRWriteData(Stvec_rdata)

  // 只有 M 入口写 mepc/mcause；委托到 S 时写 sepc/scause/stval。
  val MepcWen = CSRWen && IsCSRAddress(CSR_MEPC) && !TrapToS
  Mepc.io.wen := MepcWen || (ExceptionCommit && !TrapToS)
  Mepc.io.ExceptionWE := ExceptionCommit && !TrapToS
  Mepc.io.ExceptionData := io.pc
  Mepc.io.wdata := Mux(ExceptionCommit && !TrapToS, io.pc, CSRWriteData(Mepc_rdata))
  val McauseWen = CSRWen && IsCSRAddress(CSR_MCAUSE) && !TrapToS
  Mcause.io.wen := McauseWen || (ExceptionCommit && !TrapToS)
  Mcause.io.wdata := Mux(ExceptionCommit && !TrapToS, ExceptionCause,
    CSRWriteData(Mcause_rdata))

  val SepcWen = CSRWen && IsCSRAddress(CSR_SEPC)
  Sepc.io.wen := SepcWen || (ExceptionCommit && TrapToS)
  Sepc.io.ExceptionWE := ExceptionCommit && TrapToS
  Sepc.io.ExceptionData := io.pc
  Sepc.io.wdata := Mux(ExceptionCommit && TrapToS, io.pc, CSRWriteData(Sepc_rdata))
  val ScauseWen = CSRWen && IsCSRAddress(CSR_SCAUSE)
  Scause.io.wen := ScauseWen || (ExceptionCommit && TrapToS)
  Scause.io.wdata := Mux(ExceptionCommit && TrapToS, ExceptionCause,
    CSRWriteData(Scause_rdata))

  when(ExceptionCommit && TrapToS) {
    Stval := Mux(io.TrapTval =/= 0.U, io.TrapTval, io.pc)
  }.elsewhen(ExceptionCommit) {
    Mtval := Mux(io.TrapTval =/= 0.U, io.TrapTval, io.pc)
  }.elsewhen(CSRWen && IsCSRAddress(CSR_STVAL)) {
    Stval := CSRWriteData(Stval)
  }
  when(CSRWen && IsCSRAddress(CSR_MTVAL)) { Mtval := CSRWriteData(Mtval) }
  when(CSRWen && IsCSRAddress(CSR_MCOUNTEREN)) { Mcounteren := CSRWriteData(Mcounteren) }
  when(CSRWen && IsCSRAddress(CSR_MCOUNTINHIBIT)) { Mcountinhibit := CSRWriteData(Mcountinhibit) }
  for (word <- 0 until 4) {
    when(CSRWen && io.CSRAddress === (0x3a0 + word).U(12.W)) {
      PmpcfgReg(word) := CSRWriteData(PmpcfgReg(word)) & "h0f0f0f0f".U(32.W)
    }
  }
  for (entry <- 0 until 16) {
    when(CSRWen && io.CSRAddress === (0x3b0 + entry).U(12.W)) {
      PmpaddrReg(entry) := CSRWriteData(PmpaddrReg(entry)) & "h3fffffff".U(32.W)
    }
  }
  when(io.Enable && !Mcountinhibit(0)) { Minstret := Minstret + 1.U }
  when(CSRWen && IsCSRAddress(CSR_SSCRATCH)) { Sscratch := CSRWriteData(Sscratch) }
  when(CSRWen && IsCSRAddress(CSR_MSCRATCH)) { Mscratch := CSRWriteData(Mscratch) }
  when(CSRWen && IsCSRAddress(CSR_MEDELEG)) { Medeleg := CSRWriteData(Medeleg) }
  when(CSRWen && IsCSRAddress(CSR_MIDELEG)) { Mideleg := CSRWriteData(Mideleg) }
  when(CSRWen && IsCSRAddress(CSR_MIE)) { Mie := CSRWriteData(Mie) }
  when(CSRWen && IsCSRAddress(CSR_MIP)) { Mip := CSRWriteData(Mip) }
  if (profile.csrViewMode == "independent") {
    when(CSRWen && IsCSRAddress(CSR_SIE)) { Sie := CSRWriteData(Sie) }
    when(CSRWen && IsCSRAddress(CSR_SIP)) { Sip := CSRWriteData(Sip) }
  } else {
    when(CSRWen && IsCSRAddress(CSR_SIE)) {
      Mie := (Mie & ~SInterruptMask) | (CSRWriteData(Mie) & SInterruptMask)
    }
    when(CSRWen && IsCSRAddress(CSR_SIP)) {
      Mip := (Mip & ~SInterruptMask) | (CSRWriteData(Mip) & SInterruptMask)
    }
  }
  when(CSRWen && IsCSRAddress(CSR_SCOUNTEREN)) { Scounteren := CSRWriteData(Scounteren) }
  val SatpWen = CSRWen && IsCSRAddress(CSR_SATP)
  when(SatpWen) {
    val raw = CSRWriteData(SatpReg)
    val data = (SatpReg & ~(profile.csrSatpWriteMask & 0xffffffffL).U(32.W)) |
      (raw & (profile.csrSatpWriteMask & 0xffffffffL).U(32.W))
    SatpReg := Cat(Mux(data(31), 1.U(1.W), 0.U(1.W)), data(30, 0))
  }
  io.Satp := SatpReg
  io.Privilege := Privilege
  io.CurrentMstatus := Mstatus_rdata
  io.FlushTLB := SfenceCommit || SatpWen

  when(ExceptionCommit) {
    Privilege := Mux(TrapToS, 1.U, 3.U)
  }.elsewhen(MretCommit) {
    Privilege := Mstatus_rdata(12, 11)
  }.elsewhen(SretCommit) {
    Privilege := Mux(Mstatus_rdata(8), 1.U, 0.U)
  }

  val McycleAccess = IsCSRAddress(CSR_MCYCLE) || IsCSRAddress(CSR_MCYCLEH)
  Mcycle.io.wen := CSRWen && McycleAccess
  Mcycle.io.SelectHigh := IsCSRAddress(CSR_MCYCLEH)
  Mcycle.io.wdata := CSRWriteData(Mcycle_rdata)

  val CSRMap = Seq(
    CSR_MSTATUS -> MstatusReadData,
    CSR_SSTATUS -> Sstatus_rdata,
    CSR_MTVEC -> Mtvec_rdata,
    CSR_STVEC -> Stvec_rdata,
    CSR_MEPC -> Mepc_rdata,
    CSR_SEPC -> Sepc_rdata,
    CSR_MCAUSE -> Mcause_rdata,
    CSR_SCAUSE -> Scause_rdata,
    CSR_MSCRATCH -> Mscratch,
    CSR_SSCRATCH -> Sscratch,
    CSR_MEDELEG -> Medeleg,
    CSR_MIDELEG -> Mideleg,
    CSR_MIE -> Mie,
    CSR_MIP -> Mip,
    CSR_SIE -> (if (profile.csrViewMode == "independent") Sie else Mie & SInterruptMask),
    CSR_SIP -> (if (profile.csrViewMode == "independent") Sip else MipState & SInterruptMask),
    CSR_SCOUNTEREN -> Scounteren,
    CSR_STVAL -> Stval,
    CSR_SATP -> SatpReadData,
    CSR_MCYCLE -> Mcycle_rdata,
    CSR_MCYCLEH -> Mcycle_rdata,
    CSR_MINSTRET -> Minstret_rdata,
    CSR_MINSTRETH -> Minstreth_rdata,
    CSR_MISA -> Misa_rdata,
    CSR_MTVAL -> Mtval,
    CSR_MCOUNTEREN -> Mcounteren,
    CSR_MCOUNTINHIBIT -> Mcountinhibit,
    CSR_MVENDORID -> Mvendorid_rdata,
    CSR_MARCHID -> Marchid_rdata
  ) ++ (0 until 4).map(i => (0x3a0 + i).U(12.W) -> PmpcfgReg(i)) ++
    (0 until 16).map(i => (0x3b0 + i).U(12.W) -> PmpaddrReg(i))
  val CsrRawRdata = MuxLookup(io.CSRAddress, 0.U(32.W))(CSRMap)
  io.CSR_rdata := Mux(CsrImplemented,
    (CsrRawRdata & CsrReadMask) | CsrReadSet, 0.U(32.W))

  val MtvecBase = Cat(Mtvec_rdata(31, 2), 0.U(2.W))
  val StvecBase = Cat(Stvec_rdata(31, 2), 0.U(2.W))
  val CauseOffsetWide = ExceptionCause(30, 0) << 2
  val CauseOffset = CauseOffsetWide(31, 0)
  val MtvecTrapTarget = Mux(
    Mtvec_rdata(1, 0) === 1.U && ExceptionCause(31),
    MtvecBase + CauseOffset, MtvecBase)
  val StvecTrapTarget = Mux(
    Stvec_rdata(1, 0) === 1.U && ExceptionCause(31),
    StvecBase + CauseOffset, StvecBase)

  io.ExceptionTaken := ExceptionCommit || MretCommit || SretCommit
  io.ExceptionTarget := Mux(
    ExceptionCommit,
    Mux(TrapToS, StvecTrapTarget, MtvecTrapTarget),
    Mux(MretCommit, Mepc_rdata, Sepc_rdata)
  )

  // 退休级快照表示本指令执行后的 CSR 状态。
  val MstatusCSRNext = MuxCase(MstatusCSRData, Seq(
    ExceptionCommit -> MstatusExceptionData,
    MretCommit -> MstatusMretData,
    SretCommit -> MstatusSretData
  ))
  io.StateMstatus := MuxCase(Mstatus_rdata, Seq(
    ExceptionCommit -> MstatusExceptionData,
    MretCommit -> MstatusMretData,
    SretCommit -> MstatusSretData,
    MstatusWen -> MstatusCSRData
  ))
  val MtvecCSRData = CSRWriteData(Mtvec_rdata)
  val MtvecLegalMode = Mux(MtvecCSRData(1, 0) === 1.U, 1.U(2.W), 0.U(2.W))
  io.StateMtvec := Mux(CSRWen && IsCSRAddress(CSR_MTVEC),
    Cat(MtvecCSRData(31, 2), MtvecLegalMode), Mtvec_rdata)
  io.StateMepc := Mux(ExceptionCommit && !TrapToS,
    Cat(io.pc(31, 2), 0.U(2.W)),
    Mux(MepcWen, Cat(CSRWriteData(Mepc_rdata)(31, 2), 0.U(2.W)), Mepc_rdata))
  io.StateMcause := Mux(ExceptionCommit && !TrapToS, ExceptionCause,
    Mux(McauseWen, CSRWriteData(Mcause_rdata), Mcause_rdata))
  for (entry <- 0 until 16) {
    io.PmpCfg(entry) := PmpcfgReg(entry / 4)(8 * (entry % 4) + 7, 8 * (entry % 4))
    io.PmpAddr(entry) := PmpaddrReg(entry)
  }
}
