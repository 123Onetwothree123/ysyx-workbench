package ysyx_26030103
import chisel3._
import chisel3.util._

//他妈的，我们伟大的scala插件和编译器设计专家应该要以死谢罪，是哪个天才想到的，如果直接写ysyx_26030103，因为我这个顶层模块类和包同名了
//能被解读成ysyx_26030103的ysyx_26030103的AXI模块，还得手动指定从最顶层的根目录去找
import _root_.ysyx_26030103.ysyx_26030103_AXI5._
import _root_.ysyx_26030103.ysyx_26030103_GPR._
import _root_.ysyx_26030103.ysyx_26030103_Message._

class ysyx_26030103(
    resetAddr:      Long = 0x30000000L,
    AddressWidth:   Int  = 32,
    BlockSizeLog2:  Int  = 4,
    IndexBits:      Int  = 5,
    CacheableBase:  Long = 0x80000000L, // ysyxsoc默认值
    CacheableMask:  Long = 0x80000000L  // ysyxsoc默认值
) extends Module {
  val io = IO(new ysyx_26030103_IO)
  val ifu = Module(new ysyx_26030103_IFU(resetAddr))
  val idu = Module(new ysyx_26030103_IDU)
  val exu = Module(new ysyx_26030103_EXU)
  val wbu = Module(new ysyx_26030103_WBU)
  val lsu = Module(new ysyx_26030103_LSU)
  val gpr = Module(new ysyx_26030103_GPR)
  val icache = Module(new ysyx_26030103_ICache(
    BlockSizeLog2 = BlockSizeLog2,
    IndexBits     = IndexBits,
    CacheableBase = CacheableBase,
    CacheableMask = CacheableMask
  ))
  val arbiter = Module(new ysyx_26030103_AXI5Arbiter)
  val xbar = Module(new ysyx_26030103_AXI5Xbar(AddressWidth))
  val clint = Module(new ysyx_26030103_AXI5CLINTSlave)
  val pipe_flush = exu.io.FlushIF
  // 分支目标缓冲(BTB): IFU取指级查询1决定下一PC,响应级查询2给指令贴预测标签,
  // EXU提交时更新taken分支的真实target(为后文jal/ret预留扩展)
  val btb = Module(new ysyx_26030103_BTB())
  // icache响应(携带取指地址和错误标志)经冲刷流水寄存器直接进IDU
  val ifuResp = Wire(Decoupled(new ysyx_26030103_IFUMessage))
  ifuResp.valid := icache.io.resp_valid
  ifuResp.bits.Instruction := icache.io.resp_data
  ifuResp.bits.pc := icache.io.resp_addr
  ifuResp.bits.ExceptionValid := icache.io.resp_fault
  ifuResp.bits.ExceptionCause := 1.U(4.W)
  // 响应级用resp_addr查BTB,给刚取回的指令贴预测标签(预测是否taken及目标)
  btb.io.lookup2_pc := icache.io.resp_addr
  // BTFN方向预测: 后向分支(target<pc)预测taken, 前向预测not-taken
  ifuResp.bits.pred_taken := btb.io.hit2 && (btb.io.target2 < icache.io.resp_addr)
  ifuResp.bits.pred_target := btb.io.target2
  icache.io.resp_ready := ifuResp.ready
  ysyx_26030103_StageConnect(ifuResp, idu.io.in, pipe_flush)
  icache.io.kill := pipe_flush
  ysyx_26030103_StageConnect(idu.io.out, exu.io.in, lsu.io.FlushIDEX)
  ysyx_26030103_StageConnect(exu.io.out, lsu.io.in, lsu.io.FlushEXMEM)
  ysyx_26030103_StageConnect(lsu.io.out, wbu.io.in)
  icache.io.axi <> arbiter.io.ifu
  icache.io.fetch_addr  := ifu.io.FetchAddr
  icache.io.fetch_valid := ifu.io.FetchValid
  ifu.io.FetchReady      := icache.io.fetch_ready
  // BTB查询1: 用当前取指地址查,结果回送IFU决定下一PC
  btb.io.lookup_pc := ifu.io.FetchAddr
  // BTFN: BTB命中且目标在后方(target<当前PC)才预测taken, 前向分支预测not-taken
  ifu.io.PredHit    := btb.io.hit && (btb.io.target < ifu.io.FetchAddr)
  ifu.io.PredTarget := btb.io.target
  // BTB更新: EXU提交taken分支时写回真实target
  btb.io.update_valid  := exu.io.BTBUpdateValid
  btb.io.update_pc     := exu.io.BTBUpdatePC
  btb.io.update_target := exu.io.BTBUpdateTarget
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

  io.slave_awready := 0.U
  io.slave_wready := 0.U
  io.slave_bvalid := 0.U
  io.slave_bresp := 0.U
  io.slave_bid := 0.U
  io.slave_arready := 0.U
  io.slave_rvalid := 0.U
  io.slave_rresp := 0.U
  io.slave_rdata := 0.U
  io.slave_rlast := 0.U
  io.slave_rid := 0.U
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
  icache.io.flush := exu.io.FenceIFlush  // 仅 FenceI 冲 iCache，分支不冲
  // 取指或访存返回错误的标志,仅保留给SoC测试台的debug输出用
  val AccessFaultOccurred = icache.io.access_fault || lsu.io.AccessFault
  gpr.io.WriteSELECT := wbu.io.WriteSELECT
  gpr.io.WriteEN := wbu.io.WriteEN
  gpr.io.wdata := wbu.io.wdata
  // 临时新加的处理中断的
  exu.io.Interrupt := io.interrupt
  // 最后删掉的跳转检查还是加回来了，还好当时接入soc的时候，没有删掉exu的那些东西，等等，到时候还得改ysyxsoc的代码？
  io.trap_valid := exu.io.TrapValid
  io.trap_pc := exu.io.TrapPC
  // sdb
  gpr.io.DebugRaddr := io.debug_gpr_raddr
  io.debug_gpr_rdata := gpr.io.DebugRdata
  io.debug_pc := ifu.io.DebugPC
  io.debug_instructions := idu.io.in.bits.Instruction
  // mtrace: 访存指令在LSU(MEM级)完成时采样
  io.debug_mtrace_valid := lsu.io.Complete && lsu.io.HazardMemOp
  io.debug_mtrace_wen := lsu.io.DebugMemoryWrite
  io.debug_mtrace_addr := lsu.io.DebugALUResult
  io.debug_mtrace_wdata := lsu.io.DebugStoreDATA
  io.debug_mtrace_rdata := lsu.io.DebugLoadDATA
  io.debug_mtrace_width := lsu.io.DebugWidthSelect
  // Access Fault
  io.debug_access_fault := AccessFaultOccurred
  io.debug_access_fault_resp := Mux(
    icache.io.access_fault,
    icache.io.access_fault_resp,
    lsu.io.AccessFaultResp
  )
  io.debug_commit := wbu.io.WriteEN
  // 性能计数器
  io.perf_ifu_fetch := icache.io.resp_valid && icache.io.resp_ready
  io.perf_exu_done  := exu.io.out.fire
  io.perf_lsu_load  := lsu.io.Complete && !lsu.io.DebugMemoryWrite
  io.perf_lsu_store := lsu.io.Complete && lsu.io.DebugMemoryWrite
  io.perf_alu_op    := exu.io.PerfALUOp
  io.perf_mem_op    := exu.io.PerfMemOp
  io.perf_csr_op    := exu.io.PerfCSROp
  io.perf_branch_op := exu.io.PerfBranchOp
  io.perf_ifu_stall_pipeline := icache.io.resp_valid && !icache.io.resp_ready
  io.perf_ifu_stall_axi      := ifu.io.StallICache
  io.perf_ifu_stall_ar       := icache.io.perf_refill_req
  io.perf_ifu_stall_r        := icache.io.perf_refill_resp
  io.perf_ifu_stall_redirect := exu.io.Redirect
  io.perf_ifu_stall_idle     := ifu.io.StallIdle
  io.perf_icache_hit  := icache.io.perf_hit
  io.perf_icache_miss := icache.io.perf_miss
  io.perf_execution_active   := exu.io.PerfExecutionActive
  // EXU被下游阻塞: 有指令但本拍未完成(EX/MEM寄存器被占,或副作用指令等MEM级排空)
  io.perf_exu_stall_lsu      := exu.io.in.valid && !exu.io.out.fire
  // EX/MEM等待槽占用: LSU级忙时有一条指令等在流水寄存器里(5级拆分买到的重叠)
  io.perf_mem_waitslot       := lsu.io.Hazard2Valid
  io.perf_lsu_active         := lsu.io.Active
  io.perf_lsu_load_active    := lsu.io.Active && !lsu.io.IsStore
  io.perf_lsu_store_active   := lsu.io.Active && lsu.io.IsStore
  io.perf_lsu_stall_read_ar  := lsu.io.StallReadAR
  io.perf_lsu_stall_read_r   := lsu.io.StallReadR
  io.perf_lsu_stall_write_req := lsu.io.StallWriteReq
  io.perf_lsu_stall_write_b  := lsu.io.StallWriteB
  idu.io.ex_memop := exu.io.HazardMemOp
  io.perf_idu_stall_raw := idu.io.perf_stall_raw
  io.perf_idu_stall_raw_loaduse := idu.io.perf_stall_raw_loaduse
  io.perf_idu_stall_raw_alu := idu.io.perf_stall_raw_alu
  io.perf_exu_idle_noinput := exu.io.PerfIdleNoInput
  io.perf_trap := exu.io.PerfTrap

}
