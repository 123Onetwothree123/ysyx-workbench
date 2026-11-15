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

private[ysyx_26030103] trait FrontendConnect { this: ysyx_26030103 =>
  def connectFrontend(): Unit = {
    // ICache 响应(携带取指地址和错误标志)经冲刷流水寄存器直接进 IDU。
    // C 模式下响应先进入 IFU 的半字拼装状态机，由 IFU 输出完整指令；
    // 非 C 模式保持原有“ICache 响应直连 IDU + PC 快照队列”的数据通路。
    val ifuResponse = Wire(Decoupled(new ysyx_26030103_IFUMessage(XLEN)))
    val ifuFaultPC = RegInit(0.U(XLEN.W))
    // 取指故障写入 stval/mtval 的地址可能不同于指令起点：C 模式下 32 位
    // 指令跨字/跨页时，故障发生在第二个字（如 ...1000），而 sepc 仍是
    // 指令起点（...ffe）。这里单独锁存 faultVaddr。
    val ifuFaultTval = RegInit(0.U(XLEN.W))
    val fetchTranslationValid =
      immuOpt.map(_.io.translationValid).getOrElse(true.B)
    val fetchTranslationFault =
      immuOpt.map(_.io.fault).getOrElse(false.B)
    val pmpFetchFaultValid = RegInit(false.B)
    val pmpFetchFaultPC = RegInit(0.U(XLEN.W))
    // 与 MMU 路径同理：C 模式下故障取指可能是跨字指令的第二个字。
    val pmpFetchFaultTval = RegInit(0.U(XLEN.W))
    // VIPT 在 VA 阶段就受理取指，因此快照队列不能再用 translationValid
    // 门控；PIPT/无 MMU 保持原条件。
    val fetchAccept =
      ifu.io.FetchValid && pmpFetchAllow &&
        (if (parallelFetchVIPT) !ifuFaultValid else fetchTranslationValid) &&
        icache.io.fetch_ready
    // 两表项虚拟 PC 队列（仅非 C 路径）：允许响应与下一笔接受同拍发生。
    // C 路径由 IFU 内部串行状态机保存 arch PC，不依赖 ICache 响应级重新
    // 对齐 PC，因此不需要这个队列。
    val fetchTag0 = RegInit(0.U.asTypeOf(new ysyx_26030103_FetchTag(XLEN)))
    val fetchTag1 = RegInit(0.U.asTypeOf(new ysyx_26030103_FetchTag(XLEN)))
    val fetchTagCount = RegInit(0.U(2.W))
    // C 模式没有 fetchTagCount：只有 IFU 当前没有待交付指令、ICache 也没有
    // 更老响应时才注入 PMP 故障，保证故障指令严格排在已取指令之后。
    val pmpFetchInject =
      if (config.UseC)
        pmpFetchFaultValid && !ifu.io.Out.valid && !icache.io.resp_valid
      else pmpFetchFaultValid && fetchTagCount === 0.U && !icache.io.resp_valid
    val pushTag = WireInit(0.U.asTypeOf(new ysyx_26030103_FetchTag(XLEN)))
    // FetchPC 是架构 PC（指令起点）；非 C 时与 FetchAddr 相同。
    pushTag.pc := ifu.io.FetchPC
    pushTag.predTaken := ifu.io.PredHit
    pushTag.predTarget := ifu.io.PredTarget
    val popFetch = icache.io.resp_valid && icache.io.resp_ready
    val responseTag = WireInit(0.U.asTypeOf(new ysyx_26030103_FetchTag(XLEN)))
    if (!config.UseC) {
      responseTag := Mux(fetchTagCount === 0.U, pushTag, fetchTag0)
      val pushAndPop = fetchAccept && popFetch
      when(
        pipeFlush || (if (parallelFetchVIPT) fetchTranslationFault else false.B)
      ) {
        // VIPT 下翻译故障对应的请求已经被 ICache 受理并推入过快照；该请求不会
        // 产生正常响应（故障由 ifuResponse 注入），因此连同年轻请求一起清空。
        fetchTagCount := 0.U
      }.elsewhen(pushAndPop) {
        when(fetchTagCount === 0.U) {
          fetchTagCount := 0.U
        }.elsewhen(fetchTagCount === 1.U) {
          fetchTag0 := pushTag
        }.otherwise {
          fetchTag0 := fetchTag1
          fetchTag1 := pushTag
        }
      }.elsewhen(fetchAccept) {
        when(fetchTagCount === 0.U) {
          fetchTag0 := pushTag
          fetchTagCount := 1.U
        }.elsewhen(fetchTagCount === 1.U) {
          fetchTag1 := pushTag
          fetchTagCount := 2.U
        }
      }.elsewhen(popFetch) {
        when(fetchTagCount === 2.U) {
          fetchTag0 := fetchTag1
          fetchTagCount := 1.U
        }.otherwise {
          fetchTagCount := 0.U
        }
      }
    }

    // IFU 的响应端口在两种模式下都连接；非 C 模式内部忽略。
    ifu.io.RespData := icache.io.resp_data
    ifu.io.RespValid := icache.io.resp_valid
    ifu.io.RespExceptionValid := icache.io.resp_fault
    ifu.io.RespExceptionCause := 1.U(4.W)
    ifu.io.RespAccessFaultResp := icache.io.access_fault_resp

    if (mmuEnabled) {
      // 取指虚拟 PC 与 ICache 的物理取指地址分离；ICache 只有一笔在途，
      // 因此一个虚拟 PC 快照足够把响应恢复成架构 PC。
      // 重定向/更老指令异常当拍必须丢弃取指故障脉冲。若等到下一拍的
      // mmuFlush 再清，故障会被注入到新的重定向目标上，产生伪精确异常。
      when(pipeFlush) {
        ifuFaultValid := false.B
      }.elsewhen(immuOpt.get.io.fault) {
        ifuFaultValid := true.B
        // C 模式下 IFU 的 pcReg 停在故障指令起点；第二个半字跨页/跨行
        // 故障时 mepc 仍指向这条指令的起点。顶层重定向会更新实时
        // FetchPC，因此这里必须锁存故障取指自己的 PC。
        ifuFaultPC := (if (config.UseC) ifu.io.FetchPC
                       else immuOpt.get.io.faultVaddr)
        ifuFaultTval := immuOpt.get.io.faultVaddr
      }.elsewhen(ifuResponse.fire) {
        ifuFaultValid := false.B
      }
    } else {
      // 无 MMU 的取指 PMP 故障注入：PMP 在 IFU 当前物理取指地址上组合
      // 判定。故障请求被挡在 ICache 之外，由这里合成一条 NOP 响应，
      // 携带 faulting PC 和 instruction access fault(cause=1)。
      when(pipeFlush) {
        // 分支重定向/更老指令异常会丢弃故障路径，重新从目标取指。
        pmpFetchFaultValid := false.B
        pmpFetchBlocked := false.B
      }.elsewhen(pmpFetchFault && ifu.io.FetchValid && !pmpFetchBlocked) {
        pmpFetchFaultValid := true.B
        pmpFetchFaultPC := (if (config.UseC) ifu.io.FetchPC
                            else ifu.io.FetchAddr)
        // FetchAddr 是实际被 PMP 拒绝的取指地址；C 模式下跨字第二个字
        // 故障时它比指令起点 FetchPC 更精确。
        pmpFetchFaultTval := ifu.io.FetchAddr
        pmpFetchBlocked := true.B
      }.elsewhen(pmpFetchInject && ifuResponse.fire) {
        // 只注入一次；blocked 保持到真正提交 trap 的冲刷，避免在同一 PC
        // 反复制造年轻故障。
        pmpFetchFaultValid := false.B
      }
    }

    // 合成故障响应的公共格式。
    // 统一使用锁存的故障 PC：C 模式也不能使用实时 FetchPC，否则重定向后
    // 的取指地址会被错误地当成故障指令地址。
    val fetchFaultPC =
      if (mmuEnabled) ifuFaultPC
      else pmpFetchFaultPC
    // fault PC 用于 mepc/sepc，fault tval 用于 stval/mtval。两者在 C 模式
    // 跨字取指故障时可以不同。异常指令不使用预测目标，这里复用 pred_target
    // 把 fault tval 透传到 EXU（IDU 原样透传该字段）。
    val fetchFaultTval =
      if (mmuEnabled) ifuFaultTval
      else pmpFetchFaultTval
    val fetchFaultCause =
      if (mmuEnabled) immuOpt.map(_.io.faultCause).getOrElse(12.U(4.W))
      else 1.U(4.W)
    val fetchFaultInject =
      (if (mmuEnabled) ifuFaultValid else false.B) || pmpFetchInject
    val faultBits = WireInit(0.U.asTypeOf(new ysyx_26030103_IFUMessage(XLEN)))
    faultBits.Instruction := "h00000013".U(32.W)
    faultBits.IsCompressed := false.B
    faultBits.pc := fetchFaultPC
    faultBits.ExceptionValid := true.B
    faultBits.ExceptionCause := fetchFaultCause
    faultBits.AccessFaultResp := 0.U(2.W)
    faultBits.pred_taken := false.B
    faultBits.pred_target := fetchFaultTval

    if (config.UseC) {
      // IFU 已把 16/32 位编码拼装完整；pc/异常/预测标签都由 IFU 给出。
      ifuResponse.valid := fetchFaultInject || ifu.io.Out.valid
      ifuResponse.bits := Mux(fetchFaultInject, faultBits, ifu.io.Out.bits)
      ifu.io.Out.ready := ifuResponse.ready && !fetchFaultInject
      icache.io.resp_ready := ifu.io.RespReady && !fetchFaultInject
    } else {
      val normalBits = WireInit(0.U.asTypeOf(new ysyx_26030103_IFUMessage(XLEN)))
      normalBits.Instruction := icache.io.resp_data
      normalBits.IsCompressed := false.B
      normalBits.pc := responseTag.pc
      normalBits.ExceptionValid := icache.io.resp_fault
      normalBits.ExceptionCause := 1.U(4.W)
      normalBits.AccessFaultResp := icache.io.access_fault_resp
      // 取指故障时 tval 必须是发生故障的取指地址，而不是 BTB 预测信息。
      normalBits.pred_taken := responseTag.predTaken && !icache.io.resp_fault
      normalBits.pred_target :=
        Mux(icache.io.resp_fault, responseTag.pc, responseTag.predTarget)
      ifuResponse.valid := fetchFaultInject || icache.io.resp_valid
      ifuResponse.bits := Mux(fetchFaultInject, faultBits, normalBits)
      ifu.io.Out.ready := false.B
      icache.io.resp_ready := ifuResponse.ready && !fetchFaultInject
      // 响应级不再重新查表, 预测标签在取指接受时快照保存。不变量：ICache
      // 的每个响应都必须对应一个已受理取指的快照；若 redirect/flush 后仍
      // 残留旧响应，fetchTagCount 会先被清零，这里立即报错。
      when(icache.io.resp_valid && !ifuFaultValid && !pmpFetchInject) {
        assert(
          fetchTagCount =/= 0.U,
          "ICache response arrived without a fetch PC tag snapshot"
        )
      }
    }
    ysyx_26030103_StageConnect(ifuResponse, idu.io.in, pipeFlush)

    // BTB 查询使用架构 PC（C 模式下可能落在字内高半字），并按 IALIGN
    // 决定去掉 1 位还是 2 位对齐位。预测只在 IFU 的指令起点取指上生效。
    val fetchPredictPC =
      if (config.UseC) ifu.io.FetchPC else ifu.io.FetchAddr
    val btbPCShift = if (config.UseC) 1 else 2
    btb.io.lookup_pc := fetchPredictPC(config.AddressWidth - 1, btbPCShift)
    jalBTB.io.lookup_pc := fetchPredictPC(config.AddressWidth - 1, btbPCShift)
    // Ret只有在RAS非空时才有资格取得优先级；Ret表项的target保存静态
    // JALR immediate，由选择器与动态栈顶相加。分支仍采用BTFN方向预测。
    predictorSelect.io.fetchPC := fetchPredictPC
    predictorSelect.io.branchHit := btb.io.hit
    predictorSelect.io.branchTarget := btb.io.target
    predictorSelect.io.jalHit := jalBTB.io.hit
    predictorSelect.io.jalTarget := jalBTB.io.target
    predictorSelect.io.jalKind := jalBTB.io.hit_kind.get
    predictorSelect.io.rasNonempty := ras.io.nonempty
    predictorSelect.io.rasTop := ras.io.top
    ifu.io.PredHit := predictorSelect.io.predHit
    ifu.io.PredTarget := predictorSelect.io.predTarget
    // BTB更新: EXU提交时按指令类型路由, 分支写分支表, jal/ret写jal表(带kind)
    // BTB/RAS 表项按虚拟 PC/返回地址索引，不携带地址空间或特权级标签。
    // satp 写入/sfence.vma(CSRFlushTLB) 或 trap/mret/sret(ExceptionTaken)
    // 后，同一虚拟 PC 可能已属于另一上下文；若继续命中旧表项，非控制指令
    // 也会带上 pred_taken，随后 EXU 的伪重定向会冲刷当前访存/浮点请求。
    // 因此上下文切换时保守清空全部预测状态，绝不跨上下文复用表项。
    val contextFlush = exu.io.CSRFlushTLB || exu.io.ExceptionTaken
    btb.io.update_valid := exu.io.BTBUpdateValid
    btb.io.update_pc := exu.io.BTBUpdatePC(config.AddressWidth - 1, btbPCShift)
    btb.io.update_target := exu.io.BTBUpdateTarget
    btb.io.flush := exu.io.FenceIFlush || contextFlush
    jalBTB.io.update_valid := exu.io.JalBTBUpdateValid
    jalBTB.io.update_pc := exu.io.JalBTBUpdatePC(
      config.AddressWidth - 1,
      btbPCShift
    )
    jalBTB.io.update_target := exu.io.JalBTBUpdateTarget
    jalBTB.io.update_kind.get := exu.io.JalBTBUpdateKind
    jalBTB.io.flush := exu.io.FenceIFlush || contextFlush
    // RAS更新: call压栈, ret弹栈
    ras.io.push_valid := exu.io.RASPushValid
    ras.io.push_addr := exu.io.RASPushAddr
    ras.io.pop_valid := exu.io.RASPopValid
    ras.io.flush := exu.io.FenceIFlush || contextFlush

    // 重定向当拍已在故障锁存处清 ifuFaultValid；这里保留下一拍兜底，
    // 用于清掉 flush 前最后时刻返回的旧取指故障。
    if (mmuEnabled) { when(mmuFlush) { ifuFaultValid := false.B } }

    ifu.io.Redirect := exu.io.Redirect
    ifu.io.RedirectTarget := exu.io.RedirectTarget
    ifu.io.ExceptionTaken := exu.io.ExceptionTaken
    ifu.io.ExceptionTarget := exu.io.ExceptionTarget
    icache.io.flush := exu.io.FenceIFlush // 仅 FenceI 冲 ICache，分支不冲
    // 可选自修改代码 snoop：CPU store 的 B 响应完成后失效对应 ICache 行。
    // FENCE_I 模式下 snoop_valid 恒为 0，ICache 内不生成地址比较/失效逻辑。
    // 该模式仍保留 fence.i：流水线里已取入的旧指令只能由 fence.i 冲刷。
    icache.io.snoop_valid :=
      (if (
         config.ICacheCoherence ==
           ysyx_26030103_ICacheCoherence.HardwareSnoop
       ) lsu.io.StoreCommit
       else false.B)
    icache.io.snoop_addr := lsu.io.StoreCommitAddr
    icache.io.snoop_addr_hi := lsu.io.StoreCommitLastAddr
    // DMA 地址级 snoop：仲裁器在 DMA 写 B 到达时给出本次 burst 覆盖的
    // 字节范围，ICache/DCache 各自展开为重叠行并失效。两个 ready 都为高
    // 后仲裁器才把 B 交给 CPU slave，因此 DMA 完成事件不会早于失效完成。
    icache.io.dma_snoop_valid := arbiter.io.dmaSnoopValid
    icache.io.dma_snoop_start := arbiter.io.dmaSnoopStart
    icache.io.dma_snoop_end := arbiter.io.dmaSnoopEnd
    lsu.io.DCacheDmaSnoopValid := arbiter.io.dmaSnoopValid
    lsu.io.DCacheDmaSnoopStart := arbiter.io.dmaSnoopStart
    lsu.io.DCacheDmaSnoopEnd := arbiter.io.dmaSnoopEnd
    arbiter.io.dmaSnoopDone :=
      icache.io.dma_snoop_ready && lsu.io.DCacheDmaSnoopReady
  }
}
