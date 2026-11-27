package ysyx_26030103

import chisel3._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config.ysyx_26030103_NPCConfig
import _root_.ysyx_26030103.infra._

// direct-NPC的Chisel SimTop，Verilator与GSIM共用这一份顶层
// 端口名和方向与旧手写rtl/riscv32e_npc_SimTop.sv逐项一致，C++侧
// 不需要区分后端；SOCXLEN=32/64时CPU与AXIRAM直接同宽连接
object Riscv32eNpcSimTop {
  // 工厂默认把SOCXLEN投影为config的生效值；显式传参可覆盖
  def apply(
      npcConfig: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig(),
      SOCXLEN: Int = -1,
      RAMBytes: Long = ysyx_26030103_KconfigDefaults.NpcRamSize,
      UARTBase: Long = ysyx_26030103_PMAOptions.Default.UARTBase,
      RAMBase: Long = ysyx_26030103_PMAOptions.Default.NpcRAMBase
  ): Riscv32eNpcSimTop =
    new Riscv32eNpcSimTop(
      npcConfig,
      if (SOCXLEN < 0) npcConfig.EffectiveSocXLEN else SOCXLEN,
      RAMBytes,
      UARTBase,
      RAMBase
    )
}

class Riscv32eNpcSimTop(
    val npcConfig: ysyx_26030103_NPCConfig,
    val SOCXLEN: Int,
    val RAMBytes: Long = ysyx_26030103_KconfigDefaults.NpcRamSize,
    val UARTBase: Long = ysyx_26030103_PMAOptions.Default.UARTBase,
    val RAMBase: Long = ysyx_26030103_PMAOptions.Default.NpcRAMBase
) extends Module {
  override def desiredName: String = "riscv32e_npc_SimTop"

  val `ram_init_valid` = IO(Input(Bool()))
  val `ram_init_index` = IO(Input(UInt(32.W)))
  val `ram_init_data` = IO(Input(UInt(SOCXLEN.W)))
  val `uart_rx_valid` = IO(Input(Bool()))
  val `uart_rx_data` = IO(Input(UInt(8.W)))
  val `uart_rx_ready` = IO(Output(Bool()))
  val `uart_tx_valid` = IO(Output(Bool()))
  val `uart_tx_data` = IO(Output(UInt(8.W)))
  val `uart_irq` = IO(Output(Bool()))
  val `trap_valid` = IO(Output(Bool()))
  val `trap_pc` = IO(Output(UInt(32.W)))
  val `trap_pc_hi` = IO(Output(UInt(32.W)))
  val `debug_gpr_raddr` = IO(Input(UInt(5.W)))
  val `debug_gpr_rdata` = IO(Output(UInt(32.W)))
  val `debug_gpr_rdata_hi` = IO(Output(UInt(32.W)))
  val `debug_gpr_wen` = IO(Output(Bool()))
  val `debug_gpr_waddr` = IO(Output(UInt(5.W)))
  val `debug_fpr_raddr` = IO(Input(UInt(5.W)))
  val `debug_fpr_rdata` = IO(Output(UInt(32.W)))
  val `debug_fpr_rdata_hi` = IO(Output(UInt(32.W)))
  val `debug_fpr_wen` = IO(Output(Bool()))
  val `debug_fpr_waddr` = IO(Output(UInt(5.W)))
  val `debug_fcsr` = IO(Output(UInt(32.W)))
  val `debug_fp_valid` = IO(Output(Bool()))
  val `debug_pc` = IO(Output(UInt(32.W)))
  val `debug_pc_hi` = IO(Output(UInt(32.W)))
  val `debug_next_pc` = IO(Output(UInt(32.W)))
  val `debug_next_pc_hi` = IO(Output(UInt(32.W)))
  val `debug_arch_pc` = IO(Output(UInt(32.W)))
  val `debug_arch_pc_hi` = IO(Output(UInt(32.W)))
  val `debug_instructions` = IO(Output(UInt(32.W)))
  val `debug_mtrace_valid` = IO(Output(Bool()))
  val `debug_mtrace_pc` = IO(Output(UInt(32.W)))
  val `debug_mtrace_pc_hi` = IO(Output(UInt(32.W)))
  val `debug_mtrace_wen` = IO(Output(Bool()))
  val `debug_mtrace_addr` = IO(Output(UInt(32.W)))
  val `debug_mtrace_addr_hi` = IO(Output(UInt(32.W)))
  val `debug_mtrace_wdata` = IO(Output(UInt(32.W)))
  val `debug_mtrace_wdata_hi` = IO(Output(UInt(32.W)))
  val `debug_mtrace_rdata` = IO(Output(UInt(32.W)))
  val `debug_mtrace_rdata_hi` = IO(Output(UInt(32.W)))
  val `debug_mtrace_width` = IO(Output(UInt(2.W)))
  val `debug_access_fault` = IO(Output(Bool()))
  val `debug_access_fault_pc` = IO(Output(UInt(32.W)))
  val `debug_access_fault_pc_hi` = IO(Output(UInt(32.W)))
  val `debug_access_fault_resp` = IO(Output(UInt(2.W)))
  val `debug_commit` = IO(Output(Bool()))
  val `debug_trap_valid` = IO(Output(Bool()))
  val `debug_trap_pc` = IO(Output(UInt(32.W)))
  val `debug_trap_pc_hi` = IO(Output(UInt(32.W)))
  val `debug_trap_target` = IO(Output(UInt(32.W)))
  val `debug_trap_target_hi` = IO(Output(UInt(32.W)))
  val `debug_trap_cause` = IO(Output(UInt(32.W)))
  val `debug_trap_tval` = IO(Output(UInt(32.W)))
  val `debug_trap_tval_hi` = IO(Output(UInt(32.W)))
  val `debug_csr_mstatus` = IO(Output(UInt(32.W)))
  val `debug_csr_mstatus_hi` = IO(Output(UInt(32.W)))
  val `debug_csr_mtvec` = IO(Output(UInt(32.W)))
  val `debug_csr_mtvec_hi` = IO(Output(UInt(32.W)))
  val `debug_csr_mepc` = IO(Output(UInt(32.W)))
  val `debug_csr_mepc_hi` = IO(Output(UInt(32.W)))
  val `debug_csr_mcause` = IO(Output(UInt(32.W)))
  val `debug_csr_mcause_hi` = IO(Output(UInt(32.W)))
  val `debug_trap_mstatus` = IO(Output(UInt(32.W)))
  val `debug_trap_mstatus_hi` = IO(Output(UInt(32.W)))
  val `debug_trap_mtvec` = IO(Output(UInt(32.W)))
  val `debug_trap_mtvec_hi` = IO(Output(UInt(32.W)))
  val `debug_trap_mepc` = IO(Output(UInt(32.W)))
  val `debug_trap_mepc_hi` = IO(Output(UInt(32.W)))
  val `debug_trap_mcause` = IO(Output(UInt(32.W)))
  val `debug_trap_mcause_hi` = IO(Output(UInt(32.W)))
  val `perf_ifu_fetch` = IO(Output(Bool()))
  val `perf_exu_done` = IO(Output(Bool()))
  val `perf_lsu_load` = IO(Output(Bool()))
  val `perf_lsu_store` = IO(Output(Bool()))
  val `perf_alu_op` = IO(Output(Bool()))
  val `perf_mem_op` = IO(Output(Bool()))
  val `perf_csr_op` = IO(Output(Bool()))
  val `perf_branch_op` = IO(Output(Bool()))
  val `perf_jal_op` = IO(Output(Bool()))
  val `perf_jalr_op` = IO(Output(Bool()))
  val `perf_mdu_req` = IO(Output(Bool()))
  val `perf_mdu_done` = IO(Output(Bool()))
  val `perf_mdu_active` = IO(Output(Bool()))
  val `perf_mdu_wait` = IO(Output(Bool()))
  val `perf_mdu_op` = IO(Output(UInt(3.W)))
  val `perf_exu_event_kind` = IO(Output(UInt(3.W)))
  val `perf_ifu_stall_pipeline` = IO(Output(Bool()))
  val `perf_ifu_stall_axi` = IO(Output(Bool()))
  val `perf_ifu_stall_ar` = IO(Output(Bool()))
  val `perf_ifu_stall_r` = IO(Output(Bool()))
  val `perf_ifu_stall_redirect` = IO(Output(Bool()))
  val `perf_ifu_stall_idle` = IO(Output(Bool()))
  val `perf_execution_active` = IO(Output(Bool()))
  val `perf_exu_stall_lsu` = IO(Output(Bool()))
  val `perf_lsu_active` = IO(Output(Bool()))
  val `perf_lsu_load_active` = IO(Output(Bool()))
  val `perf_lsu_store_active` = IO(Output(Bool()))
  val `perf_lsu_stall_read_ar` = IO(Output(Bool()))
  val `perf_lsu_stall_read_r` = IO(Output(Bool()))
  val `perf_lsu_stall_write_req` = IO(Output(Bool()))
  val `perf_lsu_stall_write_b` = IO(Output(Bool()))
  val `perf_icache_hit` = IO(Output(Bool()))
  val `perf_icache_miss` = IO(Output(Bool()))
  val `perf_dcache_hit` = IO(Output(Bool()))
  val `perf_dcache_miss` = IO(Output(Bool()))
  val `perf_dcache_refill_req` = IO(Output(Bool()))
  val `perf_dcache_refill_resp` = IO(Output(Bool()))
  val `perf_idu_stall_raw` = IO(Output(Bool()))
  val `perf_idu_stall_raw_loaduse` = IO(Output(Bool()))
  val `perf_idu_stall_raw_alu` = IO(Output(Bool()))
  val `perf_exu_idle_noinput` = IO(Output(Bool()))
  val `perf_trap_count` = IO(Output(Bool()))
  val `perf_mem_waitslot` = IO(Output(Bool()))
  val `perf_btb_hit` = IO(Output(Bool()))
  val `perf_btb_miss` = IO(Output(Bool()))
  val `perf_jal_btb_hit` = IO(Output(Bool()))
  val `perf_ras_hit` = IO(Output(Bool()))
  val `perf_branch_mispredict` = IO(Output(Bool()))
  val `perf_itlb_hit` = IO(Output(Bool()))
  val `perf_itlb_miss` = IO(Output(Bool()))
  val `perf_dtlb_hit` = IO(Output(Bool()))
  val `perf_dtlb_miss` = IO(Output(Bool()))
  val `perf_tlb_refill` = IO(Output(Bool()))
  val `perf_fpu_req` = IO(Output(Bool()))
  val `perf_fpu_done` = IO(Output(Bool()))
  val `perf_axi_ar` = IO(Output(Bool()))
  val `perf_axi_aw` = IO(Output(Bool()))
  val `perf_axi_r` = IO(Output(Bool()))
  val `perf_axi_w` = IO(Output(Bool()))
  val `perf_axi_b` = IO(Output(Bool()))
  val `perf_load_op` = IO(Output(Bool()))
  val `perf_store_op` = IO(Output(Bool()))
  val `perf_amo_op` = IO(Output(Bool()))
  val `perf_branch_taken` = IO(Output(Bool()))
  val `perf_branch_not_taken` = IO(Output(Bool()))
  val `perf_tlb_flush` = IO(Output(Bool()))
  val `perf_fpu_active` = IO(Output(Bool()))
  val `perf_fence_op` = IO(Output(Bool()))
  val `perf_rvc_inst` = IO(Output(Bool()))
  val `perf_system_op` = IO(Output(Bool()))
  val `perf_icache_refill` = IO(Output(Bool()))
  val `perf_dcache_refill` = IO(Output(Bool()))
  val `perf_timer_irq` = IO(Output(Bool()))
  val `perf_external_irq` = IO(Output(Bool()))
  val `perf_interrupt_taken` = IO(Output(Bool()))
  val `perf_wbuf_full` = IO(Output(Bool()))
  val `perf_wbuf_drain` = IO(Output(Bool()))
  val `perf_csr_read` = IO(Output(Bool()))
  val `perf_csr_write` = IO(Output(Bool()))
  val `perf_mul_active` = IO(Output(Bool()))
  val `perf_div_active` = IO(Output(Bool()))
  val `perf_ptw_walk` = IO(Output(Bool()))
  val `perf_ptw_walk_cycle` = IO(Output(Bool()))
  val `perf_page_fault` = IO(Output(Bool()))
  val `perf_ras_miss` = IO(Output(Bool()))
  val `perf_redirect_branch` = IO(Output(Bool()))
  val `perf_redirect_fence_i` = IO(Output(Bool()))
  val `perf_redirect_trap` = IO(Output(Bool()))
  val `perf_redirect_mret` = IO(Output(Bool()))
  val `perf_fpu_stall` = IO(Output(Bool()))
  val `perf_illegal_inst` = IO(Output(Bool()))
  val `perf_axi_stall` = IO(Output(Bool()))
  val `perf_axi_error` = IO(Output(Bool()))
  val `perf_fpu_divsqrt` = IO(Output(Bool()))
  val `perf_fpu_fma` = IO(Output(Bool()))
  val `perf_fpu_exception` = IO(Output(Bool()))
  val `perf_csr_illegal` = IO(Output(Bool()))
  val `perf_plic_claim` = IO(Output(Bool()))
  val `perf_plic_complete` = IO(Output(Bool()))
  val `perf_flush_cycle` = IO(Output(Bool()))
  val `perf_wbuf_count` = IO(Output(UInt(5.W)))
  val `perf_uncached_access` = IO(Output(Bool()))
  val `perf_irq_pending` = IO(Output(Bool()))
  val `perf_wfi_inst` = IO(Output(Bool()))
  val `perf_bubble_cycle` = IO(Output(Bool()))
  val `perf_issue_occupancy` = IO(Output(Bool()))
  val `perf_retire_occupancy` = IO(Output(Bool()))
  val `perf_icache_line_fill` = IO(Output(Bool()))
  val `perf_dcache_line_fill` = IO(Output(Bool()))
  val `perf_dcache_writeback` = IO(Output(Bool()))
  val `perf_tlb_superpage_hit` = IO(Output(Bool()))
  val `perf_tlb_asid_hit` = IO(Output(Bool()))
  val `perf_div_iterations` = IO(Output(UInt(16.W)))
  val `perf_div_early_out` = IO(Output(Bool()))
  val `perf_div_done` = IO(Output(Bool()))

  require(
    SOCXLEN == 32 || SOCXLEN == 64,
    s"direct-NPC SimTop的SOCXLEN只允许32或64，收到 $SOCXLEN"
  )

  // SOCXLEN与npcConfig的生效值不一致时，以显式参数为准重新投影CPU外部
  // AXI；XLEN/内部fabric/PMA等其余字段保持npcConfig原值
  private val cpuConfig =
    if (npcConfig.EffectiveSocXLEN == SOCXLEN) npcConfig
    else npcConfig.copy(SOCXLENFollowCPU = false, SocXLEN = SOCXLEN)

  val cpu = Module(new _root_.ysyx_26030103.ysyx_26030103(cpuConfig))
  val ram = Module(new riscv32e_npc_AXIRAM(RAMBytes, SOCXLEN, UARTBase, RAMBase))

  // direct-NPC的UART中断同时直连CPU和内部PLIC source
  cpu.io.interrupt := `uart_irq`
  cpu.io.UARTIRQ := `uart_irq`

  private def connectCpuMaster(slave: ysyx_26030103_AXI5IO): Unit = {
    slave.AW.AWVALID := cpu.io.master.awvalid
    cpu.io.master.awready := slave.AW.AWREADY
    slave.AW.AWADDR := cpu.io.master.awaddr
    slave.AW.AWID := cpu.io.master.awid
    slave.AW.AWLEN := cpu.io.master.awlen
    slave.AW.AWSIZE := cpu.io.master.awsize
    slave.AW.AWBURST := cpu.io.master.awburst
    slave.AW.AWCACHE := cpu.io.master.awcache
    slave.AW.AWPROT := cpu.io.master.awprot
    slave.AW.AWQOS := cpu.io.master.awqos

    slave.W.WVALID := cpu.io.master.wvalid
    cpu.io.master.wready := slave.W.WREADY
    slave.W.WDATA := cpu.io.master.wdata
    slave.W.WSTRB := cpu.io.master.wstrb
    slave.W.WLAST := cpu.io.master.wlast

    cpu.io.master.bvalid := slave.B.BVALID
    cpu.io.master.bresp := slave.B.BRESP
    cpu.io.master.bid := slave.B.BID
    slave.B.BREADY := cpu.io.master.bready

    slave.AR.ARVALID := cpu.io.master.arvalid
    cpu.io.master.arready := slave.AR.ARREADY
    slave.AR.ARADDR := cpu.io.master.araddr
    slave.AR.ARID := cpu.io.master.arid
    slave.AR.ARLEN := cpu.io.master.arlen
    slave.AR.ARSIZE := cpu.io.master.arsize
    slave.AR.ARBURST := cpu.io.master.arburst
    slave.AR.ARCACHE := cpu.io.master.arcache
    slave.AR.ARPROT := cpu.io.master.arprot
    slave.AR.ARQOS := cpu.io.master.arqos

    cpu.io.master.rvalid := slave.R.RVALID
    cpu.io.master.rdata := slave.R.RDATA
    cpu.io.master.rresp := slave.R.RRESP
    cpu.io.master.rlast := slave.R.RLAST
    cpu.io.master.rid := slave.R.RID
    slave.R.RREADY := cpu.io.master.rready
  }

  // SOCXLEN=64时CPU master和AXIRAM都使用64位地址/数据，直接同宽连接；
  // 32位时保持原来的点对点直连
  connectCpuMaster(ram.io.AXI)

  // CPU slave未使用，输入固定为0，输出悬空
  cpu.io.slave.awvalid := false.B
  cpu.io.slave.awaddr := 0.U
  cpu.io.slave.awid := 0.U
  cpu.io.slave.awlen := 0.U
  cpu.io.slave.awsize := 0.U
  cpu.io.slave.awburst := 0.U
  cpu.io.slave.awlock := false.B
  cpu.io.slave.awcache := 0.U
  cpu.io.slave.awprot := 0.U
  cpu.io.slave.awqos := 0.U
  cpu.io.slave.wvalid := false.B
  cpu.io.slave.wdata := 0.U
  cpu.io.slave.wstrb := 0.U
  cpu.io.slave.wlast := false.B
  cpu.io.slave.bready := false.B
  cpu.io.slave.arvalid := false.B
  cpu.io.slave.araddr := 0.U
  cpu.io.slave.arid := 0.U
  cpu.io.slave.arlen := 0.U
  cpu.io.slave.arsize := 0.U
  cpu.io.slave.arburst := 0.U
  cpu.io.slave.arlock := false.B
  cpu.io.slave.arcache := 0.U
  cpu.io.slave.arprot := 0.U
  cpu.io.slave.arqos := 0.U
  cpu.io.slave.rready := false.B

  ram.io.hostWriteValid := `ram_init_valid`
  ram.io.hostWriteIndex := `ram_init_index`
  ram.io.hostWriteData := `ram_init_data`
  ram.io.UARTRxValid := `uart_rx_valid`
  ram.io.UARTRxData := `uart_rx_data`
  `uart_rx_ready` := ram.io.UARTRxReady
  `uart_tx_valid` := ram.io.UARTTxValid
  `uart_tx_data` := ram.io.UARTTxData
  `uart_irq` := ram.io.UARTIRQ

  cpu.io.debug.GPRRaddr := `debug_gpr_raddr`
  `debug_gpr_rdata` := cpu.io.debug.GPRRdata
  `debug_gpr_rdata_hi` := cpu.io.debug.GPRRdataHi
  `debug_gpr_wen` := cpu.io.debug.GPRWen
  `debug_gpr_waddr` := cpu.io.debug.GPRWaddr
  cpu.io.debug.FPRRaddr := `debug_fpr_raddr`
  `debug_fpr_rdata` := cpu.io.debug.FPRRdata
  `debug_fpr_rdata_hi` := cpu.io.debug.FPRRdataHi
  `debug_fpr_wen` := cpu.io.debug.FPRWen
  `debug_fpr_waddr` := cpu.io.debug.FPRWaddr
  `debug_fcsr` := cpu.io.debug.fcsr
  `debug_fp_valid` := cpu.io.debug.FPValid
  `debug_pc` := cpu.io.debug.PC
  `debug_pc_hi` := cpu.io.debug.PCHi
  `debug_next_pc` := cpu.io.debug.NextPC
  `debug_next_pc_hi` := cpu.io.debug.NextPCHi
  `debug_arch_pc` := cpu.io.debug.ArchPC
  `debug_arch_pc_hi` := cpu.io.debug.ArchPCHi
  `debug_instructions` := cpu.io.debug.instructions
  `debug_mtrace_valid` := cpu.io.debug.MTraceValid
  `debug_mtrace_pc` := cpu.io.debug.MTracePC
  `debug_mtrace_pc_hi` := cpu.io.debug.MTracePCHi
  `debug_mtrace_wen` := cpu.io.debug.MTraceWen
  `debug_mtrace_addr` := cpu.io.debug.MTraceAddress
  `debug_mtrace_addr_hi` := cpu.io.debug.MTraceAddressHi
  `debug_mtrace_wdata` := cpu.io.debug.MTraceWdata
  `debug_mtrace_wdata_hi` := cpu.io.debug.MTraceWdataHi
  `debug_mtrace_rdata` := cpu.io.debug.MTraceRdata
  `debug_mtrace_rdata_hi` := cpu.io.debug.MTraceRdataHi
  `debug_mtrace_width` := cpu.io.debug.MTraceWidth
  `debug_access_fault` := cpu.io.debug.AccessFault
  `debug_access_fault_pc` := cpu.io.debug.AccessFaultPC
  `debug_access_fault_pc_hi` := cpu.io.debug.AccessFaultPCHi
  `debug_access_fault_resp` := cpu.io.debug.AccessFaultResponse
  `debug_commit` := cpu.io.debug.commit
  `debug_trap_valid` := cpu.io.debug.TrapValid
  `debug_trap_pc` := cpu.io.debug.TrapPC
  `debug_trap_pc_hi` := cpu.io.debug.TrapPCHi
  `debug_trap_target` := cpu.io.debug.TrapTarget
  `debug_trap_target_hi` := cpu.io.debug.TrapTargetHi
  `debug_trap_cause` := cpu.io.debug.TrapCause
  `debug_trap_tval` := cpu.io.debug.TrapTval
  `debug_trap_tval_hi` := cpu.io.debug.TrapTvalHi
  `debug_csr_mstatus` := cpu.io.debug.CSRMstatus
  `debug_csr_mstatus_hi` := cpu.io.debug.CSRMstatusHi
  `debug_csr_mtvec` := cpu.io.debug.CSRMtvec
  `debug_csr_mtvec_hi` := cpu.io.debug.CSRMtvecHi
  `debug_csr_mepc` := cpu.io.debug.CSRMepc
  `debug_csr_mepc_hi` := cpu.io.debug.CSRMepcHi
  `debug_csr_mcause` := cpu.io.debug.CSRMcause
  `debug_csr_mcause_hi` := cpu.io.debug.CSRMcauseHi
  `debug_trap_mstatus` := cpu.io.debug.TrapMstatus
  `debug_trap_mstatus_hi` := cpu.io.debug.TrapMstatusHi
  `debug_trap_mtvec` := cpu.io.debug.TrapMtvec
  `debug_trap_mtvec_hi` := cpu.io.debug.TrapMtvecHi
  `debug_trap_mepc` := cpu.io.debug.TrapMepc
  `debug_trap_mepc_hi` := cpu.io.debug.TrapMepcHi
  `debug_trap_mcause` := cpu.io.debug.TrapMcause
  `debug_trap_mcause_hi` := cpu.io.debug.TrapMcauseHi
  `perf_ifu_fetch` := cpu.io.perf.ifu_fetch
  `perf_exu_done` := cpu.io.perf.exu_done
  `perf_lsu_load` := cpu.io.perf.lsu_load
  `perf_lsu_store` := cpu.io.perf.lsu_store
  `perf_alu_op` := cpu.io.perf.alu_op
  `perf_mem_op` := cpu.io.perf.mem_op
  `perf_csr_op` := cpu.io.perf.csr_op
  `perf_branch_op` := cpu.io.perf.branch_op
  `perf_jal_op` := cpu.io.perf.jal_op
  `perf_jalr_op` := cpu.io.perf.jalr_op
  `perf_mdu_req` := cpu.io.perf.mdu_req
  `perf_mdu_done` := cpu.io.perf.mdu_done
  `perf_mdu_op` := cpu.io.perf.mdu_op
  `perf_mdu_active` := cpu.io.perf.mdu_active
  `perf_mdu_wait` := cpu.io.perf.mdu_wait
  `perf_exu_event_kind` := cpu.io.perf.exu_event_kind
  `perf_ifu_stall_pipeline` := cpu.io.perf.ifu_stall_pipeline
  `perf_ifu_stall_axi` := cpu.io.perf.ifu_stall_axi
  `perf_ifu_stall_ar` := cpu.io.perf.ifu_stall_ar
  `perf_ifu_stall_r` := cpu.io.perf.ifu_stall_r
  `perf_ifu_stall_redirect` := cpu.io.perf.ifu_stall_redirect
  `perf_ifu_stall_idle` := cpu.io.perf.ifu_stall_idle
  `perf_execution_active` := cpu.io.perf.execution_active
  `perf_exu_stall_lsu` := cpu.io.perf.exu_stall_lsu
  `perf_lsu_active` := cpu.io.perf.lsu_active
  `perf_lsu_load_active` := cpu.io.perf.lsu_load_active
  `perf_lsu_store_active` := cpu.io.perf.lsu_store_active
  `perf_lsu_stall_read_ar` := cpu.io.perf.lsu_stall_read_ar
  `perf_lsu_stall_read_r` := cpu.io.perf.lsu_stall_read_r
  `perf_lsu_stall_write_req` := cpu.io.perf.lsu_stall_write_req
  `perf_lsu_stall_write_b` := cpu.io.perf.lsu_stall_write_b
  `perf_icache_hit` := cpu.io.perf.icache_hit
  `perf_icache_miss` := cpu.io.perf.icache_miss
  `perf_dcache_hit` := cpu.io.perf.dcache_hit
  `perf_dcache_miss` := cpu.io.perf.dcache_miss
  `perf_dcache_refill_req` := cpu.io.perf.dcache_refill_req
  `perf_dcache_refill_resp` := cpu.io.perf.dcache_refill_resp
  `perf_idu_stall_raw` := cpu.io.perf.idu_stall_raw
  `perf_idu_stall_raw_loaduse` := cpu.io.perf.idu_stall_raw_loaduse
  `perf_idu_stall_raw_alu` := cpu.io.perf.idu_stall_raw_alu
  `perf_exu_idle_noinput` := cpu.io.perf.exu_idle_noinput
  `perf_trap_count` := cpu.io.perf.trap_count
  `perf_mem_waitslot` := cpu.io.perf.mem_waitslot
  `perf_btb_hit` := cpu.io.perf.btb_hit
  `perf_btb_miss` := cpu.io.perf.btb_miss
  `perf_jal_btb_hit` := cpu.io.perf.jal_btb_hit
  `perf_ras_hit` := cpu.io.perf.ras_hit
  `perf_branch_mispredict` := cpu.io.perf.branch_mispredict
  `perf_itlb_hit` := cpu.io.perf.itlb_hit
  `perf_itlb_miss` := cpu.io.perf.itlb_miss
  `perf_dtlb_hit` := cpu.io.perf.dtlb_hit
  `perf_dtlb_miss` := cpu.io.perf.dtlb_miss
  `perf_tlb_refill` := cpu.io.perf.tlb_refill
  `perf_fpu_req` := cpu.io.perf.fpu_req
  `perf_fpu_done` := cpu.io.perf.fpu_done
  `perf_axi_ar` := cpu.io.perf.axi_ar
  `perf_axi_aw` := cpu.io.perf.axi_aw
  `perf_axi_r` := cpu.io.perf.axi_r
  `perf_axi_w` := cpu.io.perf.axi_w
  `perf_axi_b` := cpu.io.perf.axi_b
  `perf_load_op` := cpu.io.perf.load_op
  `perf_store_op` := cpu.io.perf.store_op
  `perf_amo_op` := cpu.io.perf.amo_op
  `perf_branch_taken` := cpu.io.perf.branch_taken
  `perf_branch_not_taken` := cpu.io.perf.branch_not_taken
  `perf_tlb_flush` := cpu.io.perf.tlb_flush
  `perf_fpu_active` := cpu.io.perf.fpu_active
  `perf_fence_op` := cpu.io.perf.fence_op
  `perf_rvc_inst` := cpu.io.perf.rvc_inst
  `perf_system_op` := cpu.io.perf.system_op
  `perf_icache_refill` := cpu.io.perf.icache_refill
  `perf_dcache_refill` := cpu.io.perf.dcache_refill
  `perf_timer_irq` := cpu.io.perf.timer_irq
  `perf_external_irq` := cpu.io.perf.external_irq
  `perf_interrupt_taken` := cpu.io.perf.interrupt_taken
  `perf_wbuf_full` := cpu.io.perf.wbuf_full
  `perf_wbuf_drain` := cpu.io.perf.wbuf_drain
  `perf_csr_read` := cpu.io.perf.csr_read
  `perf_csr_write` := cpu.io.perf.csr_write
  `perf_mul_active` := cpu.io.perf.mul_active
  `perf_div_active` := cpu.io.perf.div_active
  `perf_ptw_walk` := cpu.io.perf.ptw_walk
  `perf_ptw_walk_cycle` := cpu.io.perf.ptw_walk_cycle
  `perf_page_fault` := cpu.io.perf.page_fault
  `perf_ras_miss` := cpu.io.perf.ras_miss
  `perf_redirect_branch` := cpu.io.perf.redirect_branch
  `perf_redirect_fence_i` := cpu.io.perf.redirect_fence_i
  `perf_redirect_trap` := cpu.io.perf.redirect_trap
  `perf_redirect_mret` := cpu.io.perf.redirect_mret
  `perf_fpu_stall` := cpu.io.perf.fpu_stall
  `perf_illegal_inst` := cpu.io.perf.illegal_inst
  `perf_axi_stall` := cpu.io.perf.axi_stall
  `perf_axi_error` := cpu.io.perf.axi_error
  `perf_fpu_divsqrt` := cpu.io.perf.fpu_divsqrt
  `perf_fpu_fma` := cpu.io.perf.fpu_fma
  `perf_fpu_exception` := cpu.io.perf.fpu_exception
  `perf_csr_illegal` := cpu.io.perf.csr_illegal
  `perf_plic_claim` := cpu.io.perf.plic_claim
  `perf_plic_complete` := cpu.io.perf.plic_complete
  `perf_flush_cycle` := cpu.io.perf.flush_cycle
  `perf_wbuf_count` := cpu.io.perf.wbuf_count
  `perf_uncached_access` := cpu.io.perf.uncached_access
  `perf_irq_pending` := cpu.io.perf.irq_pending
  `perf_wfi_inst` := cpu.io.perf.wfi_inst
  `perf_bubble_cycle` := cpu.io.perf.bubble_cycle
  `perf_issue_occupancy` := cpu.io.perf.issue_occupancy
  `perf_retire_occupancy` := cpu.io.perf.retire_occupancy
  `perf_icache_line_fill` := cpu.io.perf.icache_line_fill
  `perf_dcache_line_fill` := cpu.io.perf.dcache_line_fill
  `perf_dcache_writeback` := cpu.io.perf.dcache_writeback
  `perf_tlb_superpage_hit` := cpu.io.perf.tlb_superpage_hit
  `perf_tlb_asid_hit` := cpu.io.perf.tlb_asid_hit
  `perf_div_iterations` := cpu.io.perf.div_iterations
  `perf_div_early_out` := cpu.io.perf.div_early_out
  `perf_div_done` := cpu.io.perf.div_done
  `trap_valid` := cpu.io.trap.valid
  `trap_pc` := cpu.io.trap.PC
  `trap_pc_hi` := cpu.io.trap.PCHi
}
