package ysyx_26030103
import chisel3._
import chisel3.util._

import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.cache._
import _root_.ysyx_26030103.infra._
import _root_.ysyx_26030103.ifu._
import _root_.ysyx_26030103.idu._
import _root_.ysyx_26030103.exu._
import _root_.ysyx_26030103.mem._
import _root_.ysyx_26030103.wbu._
import _root_.ysyx_26030103.mmu._

private[ysyx_26030103] trait ObservabilityConnect { this: ysyx_26030103 =>
  def connectObservability(): Unit = {
    // 仿真 halt 是 custom-0 指令的提交事件，而不是架构 EBREAK。再寄存
    // 一拍后暴露给 C++，保证上升沿上的所有更老 GPR 写回已经生效。
    val simHaltCommit = exu.io.SimHaltValid
    io.trap.valid := RegNext(simHaltCommit, false.B)
    val SimHaltPC =
      RegEnable(exu.io.SimHaltPC, config.ResetAddress.U(XLEN.W), simHaltCommit)
    io.trap.PC := SimHaltPC(31, 0)
    io.trap.PCHi := DebugView.highWord(SimHaltPC, XLEN)
    // 固定 ABI：关闭动态调试读口时，GPR 模块忽略地址并将这里直连到 a0，
    // 供 custom halt 判定 GOOD/BAD；不会为此保留第三个 32 路读 mux。
    GPR.io.DebugRaddr := io.debug.GPRRaddr
    io.debug.GPRRdata := GPR.io.DebugReadData(31, 0)
    io.debug.GPRRdataHi := DebugView.highWord(GPR.io.DebugReadData, XLEN)
    // WBUCommit 与最小架构 PC 不属于可选观测逻辑。异常/中断即使在所有
    // 调试功能关闭时也需要精确的最近架构边界。
    val WBUCommit = wbu.io.in.fire && wbu.io.in.bits.Retire
    // fcsr 的 DiffTest 快照必须与 WBU 退休事件对齐。CSR 在 EXU 提交点已
    // 更新 fflags/frm；这里在退休沿锁存，避免年轻但尚未退休的 FP 指令
    // 把未提交状态提前暴露给 DiffTest。FLEN=32/RV32F 同样只有低 8 位有效。
    if (config.UseF) {
      val retireFcsr = RegEnable(exu.io.CSRFcsr, 0.U(8.W), WBUCommit)
      io.debug.fcsr := Cat(0.U(24.W), retireFcsr)
    } else {
      io.debug.fcsr := 0.U
    }
    if (config.EnableDynamicGPRDebug) {
      // DiffTest 在上升沿前采样实际物理写口，沿后只回读被写寄存器。
      io.debug.GPRWen := wbu.io.WriteEN && (wbu.io.WriteSELECT =/= 0.U)
      io.debug.GPRWaddr := wbu.io.WriteSELECT
      // 浮点写口与 GPR 写口同拍、同一边沿语义。C++ 只需要回读真正被写的
      // f 寄存器，避免每条退休指令都做 32 次差分读。
      io.debug.FPRWen := wbu.io.FPWriteEN
      io.debug.FPRWaddr := wbu.io.FPWriteSELECT
    } else {
      io.debug.GPRWen := false.B
      io.debug.GPRWaddr := 0.U
      io.debug.FPRWen := false.B
      io.debug.FPRWaddr := 0.U
    }
    if (config.EnableRetireTrace) {
      // C++ 在上升沿后看到 commit，此时 GPR 已是该指令提交后的状态。
      io.debug.commit := RegNext(WBUCommit, false.B)
      val RetirePC = RegEnable(
        wbu.io.in.bits.PC,
        config.ResetAddress.U(XLEN.W),
        WBUCommit
      )
      val RetireNextPC = RegEnable(
        wbu.io.in.bits.NextPC,
        config.ResetAddress.U(XLEN.W),
        WBUCommit
      )
      io.debug.PC := RetirePC(31, 0)
      io.debug.PCHi := DebugView.highWord(RetirePC, XLEN)
      io.debug.NextPC := RetireNextPC(31, 0)
      io.debug.NextPCHi := DebugView.highWord(RetireNextPC, XLEN)
      io.debug.instructions := RegEnable(
        wbu.io.in.bits.Instruction,
        "h00000013".U(32.W),
        WBUCommit
      )
    } else {
      io.debug.commit := false.B
      io.debug.PC := 0.U
      io.debug.PCHi := 0.U
      io.debug.NextPC := 0.U
      io.debug.NextPCHi := 0.U
      io.debug.instructions := 0.U
    }

    // 调试器的 $PC 不能复用 retire trace PC。正常退休时前进到
    // 该指令的实际后继；异常/中断提交时直接记录 trap 目标。
    // 若同拍既有更老的 WBU 退休又有年轻 trap，trap 必须优先。
    val ArchitecturalPC = RegInit(config.ResetAddress.U(XLEN.W))
    // 空流水中断没有随指令携带的 PC；同拍若有更老退休，则以该指令后继
    // 为边界，否则使用最近架构 PC。此路径是架构状态，不能随调试功能裁剪。
    exu.io.IdleIRQPC := Mux(WBUCommit, wbu.io.in.bits.NextPC, ArchitecturalPC)
    when(WBUCommit) {
      ArchitecturalPC := wbu.io.in.bits.NextPC
    }
    when(exu.io.TrapCommit) {
      ArchitecturalPC := exu.io.TrapTarget
    }
    io.debug.ArchPC := ArchitecturalPC(31, 0)
    io.debug.ArchPCHi := DebugView.highWord(ArchitecturalPC, XLEN)

    if (config.EnableTrapTrace) {
      io.debug.TrapValid := RegNext(exu.io.TrapCommit, false.B)
      val TrapPC = RegEnable(
        exu.io.TrapPC,
        config.ResetAddress.U(XLEN.W),
        exu.io.TrapCommit
      )
      val trapTarget = RegEnable(
        exu.io.TrapTarget,
        config.ResetAddress.U(XLEN.W),
        exu.io.TrapCommit
      )
      io.debug.TrapPC := TrapPC(31, 0)
      io.debug.TrapPCHi := DebugView.highWord(TrapPC, XLEN)
      io.debug.TrapTarget := trapTarget(31, 0)
      io.debug.TrapTargetHi := DebugView.highWord(trapTarget, XLEN)
      io.debug.TrapCause := RegEnable(
        exu.io.TrapCause,
        0.U(32.W),
        exu.io.TrapCommit
      )
      // 与 cause 同一拍锁存，保证 DiffTest 看到的是本次 trap 的 tval。
      val trapTval = RegEnable(
        exu.io.CSRStateTval,
        0.U(XLEN.W),
        exu.io.TrapCommit
      )
      io.debug.TrapTval := trapTval(31, 0)
      io.debug.TrapTvalHi := DebugView.highWord(trapTval, XLEN)
    } else {
      io.debug.TrapValid := false.B
      io.debug.TrapPC := 0.U
      io.debug.TrapPCHi := 0.U
      io.debug.TrapTarget := 0.U
      io.debug.TrapTargetHi := 0.U
      io.debug.TrapCause := 0.U
      io.debug.TrapTval := 0.U
      io.debug.TrapTvalHi := 0.U
    }
    if (config.EnableCSRTrace) {
      val retireMstatus = RegEnable(
        wbu.io.in.bits.CSRStateMstatus,
        DebugView.mstatusResetValue(XLEN),
        WBUCommit
      )
      val retireMtvec = RegEnable(
        wbu.io.in.bits.CSRStateMtvec,
        0.U(XLEN.W),
        WBUCommit
      )
      val retireMepc = RegEnable(
        wbu.io.in.bits.CSRStateMepc,
        0.U(XLEN.W),
        WBUCommit
      )
      io.debug.CSRMstatus := retireMstatus(31, 0)
      io.debug.CSRMstatusHi := DebugView.highWord(retireMstatus, XLEN)
      io.debug.CSRMtvec := retireMtvec(31, 0)
      io.debug.CSRMtvecHi := DebugView.highWord(retireMtvec, XLEN)
      io.debug.CSRMepc := retireMepc(31, 0)
      io.debug.CSRMepcHi := DebugView.highWord(retireMepc, XLEN)
      val retireMcause = RegEnable(
        wbu.io.in.bits.CSRStateMcause,
        0.U(XLEN.W),
        WBUCommit
      )
      io.debug.CSRMcause := retireMcause(31, 0)
      io.debug.CSRMcauseHi := DebugView.highWord(retireMcause, XLEN)
      val trapMstatus = RegEnable(
        exu.io.CSRStateMstatus,
        DebugView.mstatusResetValue(XLEN),
        exu.io.TrapCommit
      )
      val trapMtvec = RegEnable(
        exu.io.CSRStateMtvec,
        0.U(XLEN.W),
        exu.io.TrapCommit
      )
      val trapMepc = RegEnable(
        exu.io.CSRStateMepc,
        0.U(XLEN.W),
        exu.io.TrapCommit
      )
      io.debug.TrapMstatus := trapMstatus(31, 0)
      io.debug.TrapMstatusHi := DebugView.highWord(trapMstatus, XLEN)
      io.debug.TrapMtvec := trapMtvec(31, 0)
      io.debug.TrapMtvecHi := DebugView.highWord(trapMtvec, XLEN)
      io.debug.TrapMepc := trapMepc(31, 0)
      io.debug.TrapMepcHi := DebugView.highWord(trapMepc, XLEN)
      val trapMcause = RegEnable(
        exu.io.CSRStateMcause,
        0.U(XLEN.W),
        exu.io.TrapCommit
      )
      io.debug.TrapMcause := trapMcause(31, 0)
      io.debug.TrapMcauseHi := DebugView.highWord(trapMcause, XLEN)
    } else {
      io.debug.CSRMstatus := 0.U
      io.debug.CSRMstatusHi := 0.U
      io.debug.CSRMtvec := 0.U
      io.debug.CSRMtvecHi := 0.U
      io.debug.CSRMepc := 0.U
      io.debug.CSRMepcHi := 0.U
      io.debug.CSRMcause := 0.U
      io.debug.CSRMcauseHi := 0.U
      io.debug.TrapMstatus := 0.U
      io.debug.TrapMstatusHi := 0.U
      io.debug.TrapMtvec := 0.U
      io.debug.TrapMtvecHi := 0.U
      io.debug.TrapMepc := 0.U
      io.debug.TrapMepcHi := 0.U
      io.debug.TrapMcause := 0.U
      io.debug.TrapMcauseHi := 0.U
    }

    // mtrace 必须与 WBU retire 记录对齐，而不是早一拍的 LSU out.fire。
    // DiffTest 在 debug_commit 拍用它判断 MMIO；错一拍会让 mtime 在 REF 真正
    // 执行并产生伪差异。错误响应/非对齐访问不 Retire，不会到达这里。
    if (config.EnableMemoryTrace) {
      val MTraceCommit = WBUCommit && wbu.io.in.bits.MemoryValid
      io.debug.MTraceValid := RegNext(MTraceCommit, false.B)
      val MTracePC = RegEnable(wbu.io.in.bits.PC, 0.U(XLEN.W), MTraceCommit)
      val MTraceAddress = RegEnable(
        wbu.io.in.bits.ALUResult,
        0.U(XLEN.W),
        MTraceCommit
      )
      val MTraceWriteData = RegEnable(
        wbu.io.in.bits.StoreData,
        0.U(XLEN.W),
        MTraceCommit
      )
      val MTraceReadData = RegEnable(
        wbu.io.in.bits.LoadData,
        0.U(XLEN.W),
        MTraceCommit
      )
      io.debug.MTracePC := MTracePC(31, 0)
      io.debug.MTracePCHi := DebugView.highWord(MTracePC, XLEN)
      io.debug.MTraceWen := RegEnable(
        wbu.io.in.bits.MemoryWrite,
        false.B,
        MTraceCommit
      )
      io.debug.MTraceAddress := MTraceAddress(31, 0)
      io.debug.MTraceAddressHi := DebugView.highWord(MTraceAddress, XLEN)
      io.debug.MTraceWdata := MTraceWriteData(31, 0)
      io.debug.MTraceWdataHi := DebugView.highWord(MTraceWriteData, XLEN)
      io.debug.MTraceRdata := MTraceReadData(31, 0)
      io.debug.MTraceRdataHi := DebugView.highWord(MTraceReadData, XLEN)
      io.debug.MTraceWidth := RegEnable(
        wbu.io.in.bits.WidthSelect,
        0.U(2.W),
        MTraceCommit
      )
    } else {
      io.debug.MTraceValid := false.B
      io.debug.MTracePC := 0.U
      io.debug.MTracePCHi := 0.U
      io.debug.MTraceWen := false.B
      io.debug.MTraceAddress := 0.U
      io.debug.MTraceAddressHi := 0.U
      io.debug.MTraceWdata := 0.U
      io.debug.MTraceWdataHi := 0.U
      io.debug.MTraceRdata := 0.U
      io.debug.MTraceRdataHi := 0.U
      io.debug.MTraceWidth := 0.U
    }

    // 调试 Access Fault 只认精确提交事件。取指错误必须随指令
    // 通过流水线到 EXU，错路响应在此前被 flush 就不得打印。
    // LSU 仍以精确 MemTrap 提交为界；cause=4/6 非对齐不是 AXI fault。
    if (config.EnableAnyObservability) {
      val LSUAccessFaultCommit = LSU.io.AccessFault
      val iCacheAccessFaultCommit = exu.io.FetchAccessFaultCommit
      val accessFaultCommit = LSUAccessFaultCommit || iCacheAccessFaultCommit
      io.debug.AccessFault := RegNext(accessFaultCommit, false.B)
      val AccessFaultPC = RegEnable(
        Mux(LSUAccessFaultCommit, LSU.io.MemTrapPC, exu.io.FetchAccessFaultPC),
        0.U(XLEN.W),
        accessFaultCommit
      )
      io.debug.AccessFaultPC := AccessFaultPC(31, 0)
      io.debug.AccessFaultPCHi := DebugView.highWord(AccessFaultPC, XLEN)
      io.debug.AccessFaultResponse := RegEnable(
        Mux(
          LSUAccessFaultCommit,
          LSU.io.AccessFaultResponse,
          exu.io.FetchAccessFaultResponse
        ),
        0.U(2.W),
        accessFaultCommit
      )
    } else {
      io.debug.AccessFault := false.B
      io.debug.AccessFaultPC := 0.U
      io.debug.AccessFaultPCHi := 0.U
      io.debug.AccessFaultResponse := 0.U
    }
    // 性能计数器：按Kconfig单计数器开关逐个生成。关闭的计数器在顶层输出
    // 常量，未被读取的叶模块信号会被综合展平/常量传播与Verilator死代码
    // 消除一并裁掉；顶层端口ABI保持不变，未启用的计数器对应CSV列恒为0。
    def perfDicArchBool(key: String)(signal: => Bool): Bool =
      if (config.EnablePerfDicArchCounter(key)) { signal }
      else { false.B }
    def perfDicArchUInt(key: String, width: Int)(signal: => UInt): UInt =
      if (config.EnablePerfDicArchCounter(key)) { signal }
      else { 0.U(width.W) }
    // 架构 PMU 事件总线。事件编号与 ysyx_26030103_PMUEventId 一致；
    // 未打开 PMU 时总线保持 0，综合可以删除全部事件选择逻辑。
    val pmuEventBits =
      Wire(Vec(ysyx_26030103_PMUEventID.BusWidth, Bool()))
    pmuEventBits := VecInit(Seq.fill(ysyx_26030103_PMUEventID.BusWidth)(false.B))
    if (config.RISCVArchPMU) {
      pmuEventBits(ysyx_26030103_PMUEventID.Cycles) := true.B
      pmuEventBits(ysyx_26030103_PMUEventID.Instructions) := exu.io.RetireCommit
      pmuEventBits(ysyx_26030103_PMUEventID.CacheReference) :=
        ICache.io.perf_hit || ICache.io.perf_miss ||
          LSU.io.DCachePerfHit || LSU.io.DCachePerfMiss
      pmuEventBits(ysyx_26030103_PMUEventID.CacheMiss) :=
        ICache.io.perf_miss || LSU.io.DCachePerfMiss
      pmuEventBits(ysyx_26030103_PMUEventID.DCacheAccess) :=
        LSU.io.DCachePerfHit || LSU.io.DCachePerfMiss
      pmuEventBits(ysyx_26030103_PMUEventID.DCacheMiss) := LSU.io.DCachePerfMiss
      pmuEventBits(ysyx_26030103_PMUEventID.BranchInstruction) :=
        exu.io.PerfBranchOp || exu.io.PerfJalOp || exu.io.PerfJalrOp
      pmuEventBits(ysyx_26030103_PMUEventID.BranchMispredict) :=
        exu.io.PerfBranchMispredict
      pmuEventBits(ysyx_26030103_PMUEventID.LoadInstruction) := exu.io.PerfLoadOp
      pmuEventBits(
        ysyx_26030103_PMUEventID.StoreInstruction
      ) := exu.io.PerfStoreOp
      pmuEventBits(ysyx_26030103_PMUEventID.CSRInstruction) := exu.io.PerfCSROp
      pmuEventBits(ysyx_26030103_PMUEventID.Trap) := exu.io.PerfTrap
      pmuEventBits(ysyx_26030103_PMUEventID.ITLBMiss) :=
        MMUUnitOpt.map(_.io.perf_itlb_miss).getOrElse(false.B)
      pmuEventBits(ysyx_26030103_PMUEventID.DTLBMiss) :=
        MMUUnitOpt.map(_.io.perf_dtlb_miss).getOrElse(false.B)
      pmuEventBits(ysyx_26030103_PMUEventID.TLBRefill) :=
        MMUUnitOpt.map(_.io.perf_tlb_refill).getOrElse(false.B)
      pmuEventBits(ysyx_26030103_PMUEventID.PTWWalk) :=
        MMUUnitOpt.map(_.io.perf_ptw_walk).getOrElse(false.B)
      pmuEventBits(ysyx_26030103_PMUEventID.AXIRead) :=
        io.master.rvalid && io.master.rready
      pmuEventBits(ysyx_26030103_PMUEventID.AXIWrite) :=
        io.master.bvalid && io.master.bready
      pmuEventBits(ysyx_26030103_PMUEventID.IFUStall) :=
        ICache.io.ResponseValid && !ICache.io.ResponseReady
      pmuEventBits(ysyx_26030103_PMUEventID.FlushCycle) := pipeFlush
      pmuEventBits(ysyx_26030103_PMUEventID.InterruptTaken) :=
        exu.io.PerfInterruptTaken
      pmuEventBits(ysyx_26030103_PMUEventID.ExternalIRQ) := exu.io.Interrupt
      pmuEventBits(ysyx_26030103_PMUEventID.TimerIRQ) := exu.io.TimerInterrupt
      pmuEventBits(ysyx_26030103_PMUEventID.FPUActive) := exu.io.PerfFPUActive
      pmuEventBits(ysyx_26030103_PMUEventID.MDUActive) := exu.io.PerfMDUActive
      pmuEventBits(ysyx_26030103_PMUEventID.WBUfFull) := LSU.io.StallWriteRequest
      pmuEventBits(ysyx_26030103_PMUEventID.UncachedAccess) :=
        LSU.io.Complete && !LSU.io.DCachePerfHit && !LSU.io.DCachePerfMiss
      pmuEventBits(ysyx_26030103_PMUEventID.ICacheLineFill) :=
        ICache.io.perf_line_fill
      pmuEventBits(ysyx_26030103_PMUEventID.DCacheLineFill) :=
        LSU.io.DCachePerfLineFill
      pmuEventBits(ysyx_26030103_PMUEventID.ICacheAccess) :=
        ICache.io.perf_hit || ICache.io.perf_miss
      pmuEventBits(ysyx_26030103_PMUEventID.ICacheMiss) := ICache.io.perf_miss
    }
    exu.io.PMUEvents := pmuEventBits.asUInt
    // 基础吞吐
    // 冲刷拍（pipeFlush）的响应被 StageConnect 消费后直接丢弃，不算交付。
    io.perf.ifu_fetch := perfDicArchBool("ifu_fetch")(
      ICache.io.ResponseValid &&
        ICache.io.ResponseReady && !pipeFlush
    )
    io.perf.exu_done := perfDicArchBool("exu_done")(exu.io.out.fire)
    io.perf.lsu_load := perfDicArchBool("lsu_load")(
      LSU.io.Complete &&
        !LSU.io.DebugMemoryWrite
    )
    io.perf.lsu_store := perfDicArchBool("lsu_store")(
      LSU.io.Complete &&
        LSU.io.DebugMemoryWrite
    )
    // 指令类型与执行活跃
    io.perf.alu_op := perfDicArchBool("alu_op")(exu.io.PerfALUOp)
    io.perf.mem_op := perfDicArchBool("mem_op")(exu.io.PerfMemOp)
    io.perf.csr_op := perfDicArchBool("csr_op")(exu.io.PerfCSROp)
    io.perf.branch_op := perfDicArchBool("branch_op")(exu.io.PerfBranchOp)
    io.perf.jal_op := perfDicArchBool("jal_op")(exu.io.PerfJalOp)
    io.perf.jalr_op := perfDicArchBool("jalr_op")(exu.io.PerfJalrOp)
    // 乘除法
    io.perf.mdu_req := perfDicArchBool("mdu_req")(exu.io.PerfMDURequest)
    io.perf.mdu_done := perfDicArchBool("mdu_done")(exu.io.PerfMDUDone)
    io.perf.mdu_op := perfDicArchUInt("mdu_op", 3)(exu.io.PerfMDUOp)
    io.perf.mdu_active := perfDicArchBool("mdu_active")(exu.io.PerfMDUActive)
    io.perf.mdu_wait := perfDicArchBool("mdu_wait")(exu.io.PerfMDUWait)
    // 取指停顿
    io.perf.ifu_stall_pipeline := perfDicArchBool("ifu_stall_pipeline")(
      ICache.io.ResponseValid && !ICache.io.ResponseReady
    )
    // AXI等待只统计真实refill的AR/R占用。FetchReady为低还可能是
    // 响应槽或下游反压，不能全部归因于AXI。
    io.perf.ifu_stall_axi := perfDicArchBool("ifu_stall_axi")(
      ICache.io.perf_refill_req || ICache.io.perf_refill_resp
    )
    io.perf.ifu_stall_ar := perfDicArchBool("ifu_stall_ar")(
      ICache.io.perf_refill_req
    )
    io.perf.ifu_stall_r := perfDicArchBool("ifu_stall_r")(
      ICache.io.perf_refill_resp
    )
    // 重定向事件含异常/mret（ExceptionTaken）：它们与分支误预测一样让 IFU
    // 停发一拍；二者同拍时用或避免重复计数。
    io.perf.ifu_stall_redirect := perfDicArchBool("ifu_stall_redirect")(
      exu.io.Redirect || exu.io.ExceptionTaken
    )
    io.perf.ifu_stall_idle := perfDicArchBool("ifu_stall_idle")(ifu.io.StallIdle)
    // 指令缓存
    io.perf.icache_hit := perfDicArchBool("icache_hit")(ICache.io.perf_hit)
    io.perf.icache_miss := perfDicArchBool("icache_miss")(ICache.io.perf_miss)
    // 数据缓存
    io.perf.dcache_hit := perfDicArchBool("dcache_hit")(LSU.io.DCachePerfHit)
    io.perf.dcache_miss := perfDicArchBool("dcache_miss")(LSU.io.DCachePerfMiss)
    io.perf.dcache_refill_req :=
      perfDicArchBool("dcache_refill_req")(LSU.io.DCachePerfRefillRequest)
    io.perf.dcache_refill_resp :=
      perfDicArchBool("dcache_refill_resp")(LSU.io.DCachePerfRefillResponse)
    // 执行级停顿
    io.perf.exu_stall_lsu := perfDicArchBool("exu_stall_lsu")(
      exu.io.PerfExecutionActive && !exu.io.out.fire && !exu.io.PerfMDUWait
    )
    io.perf.exu_idle_noinput := perfDicArchBool("exu_idle_noinput")(
      exu.io.PerfIdleNoInput
    )
    io.perf.mem_waitslot := perfDicArchBool("mem_waitslot")(LSU.io.Hazard2Valid)
    // 访存级
    io.perf.lsu_active := perfDicArchBool("lsu_active")(LSU.io.Active)
    io.perf.lsu_load_active := perfDicArchBool("lsu_load_active")(
      LSU.io.Active &&
        !LSU.io.IsStore
    )
    io.perf.lsu_store_active := perfDicArchBool("lsu_store_active")(
      LSU.io.Active &&
        LSU.io.IsStore
    )
    io.perf.lsu_stall_read_ar := perfDicArchBool("lsu_stall_read_ar")(
      LSU.io.StallReadAR
    )
    io.perf.lsu_stall_read_r := perfDicArchBool("lsu_stall_read_r")(
      LSU.io.StallReadR
    )
    io.perf.lsu_stall_write_req :=
      perfDicArchBool("lsu_stall_write_req")(LSU.io.StallWriteRequest)
    io.perf.lsu_stall_write_b := perfDicArchBool("lsu_stall_write_b")(
      LSU.io.StallWriteB
    )
    // 译码级RAW停顿
    io.perf.idu_stall_raw := perfDicArchBool("idu_stall_raw")(
      idu.io.perf_stall_raw
    )
    io.perf.idu_stall_raw_loaduse :=
      perfDicArchBool("idu_stall_raw_loaduse")(idu.io.perf_stall_raw_loaduse)
    io.perf.idu_stall_raw_alu :=
      perfDicArchBool("idu_stall_raw_alu")(idu.io.perf_stall_raw_alu)
    // 异常
    io.perf.trap := perfDicArchBool("trap_count")(exu.io.PerfTrap)
    io.perf.trap_count := io.perf.trap
    // 执行活跃电平与事件分类码是多个单计数器的公共信号：消费方任一开启
    // 才生成，否则绑常量。
    io.perf.execution_active :=
      (if (config.EnablePerfExecutionActive) exu.io.PerfExecutionActive
       else false.B)
    io.perf.exu_event_kind :=
      (if (config.EnablePerfEventKind) exu.io.PerfEventKind else 0.U(3.W))

    val fetchPredictFire = ifu.io.FetchValid && ifu.io.FetchReady
    io.perf.btb_hit := perfDicArchBool("btb_hit")(fetchPredictFire && BTB.io.hit)
    io.perf.btb_miss := perfDicArchBool("btb_miss")(
      fetchPredictFire && !BTB.io.hit
    )
    io.perf.jal_btb_hit :=
      perfDicArchBool("jal_btb_hit")(fetchPredictFire && JalBTB.io.hit)
    io.perf.ras_hit := perfDicArchBool("ras_hit")(
      fetchPredictFire && JalBTB.io.hit &&
        JalBTB.io.HitKind.get === ysyx_26030103_BTBKind.Ret &&
        RAS.io.nonempty
    )
    io.perf.branch_mispredict :=
      perfDicArchBool("branch_mispredict")(exu.io.PerfBranchMispredict)
    io.perf.itlb_hit :=
      perfDicArchBool("itlb_hit")(
        MMUUnitOpt.map(_.io.perf_itlb_hit).getOrElse(false.B)
      )
    io.perf.itlb_miss :=
      perfDicArchBool("itlb_miss")(
        MMUUnitOpt.map(_.io.perf_itlb_miss).getOrElse(false.B)
      )
    io.perf.dtlb_hit :=
      perfDicArchBool("dtlb_hit")(
        MMUUnitOpt.map(_.io.perf_dtlb_hit).getOrElse(false.B)
      )
    io.perf.dtlb_miss :=
      perfDicArchBool("dtlb_miss")(
        MMUUnitOpt.map(_.io.perf_dtlb_miss).getOrElse(false.B)
      )
    io.perf.tlb_refill :=
      perfDicArchBool("tlb_refill")(
        MMUUnitOpt.map(_.io.perf_tlb_refill).getOrElse(false.B)
      )
    io.perf.fpu_req := perfDicArchBool("fpu_req")(exu.io.PerfFPURequest)
    io.perf.fpu_done := perfDicArchBool("fpu_done")(exu.io.PerfFPUDone)
    io.perf.axi_ar :=
      perfDicArchBool("axi_ar")(io.master.arvalid && io.master.arready)
    io.perf.axi_aw :=
      perfDicArchBool("axi_aw")(io.master.awvalid && io.master.awready)
    io.perf.axi_r :=
      perfDicArchBool("axi_r")(io.master.rvalid && io.master.rready)
    io.perf.axi_w :=
      perfDicArchBool("axi_w")(io.master.wvalid && io.master.wready)
    io.perf.axi_b :=
      perfDicArchBool("axi_b")(io.master.bvalid && io.master.bready)
    io.perf.load_op := perfDicArchBool("load_op")(exu.io.PerfLoadOp)
    io.perf.store_op := perfDicArchBool("store_op")(exu.io.PerfStoreOp)
    io.perf.amo_op := perfDicArchBool("amo_op")(exu.io.PerfAmoOp)
    io.perf.branch_taken := perfDicArchBool("branch_taken")(
      exu.io.PerfBranchTaken
    )
    io.perf.branch_not_taken :=
      perfDicArchBool("branch_not_taken")(exu.io.PerfBranchNotTaken)
    io.perf.tlb_flush := perfDicArchBool("tlb_flush")(exu.io.CSRFlushTLB)
    io.perf.fpu_active := perfDicArchBool("fpu_active")(exu.io.PerfFPUActive)
    io.perf.fence_op := perfDicArchBool("fence_op")(exu.io.PerfFenceOp)
    io.perf.rvc_inst := perfDicArchBool("rvc_inst")(exu.io.PerfRvcInst)
    io.perf.system_op := perfDicArchBool("system_op")(exu.io.PerfSystemOp)
    val iCacheRefillPrevious =
      RegNext(ICache.io.perf_refill_req, false.B)
    val dCacheRefillPrevious =
      RegNext(LSU.io.DCachePerfRefillRequest, false.B)
    io.perf.icache_refill := perfDicArchBool("icache_refill")(
      ICache.io.perf_refill_req && !iCacheRefillPrevious
    )
    io.perf.dcache_refill := perfDicArchBool("dcache_refill")(
      LSU.io.DCachePerfRefillRequest && !dCacheRefillPrevious
    )
    io.perf.timer_irq := perfDicArchBool("timer_irq")(exu.io.TimerInterrupt)
    io.perf.external_irq := perfDicArchBool("external_irq")(exu.io.Interrupt)
    io.perf.interrupt_taken := exu.io.PerfInterruptTaken &&
      (config.EnablePerfDicArchCounter("interrupt_taken") ||
        config.EnablePerfDicArchCounter("interrupt_latency")).B
    io.perf.wbuf_full := perfDicArchBool("wbuf_full")(LSU.io.StallWriteRequest)
    io.perf.wbuf_drain := perfDicArchBool("wbuf_drain")(LSU.io.StallWriteB)
    io.perf.csr_read := perfDicArchBool("csr_read")(exu.io.PerfCSRRead)
    io.perf.csr_write := perfDicArchBool("csr_write")(exu.io.PerfCSRWrite)
    io.perf.mul_active := perfDicArchBool("mul_active")(exu.io.PerfMulActive)
    io.perf.div_active := perfDicArchBool("div_active")(exu.io.PerfDivActive)
    io.perf.ptw_walk :=
      perfDicArchBool("ptw_walk")(
        MMUUnitOpt.map(_.io.perf_ptw_walk).getOrElse(false.B)
      )
    io.perf.ptw_walk_cycle :=
      perfDicArchBool("ptw_walk_cycle")(
        MMUUnitOpt.map(_.io.perf_ptw_walk_cycle).getOrElse(false.B)
      )
    io.perf.page_fault :=
      perfDicArchBool("page_fault")(
        MMUUnitOpt.map(_.io.perf_page_fault).getOrElse(false.B)
      )
    io.perf.ras_miss := perfDicArchBool("ras_miss")(
      fetchPredictFire && JalBTB.io.hit &&
        JalBTB.io.HitKind.get === ysyx_26030103_BTBKind.Ret &&
        !RAS.io.nonempty
    )
    io.perf.redirect_branch :=
      perfDicArchBool("redirect_branch")(exu.io.PerfRedirectBranch)
    io.perf.redirect_fence_i :=
      perfDicArchBool("redirect_fence_i")(exu.io.PerfRedirectFenceI)
    io.perf.redirect_trap :=
      perfDicArchBool("redirect_trap")(exu.io.PerfRedirectTrap)
    io.perf.redirect_mret :=
      perfDicArchBool("redirect_mret")(exu.io.PerfRedirectMret)
    io.perf.fpu_stall := perfDicArchBool("fpu_stall")(exu.io.PerfFPUStall)
    io.perf.illegal_inst :=
      perfDicArchBool("illegal_inst")(exu.io.PerfIllegalInst)
    io.perf.axi_stall := perfDicArchBool("axi_stall")(
      (io.master.arvalid && !io.master.arready) ||
        (io.master.awvalid && !io.master.awready) ||
        (io.master.rvalid && !io.master.rready) ||
        (io.master.wvalid && !io.master.wready) ||
        (io.master.bvalid && !io.master.bready)
    )
    io.perf.axi_error := perfDicArchBool("axi_error")(
      (io.master.rvalid && io.master.rready && io.master.rresp =/= 0.U) ||
        (io.master.bvalid && io.master.bready && io.master.bresp =/= 0.U)
    )
    io.perf.fpu_divsqrt :=
      perfDicArchBool("fpu_divsqrt")(exu.io.PerfFPUDivSqrt)
    io.perf.fpu_fma := perfDicArchBool("fpu_fma")(exu.io.PerfFPUFma)
    io.perf.fpu_exception :=
      perfDicArchBool("fpu_exception")(exu.io.PerfFPUException)
    io.perf.csr_illegal := perfDicArchBool("csr_illegal")(exu.io.PerfCSRIllegal)
    io.perf.plic_claim := perfDicArchBool("plic_claim")(PLIC.perf_claim)
    io.perf.plic_complete := perfDicArchBool("plic_complete")(PLIC.perf_complete)
    io.perf.flush_cycle := perfDicArchBool("flush_cycle")(pipeFlush)
    io.perf.wbuf_count :=
      perfDicArchUInt("wbuf_occupancy", 5)(LSU.io.PerfWBufCount)
    io.perf.uncached_access := perfDicArchBool("uncached_access")(
      LSU.io.Complete && !LSU.io.DCachePerfHit && !LSU.io.DCachePerfMiss
    )
    io.perf.irq_pending := exu.io.PerfIRQPending &&
      (config.EnablePerfDicArchCounter("interrupt_latency") ||
        config.EnablePerfDicArchCounter("interrupt_pending_cycle")).B
    io.perf.wfi_inst := perfDicArchBool("wfi_inst")(idu.io.PerfWfi)
    io.perf.bubble_cycle := perfDicArchBool("bubble_cycle")(!exu.io.in.valid)
    io.perf.issue_occupancy :=
      perfDicArchBool("issue_occupancy")(exu.io.in.valid)
    io.perf.retire_occupancy :=
      perfDicArchBool("retire_occupancy")(exu.io.out.valid)
    io.perf.icache_line_fill :=
      perfDicArchBool("icache_line_fill")(ICache.io.perf_line_fill)
    io.perf.dcache_line_fill :=
      perfDicArchBool("dcache_line_fill")(LSU.io.DCachePerfLineFill)
    io.perf.dcache_writeback :=
      perfDicArchBool("dcache_writeback")(LSU.io.DCachePerfWriteback)
    io.perf.div_iterations :=
      perfDicArchUInt("div_iterations", 16)(exu.io.PerfDivIterations)
    io.perf.div_early_out :=
      perfDicArchBool("div_early_out")(exu.io.PerfDivEarlyOut)
    io.perf.div_done := exu.io.PerfDivDone &&
      (config.EnablePerfDicArchCounter("div_iterations") ||
        config.EnablePerfDicArchCounter("div_early_out")).B
    io.perf.tlb_superpage_hit := perfDicArchBool("tlb_superpage_hit")(
      MMUUnitOpt.map(_.io.perf_superpage_hit).getOrElse(false.B)
    )
    io.perf.tlb_asid_hit := perfDicArchBool("tlb_asid_hit")(
      MMUUnitOpt.map(_.io.perf_asid_hit).getOrElse(false.B)
    )
  }
}
