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

private[ysyx_26030103] trait MemoryConnect { this: ysyx_26030103 =>
  def connectMemory(): Unit = {
    // PMP 检查的是物理地址。MMU 关闭时虚拟地址等于物理地址；MMU 打开
    // 后由 IMMU 给出翻译结果，下面会覆盖这个默认连接。此前一直送虚拟
    // 地址，导致高虚拟地址（0xffffffff8...）永远不匹配 OpenSBI 的全内存
    // NAPOT，S 模式取指被误判为 instruction access fault。
    val PMPFetchAddress = WireDefault(ifu.io.FetchAddress)
    PMPOpt.foreach { PMP =>
      PMP.io.config := exu.io.PMPConfig
      PMP.io.address := exu.io.PMPAddress
      PMP.io.fetchAddress := PMPFetchAddress
      PMP.io.fetchPriv := exu.io.CSRPrivilege
      // 默认按无 MMU 路径接 LSU 的访存检查地址；MMU 分支稍后覆盖成
      // DMMU 给出的物理地址。这样两种配置共用同一个 PMP 例化。
      PMP.io.dataAddress := LSU.io.PMPAddress
      PMP.io.dataLastAddress := LSU.io.PMPLastAddress
      PMP.io.dataPriv := dataPrivilege
      PMP.io.dataRead := LSU.io.PMPRead
      PMP.io.dataWrite := LSU.io.PMPWrite
    }

    // ICache.kill 在下面按 PIPT/VIPT 分支连接：VIPT 还需要在翻译故障时
    // 杀死未完成的 VA 请求。
    // EXU 重定向或异常必须杀死 IDU->EXU 边界中排队的年轻指令，同时让当前 EXU
    // 指令在本周期保持可见，以便它提交并产生重定向。
    // DMMU 页故障/PMP 故障通过 faultCommit 提交，不会拉高 LSU.FlushIDEX；
    // 它同样必须杀死 IDU->EXU 边界里的年轻指令，否则故障指令之后的那条
    // 会在 trap 入口前进入 EXU。faultCommit 是寄存器输出，不引入组合环。
    val DMMUFaultCommit = DMMUOpt.map(_.io.faultCommit).getOrElse(false.B)
    ysyx_26030103_StageConnect(
      idu.io.out,
      exu.io.in,
      LSU.io.FlushIDEX || pipeFlush || DMMUFaultCommit,
      // 当前 EXU 项产生重定向或陷阱时必须允许该项握手；更老的 MEM 故障则必须
      // 杀死它，即使 EXU 正在承受反压也一样。DMMU 故障属于更老的 MEM 故障。
      flushCurrent = LSU.io.FlushIDEX || DMMUFaultCommit
    )
    // 此边界保存的是比 LSU 当前指令更年轻的指令，只有更老的 MEM 故障可以丢弃
    // 它；不要在这里接入 pipeFlush。
    if (MMUEnabled) {
      // DMMU 对非访存指令是组合直通，因此这里仍然只有 LSU 前的一级流水寄存器。
      // VIPT 并行 load 会在翻译完成前通过这个边界进入 LSU；翻译故障时
      // memKill 必须清理该边界，避免故障请求在 kill 之后再次进入 LSU。
      // 但 memKill 直接接 flushCurrent 会经 LSU.in.valid → MemoryBusy →
      // EXU.FlushIF 形成组合环；kill 打一拍只用于清 StageConnect，ViptKill
      // 仍在当拍清理 LSU/DCache，不会让故障请求留下副作用。
      val DMMUMemKillForStage = RegNext(DMMUOpt.get.io.memKill, false.B)
      exu.io.out <> DMMUOpt.get.io.in
      ysyx_26030103_StageConnect(
        DMMUOpt.get.io.out,
        LSU.io.in,
        LSU.io.FlushEXMEM || DMMUMemKillForStage
      )
      LSU.io.VIPTPAddress := DMMUOpt.get.io.memPhysicalAddress
      LSU.io.VIPTPAddressValid := DMMUOpt.get.io.memPhysicalAddressValid
      LSU.io.VIPTPAddressVirtualAddress := DMMUOpt.get.io.memPhysicalAddressVirtualAddress
      LSU.io.VIPTKill := DMMUOpt.get.io.memKill
      DMMUOpt.get.io.memActive := LSU.io.Busy
      // LSU 本地陷阱（misaligned/PMP/总线错误）优先于尚未返回的翻译；
      // 撤销在途 DMMU 请求，防止同一个 store/load 二次提交 page fault。
      DMMUOpt.get.io.memTrap := LSU.io.MemTrapCommit
    } else {
      ysyx_26030103_StageConnect(exu.io.out, LSU.io.in, LSU.io.FlushEXMEM)
      // 无 MMU 时 VA==PA；VIPT 并行端口不使用，绑定安全常量。
      LSU.io.VIPTPAddress := 0.U
      LSU.io.VIPTPAddressValid := false.B
      LSU.io.VIPTPAddressVirtualAddress := 0.U
      LSU.io.VIPTKill := false.B
    }
    ysyx_26030103_StageConnect(LSU.io.out, wbu.io.in)

    if (MMUEnabled) {
      val IMMU = IMMUOpt.get
      val DMMU = DMMUOpt.get
      val mmu = MMUUnitOpt.get
      val MMUArbiter = MMUArbiterOpt.get
      val PTWAXI = PTWToAXIOpt.get
      // satp 写入/sfence.vma 的 TLB 全量刷新会清空 MMU 内的在途翻译；
      // IFU/LSU 的翻译状态机必须同步回到 idle 并重发请求，否则会永远
      // 停在 xwait 等一个已被丢弃的响应。
      val TLBFlush = exu.io.CSRFlushTLB

      // 与 IFUMMU 内部 bypass 判定保持一致：satp.Mode=0 或 M 模式时 VA==PA。
      // RV64 的 MODE 位于 satp[63:60]（Sv39=8/Sv48=9），不能只检查 bit31。
      val IMMUBypass =
        (if (XLEN == 32) exu.io.CSRSatp(31) === 0.U
         else exu.io.CSRSatp(63, 60) === 0.U) || exu.io.CSRPrivilege === 3.U
      IMMU.io.privilege := exu.io.CSRPrivilege
      IMMU.io.satp := exu.io.CSRSatp
      // 取指没有 SUM 豁免；HAS_U=n 时直接绑 0。
      IMMU.io.sum := fetchSum
      IMMU.io.mxr := exu.io.CSRCurrentMstatus(19)
      IMMU.io.flush := MMUFlush || TLBFlush
      if (parallelFetchVIPT) {
        // VIPT 并行路径：只翻译 ICache 已受理的 VA；PA 带 VA 标签返回。
        // IFU 可以在 PA 到达前推进 PC（此时 cache 已用 VA 选好 index）。
        IMMU.io.fetchValid := ICache.io.FetchXlatePending
        IMMU.io.fetchVirtualAddress := ICache.io.FetchXlateVaddr
        IMMU.io.cacheReady := ICache.io.FetchReady
        ifu.io.FetchReady := ICache.io.FetchReady && !IFUFaultValid
        // 翻译故障期间停止受理新取指，等 EXU 精确陷阱冲刷。
        ICache.io.FetchValid := ifu.io.FetchValid && !IFUFaultValid
        ICache.io.FetchVaddr := ifu.io.FetchAddress
        // Bare/M 模式的 bypass 也必须经过 IFUMMU 的 PMP 检查：translationValid
        // 已经与 !PMPFault 相与，不能再直接用 IMMUBypass 放行 PA，否则被 PMP
        // 拒绝的取指会绕过 IFUMMU 直接被 ICache 受理。
        ICache.io.FetchAddress := IMMU.io.fetchPhysicalAddress
        ICache.io.FetchPaddrValid := IMMU.io.translationValid
        ICache.io.FetchPaddrVaddr := IMMU.io.fetchPhysicalAddressVirtualAddress
        // 翻译故障与重定向都杀死未完成的 VA 请求，错误数据绝不提交。
        ICache.io.kill := pipeFlush || IMMU.io.fault
      } else {
        IMMU.io.fetchValid := ifu.io.FetchValid
        IMMU.io.fetchVirtualAddress := ifu.io.FetchAddress
        IMMU.io.cacheReady := ICache.io.FetchReady
        ifu.io.FetchReady := IMMU.io.fetchReady
        ICache.io.FetchAddress := IMMU.io.fetchPhysicalAddress
        ICache.io.FetchVaddr := ifu.io.FetchAddress
        ICache.io.FetchValid := ifu.io.FetchValid && IMMU.io.translationValid
        // PIPT 实现忽略这两个端口；保持旧路径完全不变。
        ICache.io.FetchPaddrValid := true.B
        ICache.io.FetchPaddrVaddr := ifu.io.FetchAddress
        ICache.io.kill := pipeFlush
      }
      // PMP 必须在物理地址上检查；Bare 模式没有翻译，仍用虚拟地址。
      PMPFetchAddress :=
        Mux(IMMUBypass, ifu.io.FetchAddress, IMMU.io.fetchPhysicalAddress)

      // mstatus.MPRV：M 模式数据访问按 MPP 的特权级翻译；取指仍用当前特权级。
      DMMU.io.privilege := dataPrivilege
      DMMU.io.satp := exu.io.CSRSatp
      DMMU.io.sum := dataSum
      DMMU.io.mxr := exu.io.CSRCurrentMstatus(19)
      DMMU.io.flush := pipeFlush || TLBFlush
      IMMU.io.PMPFault := PMPOpt.map(_.io.fetchFault).getOrElse(false.B)
      DMMU.io.PMPFault := PMPOpt.map(_.io.dataFault).getOrElse(false.B)
      PMPOpt.foreach { PMP =>
        PMP.io.fetchAddress := IMMU.io.fetchPhysicalAddress
        PMP.io.dataAddress := DMMU.io.PMPAddress
        PMP.io.dataLastAddress := DMMU.io.PMPLastAddress
        PMP.io.dataRead := DMMU.io.PMPRead
        PMP.io.dataWrite := DMMU.io.PMPWrite
      }
      // MMU 路径的 PMP 已在 DMMU 翻译完成后检查，LSU 不需要再检查。
      LSU.io.PMPFault := false.B

      MMUArbiter.io.iRequest <> IMMU.io.request
      MMUArbiter.io.iResponse <> IMMU.io.response
      MMUArbiter.io.dRequest <> DMMU.io.request
      MMUArbiter.io.dResponse <> DMMU.io.response
      MMUArbiter.io.MMURequest <> mmu.io.request
      mmu.io.response <> MMUArbiter.io.MMUResponse
      // satp 写入/sfence.vma 会清空 MMU 内所有在途翻译且不再回响应，
      // 仲裁器必须同步撤销 outstanding，否则后续取指/访存永远等不到翻译。
      MMUArbiter.io.flush := TLBFlush
      mmu.io.flush := TLBFlush

      PTWPMPFilterOpt match {
        case Some(filter) =>
          filter.io.in.request <> mmu.io.bus.request
          filter.io.in.response <> mmu.io.bus.response
          filter.io.out.request <> PTWAXI.io.request
          filter.io.out.response <> PTWAXI.io.response
          filter.io.config := exu.io.PMPConfig
          filter.io.address := exu.io.PMPAddress
        case None =>
          mmu.io.bus.request <> PTWAXI.io.request
          mmu.io.bus.response <> PTWAXI.io.response
      }
      if (config.MMU.PTWPath == "reuse_dcache") {
        val LSUReadMux = AXIReadLSUMuxOpt.get
        ICache.io.AXI <> arbiter.io.ifu
        LSU.io.DataBus <> LSUReadMux.io.a
        PTWAXI.io.AXI <> LSUReadMux.io.b
        AXIWriteMuxOpt match {
          case Some(writeMux) =>
            LSUReadMux.io.out.AW <> writeMux.io.a.AW
            LSUReadMux.io.out.W <> writeMux.io.a.W
            writeMux.io.a.B <> LSUReadMux.io.out.B
            LSUReadMux.io.out.AR <> arbiter.io.LSU.AR
            arbiter.io.LSU.R <> LSUReadMux.io.out.R
          case None => LSUReadMux.io.out <> arbiter.io.LSU
        }
      } else {
        val readMux = AXIReadMuxOpt.get
        PTWAXI.io.AXI <> readMux.io.b
        ICache.io.AXI <> readMux.io.a
        readMux.io.out <> arbiter.io.ifu
      }
    } else {
      ICache.io.AXI <> arbiter.io.ifu
      ICache.io.FetchAddress := ifu.io.FetchAddress
      ICache.io.FetchVaddr := ifu.io.FetchAddress
      // 无 MMU 时 VA==PA，VIPT 等价于恒等映射，不需要等待翻译。
      ICache.io.FetchPaddrValid := true.B
      ICache.io.FetchPaddrVaddr := ifu.io.FetchAddress
      // PMP 故障请求不得进入 ICache；故障 PC 在响应级合成 cause=1。
      ICache.io.FetchValid := ifu.io.FetchValid && PMPFetchAllow
      ICache.io.kill := pipeFlush
      ifu.io.FetchReady := ICache.io.FetchReady && PMPFetchAllow
      // 无 MMU 时顶层 PMP 直接检查 LSU 给出的 VA==PA 访存地址；
      // MMU 路径在 DMMU 翻译后检查，LSU 端口保持关闭。
      LSU.io.PMPFault := PMPOpt.map(_.io.dataFault).getOrElse(false.B)
    }

    if (MMUEnabled) {
      AXIWriteMuxOpt match {
        case Some(writeMux) =>
          if (config.MMU.PTWPath != "reuse_dcache") {
            LSU.io.DataBus.AW <> writeMux.io.a.AW
            LSU.io.DataBus.W <> writeMux.io.a.W
            writeMux.io.a.B <> LSU.io.DataBus.B
            LSU.io.DataBus.AR <> arbiter.io.LSU.AR
            arbiter.io.LSU.R <> LSU.io.DataBus.R
          }
          // 写 mux 只承载写通道；未使用的 AR/R 输入显式绑定。
          writeMux.io.a.AR.ARVALID := false.B
          writeMux.io.a.AR.ARID := 0.U
          writeMux.io.a.AR.ARADDR := 0.U
          writeMux.io.a.AR.ARLEN := 0.U
          writeMux.io.a.AR.ARSIZE := 2.U
          writeMux.io.a.AR.ARBURST := 0.U
          writeMux.io.a.AR.ARPROT := 0.U
          writeMux.io.a.AR.ARCACHE := 0.U
          writeMux.io.a.AR.ARQOS := 0.U
          writeMux.io.a.R.RREADY := false.B
          PTWToAXIOpt.get.io.AXIWrite <> writeMux.io.b
          writeMux.io.out.AW <> arbiter.io.LSU.AW
          writeMux.io.out.W <> arbiter.io.LSU.W
          writeMux.io.out.B <> arbiter.io.LSU.B
          writeMux.io.out.AR.ARREADY := false.B
          writeMux.io.out.R.RID := 0.U
          writeMux.io.out.R.RDATA := 0.U
          writeMux.io.out.R.RRESP := 0.U
          writeMux.io.out.R.RLAST := false.B
          writeMux.io.out.R.RVALID := false.B
        case None =>
          if (config.MMU.PTWPath != "reuse_dcache") {
            arbiter.io.LSU <> LSU.io.DataBus
          }
          // 没有 A/D 写回时 PTW 写端口保持空闲。
          val PTWWrite = PTWToAXIOpt.get.io.AXIWrite
          PTWWrite.AW.AWREADY := false.B
          PTWWrite.W.WREADY := false.B
          PTWWrite.B.BVALID := false.B
          PTWWrite.B.BID := 0.U
          PTWWrite.B.BRESP := 0.U
          PTWWrite.AR.ARREADY := false.B
          PTWWrite.R.RVALID := false.B
          PTWWrite.R.RID := 0.U
          PTWWrite.R.RDATA := 0.U
          PTWWrite.R.RRESP := 0.U
          PTWWrite.R.RLAST := false.B
      }
    } else {
      arbiter.io.LSU <> LSU.io.DataBus
    }

    idu.io.MMUStall := DMMUOpt.map(_.io.busy).getOrElse(false.B)

    // 年长访存事务与年长普通指令必须分开。控制转移只需等
    // 可能故障的访存，不应被LSU中单拍直通的普通ALU指令拖慢；
    // IRQ/CSR/异常则仍使用全流水级Busy保证精确顺序。
    exu.io.MEMBusy := LSU.io.MemoryBusy
    // DMMU 翻译/故障状态也属于更老的 MEM 事务。只等 LSU 会让 IRQ 在
    // 一笔仍在 DMMU xreq/xwait 的 load/store 之前提交，丢掉更老的精确故障。
    // DMMU.busy 只由状态寄存器译码，不依赖 EXU 的 ready，不会形成组合环。
    exu.io.PipelineBusy :=
      LSU.io.Busy || DMMUOpt.map(_.io.busy).getOrElse(false.B)
    val DMMUFaultCause = DMMUOpt.map(_.io.faultCause).getOrElse(0.U(32.W))
    val DMMUFaultPC = DMMUOpt.map(_.io.FaultPC).getOrElse(0.U(32.W))
    val DMMUFaultTval = DMMUOpt.map(_.io.faultTval).getOrElse(0.U(32.W))
    exu.io.MemTrapCommit := LSU.io.MemTrapCommit || DMMUFaultCommit
    exu.io.MemTrapCause := Mux(
      DMMUFaultCommit,
      DMMUFaultCause,
      LSU.io.MemTrapCause
    )
    exu.io.MemTrapPC := Mux(DMMUFaultCommit, DMMUFaultPC, LSU.io.MemTrapPC)
    exu.io.MemTrapTval := Mux(DMMUFaultCommit, DMMUFaultTval, LSU.io.MemTrapTval)

    // DCache 是写直达/不写分配，Fence.I 在提交前已等更老的 store 排空，所以
    // 指令一致性只需失效 ICache/取指流水；清 DCache 只会制造无意义的数据 miss。
    // DMA 写是 CPU 缓存之外的副作用：写直达无脏数据，在匹配 B 响应首次出现时
    // 全局失效一次即可，后续 B 反压不阻止 load；错误 B 也同样失效，以覆盖从设备
    // 可能的部分写副作用。不在这里异步冲 ICache，DMA 改可执行内存后由软件
    // fence.i 完成指令一致性。
    // DMA_COHERENCE=uncached 时 DMA 窗口本身不进入 Cache，无需全局失效；
    // hardware_snoop 改用地址级行失效。原子写完成后也必须让 DCache 旧行失效，
    // 这里复用全量 flush（写直达无脏数据），避免为 alias 情形新增行扫描硬件。
    LSU.io.DCacheFlush :=
      (if (
         config.DMACoherence ==
           ysyx_26030103_DMACoherence.SoftwareFlush
       )
         arbiter.io.DMAWriteInvalidate || LSU.io.AtomicCommit
       else LSU.io.AtomicCommit)
    // 原子事务总线独占：LSU 先等总线安静，再拉 AtomicBusy 阻止 DMA 插入。
    arbiter.io.atomicBusy := LSU.io.AtomicBusy
    LSU.io.BusQuiet := arbiter.io.busQuiet
    // 异常或 CSR 提交（上下文切换）后，旧 reservation 不再有效。
    LSU.io.ReservationClear := exu.io.ExceptionTaken || exu.io.CSRCommit
  }
}
