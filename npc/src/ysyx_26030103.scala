package ysyx_26030103
import chisel3._
import chisel3.util._

//他妈的，我们伟大的scala插件和编译器设计专家应该要以死谢罪，是哪个天才想到的，如果直接写ysyx_26030103，因为我这个顶层模块类和包同名了
//能被解读成ysyx_26030103的ysyx_26030103的AXI模块，还得手动指定从最顶层的根目录去找
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.infra._
import _root_.ysyx_26030103.ifu._
import _root_.ysyx_26030103.idu._
import _root_.ysyx_26030103.exu._
import _root_.ysyx_26030103.mem._
import _root_.ysyx_26030103.wbu._

class ysyx_26030103(val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig())
    extends Module {
  val io = IO(new ysyx_26030103_IO)
  val ifu = Module(new ysyx_26030103_IFU(config.ResetAddr))
  val idu = Module(new ysyx_26030103_IDU(config))
  val exu = Module(new ysyx_26030103_EXU(config))
  val wbu = Module(new ysyx_26030103_WBU)
  val lsu = Module(
    new ysyx_26030103_LSU(
      WBufDepth = config.WBufDepth,
      DCacheEnable = config.DCacheEnable,
      DCacheBlockSizeLog2 = config.BlockSizeLog2,
      DCacheIndexBits = config.IndexBits,
      DCacheableBase = config.CacheableBase,
      DCacheableMask = config.CacheableMask
    )
  )
  val gpr = Module(new ysyx_26030103_GPR)
  val icache = Module(
    new ysyx_26030103_ICache(
      Enable = config.ICacheEnable,
      BlockSizeLog2 = config.BlockSizeLog2,
      IndexBits = config.IndexBits,
      PMARegions = config.PMARegions
    )
  )
  val arbiter = Module(new ysyx_26030103_AXI5Arbiter)
  val xbar = Module(
    new ysyx_26030103_AXI5Xbar(config.AddressWidth, config.PMARegions)
  )
  val clint = Module(new ysyx_26030103_AXI5CLINTSlave)
  val dmaErrorSlave = Module(new ysyx_26030103_AXI5DMAErrorSlave)
  val pipe_flush = exu.io.FlushIF
  // 分支目标缓冲(BTB): IFU取指级查询决定下一PC,取指被icache接受时快照预测标签随请求保存,
  // EXU提交时更新分支的真实target; 另设独立jal BTB(方案B: 与分支表零干扰, 表项带kind区分Jal/Call/Ret)
  // + 返回地址栈RAS(ret的预测目标=栈顶, EXU提交call/ret时压/弹, 非投机无需修复)
  val btb = Module(new ysyx_26030103_BTB(config.BTBBits, config.BTBWays))
  val jal_btb =
    Module(new ysyx_26030103_BTB(config.JalBTBBits, config.JalBTBWays, 32, 2))
  val ras = Module(new ysyx_26030103_RAS(config.RASBits))
  val predictorSelect = Module(new ysyx_26030103_PredictorSelect)
  // icache响应(携带取指地址和错误标志)经冲刷流水寄存器直接进IDU
  val ifuResp = Wire(Decoupled(new ysyx_26030103_IFUMessage))
  ifuResp.valid := icache.io.resp_valid
  ifuResp.bits.Instruction := icache.io.resp_data
  ifuResp.bits.pc := icache.io.resp_addr
  ifuResp.bits.ExceptionValid := icache.io.resp_fault
  ifuResp.bits.ExceptionCause := 1.U(4.W)
  ifuResp.bits.AccessFaultResp := icache.io.access_fault_resp
  // 预测标签快照: 取指请求被icache接受时,把取指时刻的预测随请求保存,响应时贴给指令.
  // 根治"取指/响应两阶段查找之间BTB/RAS状态变化,导致标签与实际取指路径不一致"的致命漏洞
  // (反例: ret取指时RAS顶是A(错),响应时弹栈后变B(对),标签=B与实际执行一致=>不冲刷,
  //  错路指令漏网提交,ra被污染. icache最多1个outstanding,单表项快照即够;
  //  背靠背=响应与接受同拍,响应读的是旧值,时序正确)
  val accept_fetch = ifu.io.FetchValid && icache.io.fetch_ready
  val pred_tag_taken = RegEnable(ifu.io.PredHit, false.B, accept_fetch)
  val pred_tag_target = RegEnable(ifu.io.PredTarget, 0.U(32.W), accept_fetch)
  ifuResp.bits.pred_taken := pred_tag_taken
  ifuResp.bits.pred_target := pred_tag_target
  // 响应级不再重新查表, 预测标签在取指接受时快照保存
  icache.io.resp_ready := ifuResp.ready
  ysyx_26030103_StageConnect(ifuResp, idu.io.in, pipe_flush)
  icache.io.kill := pipe_flush
  ysyx_26030103_StageConnect(idu.io.out, exu.io.in, lsu.io.FlushIDEX)
  ysyx_26030103_StageConnect(exu.io.out, lsu.io.in, lsu.io.FlushEXMEM)
  ysyx_26030103_StageConnect(lsu.io.out, wbu.io.in)
  icache.io.axi <> arbiter.io.ifu
  icache.io.fetch_addr := ifu.io.FetchAddr
  icache.io.fetch_valid := ifu.io.FetchValid
  ifu.io.FetchReady := icache.io.fetch_ready
  // BTB查询1: 用当前取指地址同时查两张表,合并结果回送IFU决定下一PC
  // 传入PC[31:2]; 低位恒为0, 无需参与索引和tag匹配
  btb.io.lookup_pc := ifu.io.FetchAddr(31, 2)
  jal_btb.io.lookup_pc := ifu.io.FetchAddr(31, 2)
  // Ret只有在RAS非空时才有资格取得优先级；Ret表项的target保存静态
  // JALR immediate，由选择器与动态栈顶相加。分支仍采用BTFN方向预测。
  predictorSelect.io.fetchPC := ifu.io.FetchAddr
  predictorSelect.io.branchHit := btb.io.hit
  predictorSelect.io.branchTarget := btb.io.target
  predictorSelect.io.jalHit := jal_btb.io.hit
  predictorSelect.io.jalTarget := jal_btb.io.target
  predictorSelect.io.jalKind := jal_btb.io.hit_kind.get
  predictorSelect.io.rasNonempty := ras.io.nonempty
  predictorSelect.io.rasTop := ras.io.top
  ifu.io.PredHit := predictorSelect.io.predHit
  ifu.io.PredTarget := predictorSelect.io.predTarget
  // BTB更新: EXU提交时按指令类型路由, 分支写分支表, jal/ret写jal表(带kind)
  btb.io.update_valid := exu.io.BTBUpdateValid
  btb.io.update_pc := exu.io.BTBUpdatePC(31, 2)
  btb.io.update_target := exu.io.BTBUpdateTarget
  btb.io.flush := exu.io.FenceIFlush
  jal_btb.io.update_valid := exu.io.JalBTBUpdateValid
  jal_btb.io.update_pc := exu.io.JalBTBUpdatePC(31, 2)
  jal_btb.io.update_target := exu.io.JalBTBUpdateTarget
  jal_btb.io.update_kind.get := exu.io.JalBTBUpdateKind
  jal_btb.io.flush := exu.io.FenceIFlush
  // RAS更新: call压栈, ret弹栈
  ras.io.push_valid := exu.io.RASPushValid
  ras.io.push_addr := exu.io.RASPushAddr
  ras.io.pop_valid := exu.io.RASPopValid
  ras.io.flush := exu.io.FenceIFlush
  arbiter.io.lsu <> lsu.io.DataBus
  arbiter.io.memory.AW <> xbar.io.in.AW
  arbiter.io.memory.W <> xbar.io.in.W
  arbiter.io.memory.B <> xbar.io.in.B
  arbiter.io.memory.AR <> xbar.io.in.AR
  arbiter.io.memory.R <> xbar.io.in.R
  val soc = xbar.io.SoCBus
  io.master_awvalid := soc.AW.AWVALID
  io.master_awaddr := soc.AW.AWADDR
  io.master_awid := soc.AW.AWID
  io.master_awlen := soc.AW.AWLEN
  io.master_awsize := soc.AW.AWSIZE
  io.master_awburst := soc.AW.AWBURST
  io.master_awlock := 0.U
  io.master_awcache := 0.U
  io.master_awprot := 0.U
  io.master_awqos := 0.U
  soc.AW.AWREADY := io.master_awready

  io.master_wvalid := soc.W.WVALID
  io.master_wdata := soc.W.WDATA
  io.master_wstrb := soc.W.WSTRB
  io.master_wlast := soc.W.WLAST
  soc.W.WREADY := io.master_wready

  io.master_bready := soc.B.BREADY
  soc.B.BID := io.master_bid
  soc.B.BVALID := io.master_bvalid
  soc.B.BRESP := io.master_bresp

  io.master_arvalid := soc.AR.ARVALID
  io.master_araddr := soc.AR.ARADDR
  io.master_arid := soc.AR.ARID
  io.master_arlen := soc.AR.ARLEN
  io.master_arsize := soc.AR.ARSIZE
  io.master_arburst := soc.AR.ARBURST
  io.master_arlock := 0.U
  io.master_arcache := 0.U
  io.master_arprot := 0.U
  io.master_arqos := 0.U
  soc.AR.ARREADY := io.master_arready

  io.master_rready := soc.R.RREADY
  soc.R.RID := io.master_rid
  soc.R.RVALID := io.master_rvalid
  soc.R.RRESP := io.master_rresp
  soc.R.RDATA := io.master_rdata
  soc.R.RLAST := io.master_rlast

  // The inbound port is connected to ChipLink's DMA-facing master in the SoC.
  // There is currently no local memory behind this CPU port.  Forwarding its
  // 0xc0000000--0xffffffff requests through the CPU master would route them
  // straight back to ChipLink, so terminate them with protocol-complete DECERR
  // responses instead of hanging forever or falsely acknowledging a write.
  // Since every write fails, no RAM/cache state changes and no DCache snoop is
  // required.  A future real DMA path must add an explicit memory target and
  // invalidate the DCache before returning a successful B response.
  dmaErrorSlave.io.AW.AWVALID := io.slave_awvalid
  dmaErrorSlave.io.AW.AWADDR := io.slave_awaddr
  dmaErrorSlave.io.AW.AWID := io.slave_awid
  dmaErrorSlave.io.AW.AWLEN := io.slave_awlen
  dmaErrorSlave.io.AW.AWSIZE := io.slave_awsize
  dmaErrorSlave.io.AW.AWBURST := io.slave_awburst
  dmaErrorSlave.io.AW.AWPROT := io.slave_awprot
  io.slave_awready := dmaErrorSlave.io.AW.AWREADY

  dmaErrorSlave.io.W.WVALID := io.slave_wvalid
  dmaErrorSlave.io.W.WDATA := io.slave_wdata
  dmaErrorSlave.io.W.WSTRB := io.slave_wstrb
  dmaErrorSlave.io.W.WLAST := io.slave_wlast
  io.slave_wready := dmaErrorSlave.io.W.WREADY

  dmaErrorSlave.io.B.BREADY := io.slave_bready
  io.slave_bvalid := dmaErrorSlave.io.B.BVALID
  io.slave_bresp := dmaErrorSlave.io.B.BRESP
  io.slave_bid := dmaErrorSlave.io.B.BID

  dmaErrorSlave.io.AR.ARVALID := io.slave_arvalid
  dmaErrorSlave.io.AR.ARADDR := io.slave_araddr
  dmaErrorSlave.io.AR.ARID := io.slave_arid
  dmaErrorSlave.io.AR.ARLEN := io.slave_arlen
  dmaErrorSlave.io.AR.ARSIZE := io.slave_arsize
  dmaErrorSlave.io.AR.ARBURST := io.slave_arburst
  dmaErrorSlave.io.AR.ARPROT := io.slave_arprot
  io.slave_arready := dmaErrorSlave.io.AR.ARREADY

  dmaErrorSlave.io.R.RREADY := io.slave_rready
  io.slave_rvalid := dmaErrorSlave.io.R.RVALID
  io.slave_rresp := dmaErrorSlave.io.R.RRESP
  io.slave_rdata := dmaErrorSlave.io.R.RDATA
  io.slave_rlast := dmaErrorSlave.io.R.RLAST
  io.slave_rid := dmaErrorSlave.io.R.RID
  xbar.io.CLINT.AW <> clint.io.AW
  xbar.io.CLINT.W <> clint.io.W
  xbar.io.CLINT.B <> clint.io.B
  xbar.io.CLINT.AR <> clint.io.AR
  xbar.io.CLINT.R <> clint.io.R
  idu.io.ex_valid := exu.io.HazardValid
  idu.io.ex_rd := exu.io.HazardRd
  idu.io.ex_regWrite := exu.io.HazardRegWrite
  idu.io.ex_fwd_ready := exu.io.FwdReady
  idu.io.ex_fwd_data := exu.io.FwdData
  idu.io.wb_fwd_data := wbu.io.wdata
  idu.io.wb_valid := wbu.io.in.valid
  idu.io.wb_rd := wbu.io.in.bits.Rd
  idu.io.wb_regWrite := wbu.io.in.bits.RegisterWrite
  idu.io.pipeline_mode := true.B
  // 手动连线了
  idu.io.ReadDATA1 := gpr.io.ReadDATA1
  idu.io.ReadDATA2 := gpr.io.ReadDATA2
  gpr.io.Read1SELECT := idu.io.Read1SELECT
  gpr.io.Read2SELECT := idu.io.Read2SELECT
  // LSU(MEM级)反馈给EXU: 非空标志+访存故障提交的CSR后门
  exu.io.MEMBusy := lsu.io.Busy
  exu.io.MemTrapCommit := lsu.io.MemTrapCommit
  exu.io.MemTrapCause := lsu.io.MemTrapCause
  exu.io.MemTrapPC := lsu.io.MemTrapPC
  // LSU(MEM级)给IDU做冒险检测和转发
  idu.io.me_valid := lsu.io.HazardValid
  idu.io.me_rd := lsu.io.HazardRd
  idu.io.me_regWrite := lsu.io.HazardRegWrite
  idu.io.me_memop := lsu.io.HazardMemOp
  idu.io.me_fwd_ready := lsu.io.FwdReady
  idu.io.me_fwd_data := lsu.io.FwdData
  // LSU等待槽(EX/MEM流水寄存器)也给IDU做冒险检测和转发
  idu.io.me2_valid := lsu.io.Hazard2Valid
  idu.io.me2_rd := lsu.io.Hazard2Rd
  idu.io.me2_regWrite := lsu.io.Hazard2RegWrite
  idu.io.me2_memop := lsu.io.Hazard2MemOp
  idu.io.me2_fwd_ready := lsu.io.Hazard2FwdReady
  idu.io.me2_fwd_data := lsu.io.Hazard2FwdData
  ifu.io.Redirect := exu.io.Redirect
  ifu.io.RedirectTarget := exu.io.RedirectTarget
  ifu.io.ExceptionTaken := exu.io.ExceptionTaken
  ifu.io.ExceptionTarget := exu.io.ExceptionTarget
  icache.io.flush := exu.io.FenceIFlush // 仅 FenceI 冲 iCache，分支不冲
  lsu.io.DCacheFlush := exu.io.FenceIFlush // fence.i同时使数据缓存失效
  gpr.io.WriteSELECT := wbu.io.WriteSELECT
  gpr.io.WriteEN := wbu.io.WriteEN
  gpr.io.wdata := wbu.io.wdata
  // 临时新加的处理中断的
  exu.io.Interrupt := io.interrupt
  // 仿真 halt 是 custom-0 指令的提交事件，而不是架构 EBREAK。再寄存
  // 一拍后暴露给 C++，保证上升沿上的所有更老 GPR 写回已经生效。
  val SimHaltCommit = exu.io.SimHaltValid
  io.trap_valid := RegNext(SimHaltCommit, false.B)
  io.trap_pc := RegEnable(exu.io.SimHaltPC, config.ResetAddr.U(32.W), SimHaltCommit)
  // sdb
  gpr.io.DebugRaddr := io.debug_gpr_raddr
  io.debug_gpr_rdata := gpr.io.DebugRdata
  // WBU 的 fire 是上升沿前的“将提交”。将 valid、PC、指令一起寄存，
  // C++ 在该上升沿之后看到 debug_commit 时，GPR 已经是提交后的状态。
  val WBUCommit = wbu.io.in.fire && wbu.io.in.bits.Retire
  io.debug_commit := RegNext(WBUCommit, false.B)
  io.debug_pc := RegEnable(wbu.io.in.bits.pc, config.ResetAddr.U(32.W), WBUCommit)
  io.debug_next_pc := RegEnable(
    wbu.io.in.bits.NextPC,
    config.ResetAddr.U(32.W),
    WBUCommit
  )
  io.debug_instructions := RegEnable(
    wbu.io.in.bits.Instruction,
    "h00000013".U(32.W),
    WBUCommit
  )

  // 调试器的 $pc 不能复用 retire trace PC。正常退休时前进到
  // 该指令的实际后继；异常/中断提交时直接记录 trap 目标。
  // 若同拍既有更老的 WBU 退休又有年轻 trap，trap 必须优先。
  val DebugArchPC = RegInit(config.ResetAddr.U(32.W))
  when(WBUCommit) {
    DebugArchPC := wbu.io.in.bits.NextPC
  }
  when(exu.io.TrapCommit) {
    DebugArchPC := exu.io.TrapTarget
  }
  io.debug_arch_pc := DebugArchPC

  io.debug_trap_valid := RegNext(exu.io.TrapCommit, false.B)
  io.debug_trap_pc := RegEnable(
    exu.io.TrapPC,
    config.ResetAddr.U(32.W),
    exu.io.TrapCommit
  )
  io.debug_trap_target := RegEnable(
    exu.io.TrapTarget,
    config.ResetAddr.U(32.W),
    exu.io.TrapCommit
  )
  io.debug_trap_cause := RegEnable(
    exu.io.TrapCause,
    0.U(32.W),
    exu.io.TrapCommit
  )

  // mtrace 只记录成功从 LSU 退休的访存。错误响应和地址非对齐均不
  // Retire，因而不会携带旧 LoadData 生成伪记录；字段与 valid 同拍锁存。
  val MtraceCommit =
    lsu.io.out.fire && lsu.io.out.bits.MemoryValid && lsu.io.out.bits.Retire
  io.debug_mtrace_valid := RegNext(MtraceCommit, false.B)
  io.debug_mtrace_pc := RegEnable(lsu.io.out.bits.pc, 0.U(32.W), MtraceCommit)
  io.debug_mtrace_wen := RegEnable(
    lsu.io.out.bits.MemoryWrite,
    false.B,
    MtraceCommit
  )
  io.debug_mtrace_addr := RegEnable(
    lsu.io.out.bits.ALUResult,
    0.U(32.W),
    MtraceCommit
  )
  io.debug_mtrace_wdata := RegEnable(
    lsu.io.out.bits.StoreData,
    0.U(32.W),
    MtraceCommit
  )
  io.debug_mtrace_rdata := RegEnable(
    lsu.io.out.bits.LoadData,
    0.U(32.W),
    MtraceCommit
  )
  io.debug_mtrace_width := RegEnable(
    lsu.io.out.bits.WidthSelect,
    0.U(2.W),
    MtraceCommit
  )

  // 调试 Access Fault 只认精确提交事件。取指错误必须随指令
  // 通过流水线到 EXU，错路响应在此前被 flush 就不得打印。
  // LSU 仍以精确 MemTrap 提交为界；cause=4/6 非对齐不是 AXI fault。
  val LSUAccessFaultCommit = lsu.io.AccessFault
  val ICacheAccessFaultCommit = exu.io.FetchAccessFaultCommit
  val AccessFaultCommit = LSUAccessFaultCommit || ICacheAccessFaultCommit
  io.debug_access_fault := RegNext(AccessFaultCommit, false.B)
  io.debug_access_fault_pc := RegEnable(
    Mux(LSUAccessFaultCommit, lsu.io.MemTrapPC, exu.io.FetchAccessFaultPC),
    0.U(32.W),
    AccessFaultCommit
  )
  io.debug_access_fault_resp := RegEnable(
    Mux(
      LSUAccessFaultCommit,
      lsu.io.AccessFaultResp,
      exu.io.FetchAccessFaultResp
    ),
    0.U(2.W),
    AccessFaultCommit
  )
  // 性能计数器
  io.perf_ifu_fetch := icache.io.resp_valid && icache.io.resp_ready
  io.perf_exu_done := exu.io.out.fire
  io.perf_lsu_load := lsu.io.Complete && !lsu.io.DebugMemoryWrite
  io.perf_lsu_store := lsu.io.Complete && lsu.io.DebugMemoryWrite
  io.perf_alu_op := exu.io.PerfALUOp
  io.perf_mem_op := exu.io.PerfMemOp
  io.perf_csr_op := exu.io.PerfCSROp
  io.perf_branch_op := exu.io.PerfBranchOp
  io.perf_jal_op := exu.io.PerfJalOp
  io.perf_jalr_op := exu.io.PerfJalrOp
  io.perf_mdu_req := exu.io.PerfMDUReq
  io.perf_mdu_done := exu.io.PerfMDUDone
  io.perf_mdu_op := exu.io.PerfMDUOp
  io.perf_mdu_active := exu.io.PerfMDUActive
  io.perf_mdu_wait := exu.io.PerfMDUWait
  io.perf_ifu_stall_pipeline := icache.io.resp_valid && !icache.io.resp_ready
  io.perf_ifu_stall_axi := ifu.io.StallICache
  io.perf_ifu_stall_ar := icache.io.perf_refill_req
  io.perf_ifu_stall_r := icache.io.perf_refill_resp
  io.perf_ifu_stall_redirect := exu.io.Redirect
  io.perf_ifu_stall_idle := ifu.io.StallIdle
  io.perf_icache_hit := icache.io.perf_hit
  io.perf_icache_miss := icache.io.perf_miss
  io.perf_dcache_hit := lsu.io.DCachePerfHit
  io.perf_dcache_miss := lsu.io.DCachePerfMiss
  io.perf_dcache_refill_req := lsu.io.DCachePerfRefillReq
  io.perf_dcache_refill_resp := lsu.io.DCachePerfRefillResp
  io.perf_execution_active := exu.io.PerfExecutionActive
  // EXU被下游阻塞: 有指令但本拍未完成(EX/MEM寄存器被占,或副作用指令等MEM级排空)
  io.perf_exu_stall_lsu := exu.io.in.valid && !exu.io.out.fire
  // EX/MEM等待槽占用: LSU级忙时有一条指令等在流水寄存器里(5级拆分买到的重叠)
  io.perf_mem_waitslot := lsu.io.Hazard2Valid
  io.perf_lsu_active := lsu.io.Active
  io.perf_lsu_load_active := lsu.io.Active && !lsu.io.IsStore
  io.perf_lsu_store_active := lsu.io.Active && lsu.io.IsStore
  io.perf_lsu_stall_read_ar := lsu.io.StallReadAR
  io.perf_lsu_stall_read_r := lsu.io.StallReadR
  io.perf_lsu_stall_write_req := lsu.io.StallWriteReq
  io.perf_lsu_stall_write_b := lsu.io.StallWriteB
  idu.io.ex_memop := exu.io.HazardMemOp
  io.perf_idu_stall_raw := idu.io.perf_stall_raw
  io.perf_idu_stall_raw_loaduse := idu.io.perf_stall_raw_loaduse
  io.perf_idu_stall_raw_alu := idu.io.perf_stall_raw_alu
  io.perf_exu_idle_noinput := exu.io.PerfIdleNoInput
  io.perf_trap := exu.io.PerfTrap

  // AXI响应协议检查: B响应的错误码不再在这里断言——DECERR/SLVERR会由LSU按
  // store访问故障精确提交(cause=7), 顶层断言会抢先把仿真停掉。
  // 这里保留BID/RID协议检查, 并消费这些顶层AXI响应信号。
  val b_handshake = io.master_bvalid && io.master_bready
  assert(
    !b_handshake || io.master_bid === 0.U,
    "AXI write: bid=%d",
    io.master_bid
  )
  assert(
    !(io.master_rvalid && io.master_rready) || io.master_rid === 0.U,
    "AXI read: rid=%d",
    io.master_rid
  )

}
