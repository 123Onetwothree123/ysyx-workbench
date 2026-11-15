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
    val pmpFetchAddress = WireDefault(ifu.io.FetchAddr)
    pmpOpt.foreach { pmp =>
      pmp.io.cfg := exu.io.PmpCfg
      pmp.io.addr := exu.io.PmpAddr
      pmp.io.fetchAddr := pmpFetchAddress
      pmp.io.fetchPriv := exu.io.CSRPrivilege
      // 默认按无 MMU 路径接 LSU 的访存检查地址；MMU 分支稍后覆盖成
      // DMMU 给出的物理地址。这样两种配置共用同一个 PMP 例化。
      pmp.io.dataAddr := lsu.io.PmpAddr
      pmp.io.dataLastAddr := lsu.io.PmpLastAddr
      pmp.io.dataPriv := dataPrivilege
      pmp.io.dataRead := lsu.io.PmpRead
      pmp.io.dataWrite := lsu.io.PmpWrite
    }

    // icache.kill 在下面按 PIPT/VIPT 分支连接：VIPT 还需要在翻译故障时
    // 杀死未完成的 VA 请求。
    // EXU 重定向或异常必须杀死 IDU->EXU 边界中排队的年轻指令，同时让当前 EXU
    // 指令在本周期保持可见，以便它提交并产生重定向。
    // DMMU 页故障/PMP 故障通过 faultCommit 提交，不会拉高 lsu.FlushIDEX；
    // 它同样必须杀死 IDU->EXU 边界里的年轻指令，否则故障指令之后的那条
    // 会在 trap 入口前进入 EXU。faultCommit 是寄存器输出，不引入组合环。
    val dmmuFaultCommit = dmmuOpt.map(_.io.faultCommit).getOrElse(false.B)
    ysyx_26030103_StageConnect(
      idu.io.out,
      exu.io.in,
      lsu.io.FlushIDEX || pipeFlush || dmmuFaultCommit,
      // 当前 EXU 项产生重定向或陷阱时必须允许该项握手；更老的 MEM 故障则必须
      // 杀死它，即使 EXU 正在承受反压也一样。DMMU 故障属于更老的 MEM 故障。
      flushCurrent = lsu.io.FlushIDEX || dmmuFaultCommit
    )
    // 此边界保存的是比 LSU 当前指令更年轻的指令，只有更老的 MEM 故障可以丢弃
    // 它；不要在这里接入 pipeFlush。
    if (mmuEnabled) {
      // DMMU 对非访存指令是组合直通，因此这里仍然只有 LSU 前的一级流水寄存器。
      // VIPT 并行 load 会在翻译完成前通过这个边界进入 LSU；翻译故障时
      // memKill 必须清理该边界，避免故障请求在 kill 之后再次进入 LSU。
      // 但 memKill 直接接 flushCurrent 会经 LSU.in.valid → MemoryBusy →
      // EXU.FlushIF 形成组合环；kill 打一拍只用于清 StageConnect，ViptKill
      // 仍在当拍清理 LSU/DCache，不会让故障请求留下副作用。
      val dmmuMemKillForStage = RegNext(dmmuOpt.get.io.memKill, false.B)
      exu.io.out <> dmmuOpt.get.io.in
      ysyx_26030103_StageConnect(
        dmmuOpt.get.io.out,
        lsu.io.in,
        lsu.io.FlushEXMEM || dmmuMemKillForStage
      )
      lsu.io.ViptPAddr := dmmuOpt.get.io.memPaddr
      lsu.io.ViptPAddrValid := dmmuOpt.get.io.memPaddrValid
      lsu.io.ViptPAddrVaddr := dmmuOpt.get.io.memPaddrVaddr
      lsu.io.ViptKill := dmmuOpt.get.io.memKill
      dmmuOpt.get.io.memActive := lsu.io.Busy
      // LSU 本地陷阱（misaligned/PMP/总线错误）优先于尚未返回的翻译；
      // 撤销在途 DMMU 请求，防止同一个 store/load 二次提交 page fault。
      dmmuOpt.get.io.memTrap := lsu.io.MemTrapCommit
    } else {
      ysyx_26030103_StageConnect(exu.io.out, lsu.io.in, lsu.io.FlushEXMEM)
      // 无 MMU 时 VA==PA；VIPT 并行端口不使用，绑定安全常量。
      lsu.io.ViptPAddr := 0.U
      lsu.io.ViptPAddrValid := false.B
      lsu.io.ViptPAddrVaddr := 0.U
      lsu.io.ViptKill := false.B
    }
    ysyx_26030103_StageConnect(lsu.io.out, wbu.io.in)

    if (mmuEnabled) {
      val immu = immuOpt.get
      val dmmu = dmmuOpt.get
      val mmu = mmuUnitOpt.get
      val mmuArb = mmuArbOpt.get
      val ptwAXI = ptwToAXIOpt.get
      // satp 写入/sfence.vma 的 TLB 全量刷新会清空 MMU 内的在途翻译；
      // IFU/LSU 的翻译状态机必须同步回到 idle 并重发请求，否则会永远
      // 停在 xwait 等一个已被丢弃的响应。
      val tlbFlush = exu.io.CSRFlushTLB

      // 与 IFUMMU 内部 bypass 判定保持一致：satp.Mode=0 或 M 模式时 VA==PA。
      // RV64 的 MODE 位于 satp[63:60]（Sv39=8/Sv48=9），不能只检查 bit31。
      val immuBypass =
        (if (XLEN == 32) exu.io.CSRSatp(31) === 0.U
         else exu.io.CSRSatp(63, 60) === 0.U) || exu.io.CSRPrivilege === 3.U
      immu.io.privilege := exu.io.CSRPrivilege
      immu.io.satp := exu.io.CSRSatp
      // 取指没有 SUM 豁免；HAS_U=n 时直接绑 0。
      immu.io.sum := fetchSum
      immu.io.mxr := exu.io.CSRCurrentMstatus(19)
      immu.io.flush := mmuFlush || tlbFlush
      if (parallelFetchVIPT) {
        // VIPT 并行路径：只翻译 ICache 已受理的 VA；PA 带 VA 标签返回。
        // IFU 可以在 PA 到达前推进 PC（此时 cache 已用 VA 选好 index）。
        immu.io.fetchValid := icache.io.fetch_xlate_pending
        immu.io.fetchVaddr := icache.io.fetch_xlate_vaddr
        immu.io.cacheReady := icache.io.fetch_ready
        ifu.io.FetchReady := icache.io.fetch_ready && !ifuFaultValid
        // 翻译故障期间停止受理新取指，等 EXU 精确陷阱冲刷。
        icache.io.fetch_valid := ifu.io.FetchValid && !ifuFaultValid
        icache.io.fetch_vaddr := ifu.io.FetchAddr
        // Bare/M 模式的 bypass 也必须经过 IFUMMU 的 PMP 检查：translationValid
        // 已经与 !pmpFault 相与，不能再直接用 immuBypass 放行 PA，否则被 PMP
        // 拒绝的取指会绕过 IFUMMU 直接被 ICache 受理。
        icache.io.fetch_addr := immu.io.fetchPaddr
        icache.io.fetch_paddr_valid := immu.io.translationValid
        icache.io.fetch_paddr_vaddr := immu.io.fetchPaddrVaddr
        // 翻译故障与重定向都杀死未完成的 VA 请求，错误数据绝不提交。
        icache.io.kill := pipeFlush || immu.io.fault
      } else {
        immu.io.fetchValid := ifu.io.FetchValid
        immu.io.fetchVaddr := ifu.io.FetchAddr
        immu.io.cacheReady := icache.io.fetch_ready
        ifu.io.FetchReady := immu.io.fetchReady
        icache.io.fetch_addr := immu.io.fetchPaddr
        icache.io.fetch_vaddr := ifu.io.FetchAddr
        icache.io.fetch_valid := ifu.io.FetchValid && immu.io.translationValid
        // PIPT 实现忽略这两个端口；保持旧路径完全不变。
        icache.io.fetch_paddr_valid := true.B
        icache.io.fetch_paddr_vaddr := ifu.io.FetchAddr
        icache.io.kill := pipeFlush
      }
      // PMP 必须在物理地址上检查；Bare 模式没有翻译，仍用虚拟地址。
      pmpFetchAddress :=
        Mux(immuBypass, ifu.io.FetchAddr, immu.io.fetchPaddr)

      // mstatus.MPRV：M 模式数据访问按 MPP 的特权级翻译；取指仍用当前特权级。
      dmmu.io.privilege := dataPrivilege
      dmmu.io.satp := exu.io.CSRSatp
      dmmu.io.sum := dataSum
      dmmu.io.mxr := exu.io.CSRCurrentMstatus(19)
      dmmu.io.flush := pipeFlush || tlbFlush
      immu.io.pmpFault := pmpOpt.map(_.io.fetchFault).getOrElse(false.B)
      dmmu.io.pmpFault := pmpOpt.map(_.io.dataFault).getOrElse(false.B)
      pmpOpt.foreach { pmp =>
        pmp.io.fetchAddr := immu.io.fetchPaddr
        pmp.io.dataAddr := dmmu.io.pmpAddr
        pmp.io.dataLastAddr := dmmu.io.pmpLastAddr
        pmp.io.dataRead := dmmu.io.pmpRead
        pmp.io.dataWrite := dmmu.io.pmpWrite
      }
      // MMU 路径的 PMP 已在 DMMU 翻译完成后检查，LSU 不需要再检查。
      lsu.io.PmpFault := false.B

      mmuArb.io.iReq <> immu.io.req
      mmuArb.io.iResp <> immu.io.resp
      mmuArb.io.dReq <> dmmu.io.req
      mmuArb.io.dResp <> dmmu.io.resp
      mmuArb.io.mmuReq <> mmu.io.req
      mmu.io.resp <> mmuArb.io.mmuResp
      // satp 写入/sfence.vma 会清空 MMU 内所有在途翻译且不再回响应，
      // 仲裁器必须同步撤销 outstanding，否则后续取指/访存永远等不到翻译。
      mmuArb.io.flush := tlbFlush
      mmu.io.flush := tlbFlush

      ptwPMPFilterOpt match {
        case Some(filter) =>
          filter.io.in.req <> mmu.io.bus.req
          filter.io.in.resp <> mmu.io.bus.resp
          filter.io.out.req <> ptwAXI.io.req
          filter.io.out.resp <> ptwAXI.io.resp
          filter.io.cfg := exu.io.PmpCfg
          filter.io.addr := exu.io.PmpAddr
        case None =>
          mmu.io.bus.req <> ptwAXI.io.req
          mmu.io.bus.resp <> ptwAXI.io.resp
      }
      if (config.MMU.ptwPath == "reuse_dcache") {
        val lsuReadMux = axiReadLSUMuxOpt.get
        icache.io.axi <> arbiter.io.ifu
        lsu.io.DataBus <> lsuReadMux.io.a
        ptwAXI.io.axi <> lsuReadMux.io.b
        axiWriteMuxOpt match {
          case Some(writeMux) =>
            lsuReadMux.io.out.AW <> writeMux.io.a.AW
            lsuReadMux.io.out.W <> writeMux.io.a.W
            writeMux.io.a.B <> lsuReadMux.io.out.B
            lsuReadMux.io.out.AR <> arbiter.io.lsu.AR
            arbiter.io.lsu.R <> lsuReadMux.io.out.R
          case None => lsuReadMux.io.out <> arbiter.io.lsu
        }
      } else {
        val readMux = axiReadMuxOpt.get
        ptwAXI.io.axi <> readMux.io.b
        icache.io.axi <> readMux.io.a
        readMux.io.out <> arbiter.io.ifu
      }
    } else {
      icache.io.axi <> arbiter.io.ifu
      icache.io.fetch_addr := ifu.io.FetchAddr
      icache.io.fetch_vaddr := ifu.io.FetchAddr
      // 无 MMU 时 VA==PA，VIPT 等价于恒等映射，不需要等待翻译。
      icache.io.fetch_paddr_valid := true.B
      icache.io.fetch_paddr_vaddr := ifu.io.FetchAddr
      // PMP 故障请求不得进入 ICache；故障 PC 在响应级合成 cause=1。
      icache.io.fetch_valid := ifu.io.FetchValid && pmpFetchAllow
      icache.io.kill := pipeFlush
      ifu.io.FetchReady := icache.io.fetch_ready && pmpFetchAllow
      // 无 MMU 时顶层 PMP 直接检查 LSU 给出的 VA==PA 访存地址；
      // MMU 路径在 DMMU 翻译后检查，LSU 端口保持关闭。
      lsu.io.PmpFault := pmpOpt.map(_.io.dataFault).getOrElse(false.B)
    }

    if (mmuEnabled) {
      axiWriteMuxOpt match {
        case Some(writeMux) =>
          if (config.MMU.ptwPath != "reuse_dcache") {
            lsu.io.DataBus.AW <> writeMux.io.a.AW
            lsu.io.DataBus.W <> writeMux.io.a.W
            writeMux.io.a.B <> lsu.io.DataBus.B
            lsu.io.DataBus.AR <> arbiter.io.lsu.AR
            arbiter.io.lsu.R <> lsu.io.DataBus.R
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
          ptwToAXIOpt.get.io.axiWrite <> writeMux.io.b
          writeMux.io.out.AW <> arbiter.io.lsu.AW
          writeMux.io.out.W <> arbiter.io.lsu.W
          writeMux.io.out.B <> arbiter.io.lsu.B
          writeMux.io.out.AR.ARREADY := false.B
          writeMux.io.out.R.RID := 0.U
          writeMux.io.out.R.RDATA := 0.U
          writeMux.io.out.R.RRESP := 0.U
          writeMux.io.out.R.RLAST := false.B
          writeMux.io.out.R.RVALID := false.B
        case None =>
          if (config.MMU.ptwPath != "reuse_dcache") {
            arbiter.io.lsu <> lsu.io.DataBus
          }
          // 没有 A/D 写回时 PTW 写端口保持空闲。
          val ptwWrite = ptwToAXIOpt.get.io.axiWrite
          ptwWrite.AW.AWREADY := false.B
          ptwWrite.W.WREADY := false.B
          ptwWrite.B.BVALID := false.B
          ptwWrite.B.BID := 0.U
          ptwWrite.B.BRESP := 0.U
          ptwWrite.AR.ARREADY := false.B
          ptwWrite.R.RVALID := false.B
          ptwWrite.R.RID := 0.U
          ptwWrite.R.RDATA := 0.U
          ptwWrite.R.RRESP := 0.U
          ptwWrite.R.RLAST := false.B
      }
    } else {
      arbiter.io.lsu <> lsu.io.DataBus
    }

    idu.io.mmu_stall := dmmuOpt.map(_.io.busy).getOrElse(false.B)

    // 年长访存事务与年长普通指令必须分开。控制转移只需等
    // 可能故障的访存，不应被LSU中单拍直通的普通ALU指令拖慢；
    // IRQ/CSR/异常则仍使用全流水级Busy保证精确顺序。
    exu.io.MEMBusy := lsu.io.MemoryBusy
    // DMMU 翻译/故障状态也属于更老的 MEM 事务。只等 LSU 会让 IRQ 在
    // 一笔仍在 DMMU xreq/xwait 的 load/store 之前提交，丢掉更老的精确故障。
    // dmmu.busy 只由状态寄存器译码，不依赖 EXU 的 ready，不会形成组合环。
    exu.io.PipelineBusy :=
      lsu.io.Busy || dmmuOpt.map(_.io.busy).getOrElse(false.B)
    val dmmuFaultCause = dmmuOpt.map(_.io.faultCause).getOrElse(0.U(32.W))
    val dmmuFaultPC = dmmuOpt.map(_.io.faultPC).getOrElse(0.U(32.W))
    val dmmuFaultTval = dmmuOpt.map(_.io.faultTval).getOrElse(0.U(32.W))
    exu.io.MemTrapCommit := lsu.io.MemTrapCommit || dmmuFaultCommit
    exu.io.MemTrapCause := Mux(
      dmmuFaultCommit,
      dmmuFaultCause,
      lsu.io.MemTrapCause
    )
    exu.io.MemTrapPC := Mux(dmmuFaultCommit, dmmuFaultPC, lsu.io.MemTrapPC)
    exu.io.MemTrapTval := Mux(dmmuFaultCommit, dmmuFaultTval, lsu.io.MemTrapTval)

    // 当前 DCache 是写直达/不写分配，成功 store 会更新命中行，并且
    // Fence.I 在提交前已经等待更老的 store 排空。因此指令一致性只需
    // 失效 ICache/取指流水；清空 DCache 只会制造无意义的数据 miss。
    // DMA写是CPU缓存之外的副作用。写直达DCache没有脏数据，
    // 因此在匹配B响应出现时全局失效即可保证后续load不命中旧行。
    // 每笔DMA写在匹配BVALID首次出现时产生单拍失效；后续B反压不会持续
    // 阻止CPU load。错误B也同样失效，以覆盖从设备可能的部分写副作用。
    // 不在这里异步冲ICache：现有ICache flush与流水线重定向成对，
    // DMA修改可执行内存后由软件执行fence.i完成指令一致性。
    // DMA_COHERENCE=uncached 时 DMA 窗口本身不进入 Cache，不再需要这个
    // 全局失效；hardware_snoop 改用上面的地址级行失效，也不清空无关行。
    // 原子写完成后必须让 DCache 中的旧行失效；这里复用现有全量 flush
    // 接口（写直达缓存无脏数据），避免为 alias 情形新增行扫描硬件。
    lsu.io.DCacheFlush :=
      (if (
         config.DmaCoherence ==
           ysyx_26030103_DmaCoherence.SoftwareFlush
       )
         arbiter.io.DMAWriteInvalidate || lsu.io.AtomicCommit
       else lsu.io.AtomicCommit)
    // 原子事务总线独占：LSU 先等总线安静，再拉 AtomicBusy 阻止 DMA 插入。
    arbiter.io.atomicBusy := lsu.io.AtomicBusy
    lsu.io.BusQuiet := arbiter.io.busQuiet
    // 异常或 CSR 提交（上下文切换）后，旧 reservation 不再有效。
    lsu.io.ReservationClear := exu.io.ExceptionTaken || exu.io.CSRCommit
  }
}
