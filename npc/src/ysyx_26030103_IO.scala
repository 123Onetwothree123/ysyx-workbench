package ysyx_26030103
import chisel3._

/** 顶层端口 ABI。
  *
  * `SocXLEN` 独立于 CPU `XLEN`/`AddressWidth`，决定 ysyxSoC CPU 侧
  * AXI 端口的地址、数据和 WSTRB 宽度：
  *   - SOC_XLEN=32：保持历史的 32 位地址/32 位数据/4 位 WSTRB；
  *   - SOC_XLEN=64：使用 64 位地址/64 位数据/8 位 WSTRB。
  *
  * NPC 内部 SoC 总线固定 32 位数据，由 `ysyx_26030103_AXI5WidthBridge`
  * 在边界完成 32<->64 转换和地址越界 DECERR。任何一侧都不会靠截断
  * 高位地址或丢弃宽数据来“适配”端口。
  *
  * 调试/trap 观测 ABI 刻意不跟随 SocXLEN：低 32 位端口保持历史
  * 名称/宽度，RV64 的完整 PC/GPR/CSR/trap 状态通过 `_hi` 端口并行
  * 导出。direct-NPC 与 full-SoC 的 ysyxSoCFull 都连接并导出这组
  * `_hi` 端口，C++ DiffTest 使用同一套低/高字拼接逻辑。
  */
class ysyx_26030103_IO(val SocXLEN: Int = 32) extends Bundle {
  require(SocXLEN == 32 || SocXLEN == 64,
    s"顶层 IO 的 SocXLEN 只允许 32 或 64，收到 $SocXLEN")
  private val SocDataWidth = SocXLEN
  private val SocStrbWidth = SocXLEN / 8
  val interrupt = Input(Bool())
  // UART 中断单独成口，full-SoC 把它接到内部 PLIC source 10；direct-NPC
  // 同时把该信号与 interrupt 相连，保持既有“外部中断线直连”的测试语义。
  val uart_irq = Input(Bool())
  // 仿真 ABI 的 custom-0 halt 提交脉冲；不是架构异常/EBREAK 指示。
  val trap_valid = Output(Bool())
  val trap_pc = Output(UInt(32.W))
//写地址的
  val master_awready = Input(Bool())
  val master_awvalid = Output(Bool())
  val master_awaddr = Output(UInt(SocXLEN.W))
  val master_awid = Output(UInt(4.W))
  val master_awlen = Output(UInt(8.W))
  val master_awsize = Output(UInt(3.W))
  val master_awburst = Output(UInt(2.W))
  val master_awlock = Output(Bool())
  val master_awcache = Output(UInt(4.W))
  val master_awprot = Output(UInt(3.W))
  val master_awqos = Output(UInt(4.W))
//W写的
  val master_wready = Input(Bool())
  val master_wvalid = Output(Bool())
  val master_wdata = Output(UInt(SocDataWidth.W))
  val master_wstrb = Output(UInt(SocStrbWidth.W))
  val master_wlast = Output(Bool())
//B写回的
  val master_bready = Output(Bool())
  val master_bvalid = Input(Bool())
  val master_bresp = Input(UInt(2.W))
  val master_bid = Input(UInt(4.W))
//AR的
  val master_arready = Input(Bool())
  val master_arvalid = Output(Bool())
  val master_araddr = Output(UInt(SocXLEN.W))
  val master_arid = Output(UInt(4.W))
  val master_arlen = Output(UInt(8.W))
  val master_arsize = Output(UInt(3.W))
  val master_arburst = Output(UInt(2.W))
  val master_arlock = Output(Bool())
  val master_arcache = Output(UInt(4.W))
  val master_arprot = Output(UInt(3.W))
  val master_arqos = Output(UInt(4.W))
//R读数据通道
  val master_rready = Output(Bool())
  val master_rvalid = Input(Bool())
  val master_rresp = Input(UInt(2.W))
  val master_rdata = Input(UInt(SocDataWidth.W))
  val master_rlast = Input(Bool())
  val master_rid = Input(UInt(4.W))
//写地址的
  val slave_awready = Output(Bool())
  val slave_awvalid = Input(Bool())
  val slave_awaddr = Input(UInt(SocXLEN.W))
  val slave_awid = Input(UInt(4.W))
  val slave_awlen = Input(UInt(8.W))
  val slave_awsize = Input(UInt(3.W))
  val slave_awburst = Input(UInt(2.W))
  val slave_awlock = Input(Bool())
  val slave_awcache = Input(UInt(4.W))
  val slave_awprot = Input(UInt(3.W))
  val slave_awqos = Input(UInt(4.W))
//写
  val slave_wready = Output(Bool())
  val slave_wvalid = Input(Bool())
  val slave_wdata = Input(UInt(SocDataWidth.W))
  val slave_wstrb = Input(UInt(SocStrbWidth.W))
  val slave_wlast = Input(Bool())
//对面的
  val slave_bready = Input(Bool())
  val slave_bvalid = Output(Bool())
  val slave_bresp = Output(UInt(2.W))
  val slave_bid = Output(UInt(4.W))

  val slave_arready = Output(Bool())
  val slave_arvalid = Input(Bool())
  val slave_araddr = Input(UInt(SocXLEN.W))
  val slave_arid = Input(UInt(4.W))
  val slave_arlen = Input(UInt(8.W))
  val slave_arsize = Input(UInt(3.W))
  val slave_arburst = Input(UInt(2.W))
  val slave_arlock = Input(Bool())
  val slave_arcache = Input(UInt(4.W))
  val slave_arprot = Input(UInt(3.W))
  val slave_arqos = Input(UInt(4.W))

  val slave_rready = Input(Bool())
  val slave_rvalid = Output(Bool())
  val slave_rresp = Output(UInt(2.W))
  val slave_rdata = Output(UInt(SocDataWidth.W))
  val slave_rlast = Output(Bool())
  val slave_rid = Output(UInt(4.W))
  // 给sdb用的
  val debug_gpr_raddr = Input(UInt(5.W))
  val debug_gpr_rdata = Output(UInt(32.W))
  // RV64 的高 32 位视图。低 32 位端口保持原样，RV32 恒为 0，
  // 这样既不改动既有 32 位观测 ABI，也不会让 64 位 GPR 被静默截断。
  val debug_gpr_rdata_hi = Output(UInt(32.W))
  // 上升沿前实际送入寄存器堆的 GPR 写使能和地址。DiffTest 在低
  // 电平采样，沿后只回读这个物理寄存器来更新 C++ 架构镜像。
  val debug_gpr_wen = Output(Bool())
  val debug_gpr_waddr = Output(UInt(5.W))
  // 浮点 DiffTest 观测口。fpr 读口在时钟沿后回读物理寄存器；写口与 GPR
  // 写口同拍，供 C++ 只更新真正被写的 f 寄存器。fcsr 是 WBU 退休边界
  // 的快照，不能直接采样实时 CSR：年轻 FP 指令可能已经改了 fflags。
  // FLEN=32 的高 32 位固定为 0；RV32F 因此不会泄漏 NaN-box 高位。
  // direct-NPC 由 rtl/riscv32e_npc_SimTop.sv 直连，full-SoC 由
  // ysyxSoC CPU/SoC 黑盒把同名端口逐级导出，两边语义完全一致。
  val debug_fpr_raddr = Input(UInt(5.W))
  val debug_fpr_rdata = Output(UInt(32.W))
  val debug_fpr_rdata_hi = Output(UInt(32.W))
  val debug_fpr_wen = Output(Bool())
  val debug_fpr_waddr = Output(UInt(5.W))
  val debug_fcsr = Output(UInt(32.W))
  val debug_fp_valid = Output(Bool())
  // debug_pc 是本次退休指令的 PC，只能在 debug_commit 时使用。
  val debug_pc = Output(UInt(32.W))
  val debug_pc_hi = Output(UInt(32.W))
  // debug_next_pc 与退休记录同拍；debug_arch_pc 是调试器应向
  // 用户展示的最新架构 PC（也会跟踪 trap 跳转）。
  val debug_next_pc = Output(UInt(32.W))
  val debug_next_pc_hi = Output(UInt(32.W))
  val debug_arch_pc = Output(UInt(32.W))
  val debug_arch_pc_hi = Output(UInt(32.W))
  val debug_instructions = Output(UInt(32.W))
  // 内存访问跟踪
  val debug_mtrace_valid = Output(Bool())
  val debug_mtrace_pc = Output(UInt(32.W))
  val debug_mtrace_pc_hi = Output(UInt(32.W))
  val debug_mtrace_wen = Output(Bool())
  val debug_mtrace_addr = Output(UInt(32.W))
  val debug_mtrace_addr_hi = Output(UInt(32.W))
  val debug_mtrace_wdata = Output(UInt(32.W))
  val debug_mtrace_wdata_hi = Output(UInt(32.W))
  val debug_mtrace_rdata = Output(UInt(32.W))
  val debug_mtrace_rdata_hi = Output(UInt(32.W))
  val debug_mtrace_width = Output(UInt(2.W))
  // 访问错误
  val debug_access_fault = Output(Bool())
  val debug_access_fault_pc = Output(UInt(32.W))
  val debug_access_fault_pc_hi = Output(UInt(32.W))
  val debug_access_fault_resp = Output(UInt(2.W))
  val debug_commit = Output(Bool())
  val debug_trap_valid = Output(Bool())
  val debug_trap_pc = Output(UInt(32.W))
  val debug_trap_pc_hi = Output(UInt(32.W))
  val debug_trap_target = Output(UInt(32.W))
  val debug_trap_target_hi = Output(UInt(32.W))
  val debug_trap_cause = Output(UInt(32.W))
  // 本次 trap 写入 mtval/stval 的最终值。单独成组，不并入 CSR 快照，
  // 避免改变现有 DiffTest CSR ABI 宽度。direct-NPC 与 full-SoC 都通过
  // 同一组低/高半字导出，C++ 侧共用拼接逻辑。
  val debug_trap_tval = Output(UInt(32.W))
  val debug_trap_tval_hi = Output(UInt(32.W))
  // 与 retire/trap 事件分别对齐的 CSR 快照。两组不能共用，
  // 因为同拍可能同时有更老的 WBU 退休与年轻 trap。
  val debug_csr_mstatus = Output(UInt(32.W))
  val debug_csr_mstatus_hi = Output(UInt(32.W))
  val debug_csr_mtvec = Output(UInt(32.W))
  val debug_csr_mtvec_hi = Output(UInt(32.W))
  val debug_csr_mepc = Output(UInt(32.W))
  val debug_csr_mepc_hi = Output(UInt(32.W))
  val debug_csr_mcause = Output(UInt(32.W))
  val debug_csr_mcause_hi = Output(UInt(32.W))
  val debug_trap_mstatus = Output(UInt(32.W))
  val debug_trap_mstatus_hi = Output(UInt(32.W))
  val debug_trap_mtvec = Output(UInt(32.W))
  val debug_trap_mtvec_hi = Output(UInt(32.W))
  val debug_trap_mepc = Output(UInt(32.W))
  val debug_trap_mepc_hi = Output(UInt(32.W))
  val debug_trap_mcause = Output(UInt(32.W))
  val debug_trap_mcause_hi = Output(UInt(32.W))
  // 仿真 custom halt 提交时的完整 PC（低 32 位仍由 trap_pc 提供）。
  val trap_pc_hi = Output(UInt(32.W))
  // 性能计数器
  val perf_ifu_fetch = Output(Bool())
  val perf_exu_done = Output(Bool())
  val perf_lsu_load = Output(Bool())
  val perf_lsu_store = Output(Bool())
  val perf_alu_op = Output(Bool())
  val perf_mem_op = Output(Bool())
  val perf_csr_op = Output(Bool())
  val perf_branch_op = Output(Bool())
  val perf_jal_op = Output(Bool())
  val perf_jalr_op = Output(Bool())
  val perf_mdu_req = Output(Bool())
  val perf_mdu_done = Output(Bool())
  val perf_mdu_op = Output(UInt(3.W))
  val perf_exu_event_kind = Output(UInt(3.W))
  // MDU从首次发射到退休的活跃周期；wait只表示结果未就绪，
  // 结果已就绪但被下游反压时归入perf_exu_stall_lsu。
  val perf_mdu_active = Output(Bool())
  val perf_mdu_wait = Output(Bool())
  val perf_ifu_stall_pipeline = Output(Bool())
  val perf_ifu_stall_axi = Output(Bool())
  val perf_ifu_stall_ar = Output(Bool())
  val perf_ifu_stall_r = Output(Bool())
  val perf_ifu_stall_redirect = Output(Bool())
  val perf_ifu_stall_idle = Output(Bool())
  val perf_icache_hit = Output(Bool())
  val perf_icache_miss = Output(Bool())
  val perf_dcache_hit = Output(Bool())
  val perf_dcache_miss = Output(Bool())
  val perf_dcache_refill_req = Output(Bool())
  val perf_dcache_refill_resp = Output(Bool())
  val perf_execution_active = Output(Bool())
  // EX/MEM/LSU下游反压或为精确顺序等待年长指令；不含MDU计算等待。
  val perf_exu_stall_lsu = Output(Bool())
  val perf_lsu_active = Output(Bool())
  val perf_lsu_load_active = Output(Bool())
  val perf_lsu_store_active = Output(Bool())
  val perf_lsu_stall_read_ar = Output(Bool())
  val perf_lsu_stall_read_r = Output(Bool())
  val perf_lsu_stall_write_req = Output(Bool())
  val perf_lsu_stall_write_b = Output(Bool())
  val perf_idu_stall_raw = Output(Bool())
  val perf_idu_stall_raw_loaduse = Output(Bool())
  val perf_idu_stall_raw_alu = Output(Bool())
  val perf_exu_idle_noinput = Output(Bool())
  // ysyxSoC 黑盒 CPU.scala 仍使用 `io_perf_trap` 作为端口名；直接 NPC
  // 仿真/C++ ABI 使用 `perf_trap_count`。两个名字在顶层同时输出同一
  // 架构事件，避免为了适配一侧而修改另一侧源码或让端口悬空。
  val perf_trap = Output(Bool())
  val perf_trap_count = Output(Bool())
  val perf_mem_waitslot = Output(Bool())

}
