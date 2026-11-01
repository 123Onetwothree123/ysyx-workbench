package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

object CPUAXI4BundleParameters {
  // CPU 侧 AXI4 端口的地址/数据宽度由 SOC_XLEN 决定：
  //   SOC_XLEN=32 -> 32 位地址、32 位数据（与旧版完全一致）
  //   SOC_XLEN=64 -> 64 位地址、64 位数据（由 AXI4Downsizer 接入 32 位 SoC）
  // ID 宽度沿用 ChipLinkParam.idBits，保持 4 位边界 ABI。
  def apply(
      addrBits: Int = 32,
      dataBits: Int = 32,
      idBits: Int = ChipLinkParam.idBits
  ) = AXI4BundleParameters(
    addrBits = addrBits,
    dataBits = dataBits,
    idBits = idBits
  )
}

class ysyx_26030103(xlen: Int, idBits: Int) extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val io_interrupt = Input(Bool())
    // UART 中断进入 CPU 内部 PLIC source 10；与外部中断直连端口分离，
    // 避免 full-SoC 把 PLIC 路径伪装成一根 M/S 外部中断线。
    val io_uart_irq = Input(Bool())
    val io_master = AXI4Bundle(CPUAXI4BundleParameters(xlen, xlen, idBits))
    val io_slave = Flipped(AXI4Bundle(CPUAXI4BundleParameters(xlen, xlen, idBits)))
    // 加的接口，本来以为OSOC代码只要改一个学号就可以的，结果发现居然还得改，因为OSOC的文档讲了要把这个SOC作为顶层模块
    val io_trap_valid = Output(Bool())
    val io_trap_pc = Output(UInt(32.W))
    // sdb
    val io_debug_gpr_raddr = Input(UInt(5.W))
    val io_debug_gpr_rdata = Output(UInt(32.W))
    val io_debug_gpr_wen = Output(Bool())
    val io_debug_gpr_waddr = Output(UInt(5.W))
    // 浮点 DiffTest 观测口：与 direct-NPC 顶层完全同名、同宽、同拍。
    // fpr 读口在时钟沿后回读物理寄存器；写口与 GPR 写口同沿，
    // fcsr 是 WBU 退休边界快照，fp_valid 表示本构建是否启用 F。
    // FLEN=32 时高 32 位固定为 0，低/高两半必须来自同一拍。
    val io_debug_fpr_raddr = Input(UInt(5.W))
    val io_debug_fpr_rdata = Output(UInt(32.W))
    val io_debug_fpr_rdata_hi = Output(UInt(32.W))
    val io_debug_fpr_wen = Output(Bool())
    val io_debug_fpr_waddr = Output(UInt(5.W))
    val io_debug_fcsr = Output(UInt(32.W))
    val io_debug_fp_valid = Output(Bool())
    val io_debug_pc = Output(UInt(32.W))
    val io_debug_next_pc = Output(UInt(32.W))
    val io_debug_arch_pc = Output(UInt(32.W))
    val io_debug_instructions = Output(UInt(32.W))
    // mtrace
    val io_debug_mtrace_valid = Output(Bool())
    val io_debug_mtrace_pc    = Output(UInt(32.W))
    val io_debug_mtrace_wen   = Output(Bool())
    val io_debug_mtrace_addr  = Output(UInt(32.W))
    val io_debug_mtrace_wdata = Output(UInt(32.W))
    val io_debug_mtrace_rdata = Output(UInt(32.W))
    val io_debug_mtrace_width = Output(UInt(2.W))
    // Access Fault
    val io_debug_access_fault = Output(Bool())
    val io_debug_access_fault_pc = Output(UInt(32.W))
    val io_debug_access_fault_resp = Output(UInt(2.W))
    val io_debug_commit = Output(Bool())
    val io_debug_trap_valid = Output(Bool())
    val io_debug_trap_pc = Output(UInt(32.W))
    val io_debug_trap_target = Output(UInt(32.W))
    val io_debug_trap_cause = Output(UInt(32.W))
    // 本次 trap 写入 mtval/stval 的最终值。低/高半字与 trap_cause
    // 同一拍锁存；RV32 高半区由 NPC 顶层驱动为 0。
    val io_debug_trap_tval = Output(UInt(32.W))
    val io_debug_trap_tval_hi = Output(UInt(32.W))
    val io_debug_csr_mstatus = Output(UInt(32.W))
    val io_debug_csr_mtvec = Output(UInt(32.W))
    val io_debug_csr_mepc = Output(UInt(32.W))
    val io_debug_csr_mcause = Output(UInt(32.W))
    val io_debug_trap_mstatus = Output(UInt(32.W))
    val io_debug_trap_mtvec = Output(UInt(32.W))
    val io_debug_trap_mepc = Output(UInt(32.W))
    val io_debug_trap_mcause = Output(UInt(32.W))
    // RV64 调试/trap 观测 ABI 的高 32 位视图。上面所有低 32 位端口
    // 的名称、宽度与语义保持历史定义，既有 32 位 C++/仿真连接不变；
    // 这里只做加法。RV32 构建中 NPC 把全部 `_hi` 驱动为 0。
    // 高/低两半必须与同一拍的事件对齐，不能在 SoC 内自行拼接或寄存。
    val io_debug_gpr_rdata_hi = Output(UInt(32.W))
    val io_debug_pc_hi = Output(UInt(32.W))
    val io_debug_next_pc_hi = Output(UInt(32.W))
    val io_debug_arch_pc_hi = Output(UInt(32.W))
    val io_debug_mtrace_pc_hi = Output(UInt(32.W))
    val io_debug_mtrace_addr_hi = Output(UInt(32.W))
    val io_debug_mtrace_wdata_hi = Output(UInt(32.W))
    val io_debug_mtrace_rdata_hi = Output(UInt(32.W))
    val io_debug_access_fault_pc_hi = Output(UInt(32.W))
    val io_debug_trap_pc_hi = Output(UInt(32.W))
    val io_debug_trap_target_hi = Output(UInt(32.W))
    val io_debug_csr_mstatus_hi = Output(UInt(32.W))
    val io_debug_csr_mtvec_hi = Output(UInt(32.W))
    val io_debug_csr_mepc_hi = Output(UInt(32.W))
    val io_debug_csr_mcause_hi = Output(UInt(32.W))
    val io_debug_trap_mstatus_hi = Output(UInt(32.W))
    val io_debug_trap_mtvec_hi = Output(UInt(32.W))
    val io_debug_trap_mepc_hi = Output(UInt(32.W))
    val io_debug_trap_mcause_hi = Output(UInt(32.W))
    // 仿真 custom halt 提交时的完整 PC；低 32 位仍由 io_trap_pc 提供。
    val io_trap_pc_hi = Output(UInt(32.W))
    // 性能计数器
    val io_perf_ifu_fetch  = Output(Bool())
    val io_perf_exu_done   = Output(Bool())
    val io_perf_lsu_load   = Output(Bool())
    val io_perf_lsu_store  = Output(Bool())
    val io_perf_alu_op     = Output(Bool())
    val io_perf_mem_op     = Output(Bool())
    val io_perf_csr_op     = Output(Bool())
    val io_perf_branch_op  = Output(Bool())
    val io_perf_jal_op     = Output(Bool())
    val io_perf_jalr_op    = Output(Bool())
    val io_perf_mdu_req    = Output(Bool())
    val io_perf_mdu_done   = Output(Bool())
    val io_perf_mdu_op     = Output(UInt(3.W))
    val io_perf_exu_event_kind = Output(UInt(3.W))
    val io_perf_mdu_active = Output(Bool())
    val io_perf_mdu_wait   = Output(Bool())
    val io_perf_ifu_stall_pipeline = Output(Bool())
    val io_perf_ifu_stall_axi      = Output(Bool())
    val io_perf_ifu_stall_redirect = Output(Bool())
    val io_perf_execution_active   = Output(Bool())
    val io_perf_lsu_active         = Output(Bool())
    val io_perf_ifu_stall_ar       = Output(Bool())
    val io_perf_ifu_stall_r        = Output(Bool())
    val io_perf_ifu_stall_idle     = Output(Bool())
    val io_perf_exu_stall_lsu      = Output(Bool())
    val io_perf_lsu_load_active    = Output(Bool())
    val io_perf_lsu_store_active   = Output(Bool())
    val io_perf_lsu_stall_read_ar  = Output(Bool())
    val io_perf_lsu_stall_read_r   = Output(Bool())
    val io_perf_lsu_stall_write_req = Output(Bool())
    val io_perf_lsu_stall_write_b  = Output(Bool())
    val io_perf_icache_hit         = Output(Bool())
    val io_perf_icache_miss        = Output(Bool())
    val io_perf_dcache_hit         = Output(Bool())
    val io_perf_dcache_miss        = Output(Bool())
    val io_perf_dcache_refill_req  = Output(Bool())
    val io_perf_dcache_refill_resp = Output(Bool())
    val io_perf_idu_stall_raw         = Output(Bool())
    val io_perf_idu_stall_raw_loaduse = Output(Bool())
    val io_perf_idu_stall_raw_alu     = Output(Bool())
    val io_perf_exu_idle_noinput      = Output(Bool())
    val io_perf_trap                  = Output(Bool())
    val io_perf_mem_waitslot          = Output(Bool())
    // 完整 DIC 架构计数器事件 ABI：与 npc/src/ysyx_26030103_IO.scala
    // 同名、同宽，full-SoC 不再只导出早期的一小部分事件。
    val io_perf_btb_hit = Output(Bool())
    val io_perf_btb_miss = Output(Bool())
    val io_perf_jal_btb_hit = Output(Bool())
    val io_perf_ras_hit = Output(Bool())
    val io_perf_branch_mispredict = Output(Bool())
    val io_perf_itlb_hit = Output(Bool())
    val io_perf_itlb_miss = Output(Bool())
    val io_perf_dtlb_hit = Output(Bool())
    val io_perf_dtlb_miss = Output(Bool())
    val io_perf_tlb_refill = Output(Bool())
    val io_perf_fpu_req = Output(Bool())
    val io_perf_fpu_done = Output(Bool())
    val io_perf_axi_ar = Output(Bool())
    val io_perf_axi_aw = Output(Bool())
    val io_perf_axi_r = Output(Bool())
    val io_perf_axi_w = Output(Bool())
    val io_perf_axi_b = Output(Bool())
    val io_perf_load_op = Output(Bool())
    val io_perf_store_op = Output(Bool())
    val io_perf_amo_op = Output(Bool())
    val io_perf_branch_taken = Output(Bool())
    val io_perf_branch_not_taken = Output(Bool())
    val io_perf_tlb_flush = Output(Bool())
    val io_perf_fpu_active = Output(Bool())
    val io_perf_fence_op = Output(Bool())
    val io_perf_rvc_inst = Output(Bool())
    val io_perf_system_op = Output(Bool())
    val io_perf_icache_refill = Output(Bool())
    val io_perf_dcache_refill = Output(Bool())
    val io_perf_timer_irq = Output(Bool())
    val io_perf_external_irq = Output(Bool())
    val io_perf_interrupt_taken = Output(Bool())
    val io_perf_wbuf_full = Output(Bool())
    val io_perf_wbuf_drain = Output(Bool())
    val io_perf_csr_read = Output(Bool())
    val io_perf_csr_write = Output(Bool())
    val io_perf_mul_active = Output(Bool())
    val io_perf_div_active = Output(Bool())
    val io_perf_ptw_walk = Output(Bool())
    val io_perf_ptw_walk_cycle = Output(Bool())
    val io_perf_page_fault = Output(Bool())
    val io_perf_ras_miss = Output(Bool())
    val io_perf_redirect_branch = Output(Bool())
    val io_perf_redirect_fence_i = Output(Bool())
    val io_perf_redirect_trap = Output(Bool())
    val io_perf_redirect_mret = Output(Bool())
    val io_perf_fpu_stall = Output(Bool())
    val io_perf_illegal_inst = Output(Bool())
    val io_perf_axi_stall = Output(Bool())
    val io_perf_axi_error = Output(Bool())
    val io_perf_fpu_divsqrt = Output(Bool())
    val io_perf_fpu_fma = Output(Bool())
    val io_perf_fpu_exception = Output(Bool())
    val io_perf_csr_illegal = Output(Bool())
    val io_perf_plic_claim = Output(Bool())
    val io_perf_plic_complete = Output(Bool())
    val io_perf_flush_cycle = Output(Bool())
    val io_perf_wbuf_count = Output(UInt(5.W))
    val io_perf_uncached_access = Output(Bool())
    val io_perf_irq_pending = Output(Bool())
    val io_perf_wfi_inst = Output(Bool())
    val io_perf_bubble_cycle = Output(Bool())
    val io_perf_issue_occupancy = Output(Bool())
    val io_perf_retire_occupancy = Output(Bool())
    val io_perf_icache_line_fill = Output(Bool())
    val io_perf_dcache_line_fill = Output(Bool())
    val io_perf_dcache_writeback = Output(Bool())
    val io_perf_div_iterations = Output(UInt(16.W))
    val io_perf_div_early_out = Output(Bool())
    val io_perf_div_done = Output(Bool())
    val io_perf_tlb_superpage_hit = Output(Bool())
    val io_perf_tlb_asid_hit = Output(Bool())
  })
}

class CPU(idBits: Int, xlen: Int)(implicit p: Parameters) extends LazyModule {
  val masterNode = AXI4MasterNode(
    p(ExtIn)
      .map(params =>
        AXI4MasterPortParameters(
          masters = Seq(
            AXI4MasterParameters(name = "cpu", id = IdRange(0, 1 << idBits))
          )
        )
      )
      .toSeq
  )
  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (master, _) = masterNode.out(0)
    // CPU 侧宽度由下游第一个节点决定；SOC_XLEN=64 时必须先接
    // AXI4Downsizer，否则这里会在 elaboration 期直接报错，而不是
    // 让 64 位端口被静默接到 32 位 xbar。
    require(
      master.params.dataBits == xlen,
      s"CPU AXI4 数据宽度 ${master.params.dataBits} 与 SOC_XLEN=$xlen 不一致"
    )
    val interrupt = IO(Input(Bool()))
    val uart_irq = IO(Input(Bool()))
    val slave = IO(Flipped(AXI4Bundle(CPUAXI4BundleParameters(xlen, xlen, idBits))))

    val cpu = Module(new ysyx_26030103(xlen, idBits))
    cpu.io.clock := clock
    cpu.io.reset := reset
    cpu.io.io_interrupt := interrupt
    cpu.io.io_uart_irq := uart_irq
    cpu.io.io_slave <> slave
    master <> cpu.io.io_master

    // 新加的，为了实现仿真停止的，和上面注释一样，接上去的
    val trap_valid = IO(Output(Bool()))
    val trap_pc = IO(Output(UInt(32.W)))
    trap_valid := cpu.io.io_trap_valid
    trap_pc := cpu.io.io_trap_pc
    // sdb
    val debug_gpr_raddr = IO(Input(UInt(5.W)))
    val debug_gpr_rdata = IO(Output(UInt(32.W)))
    val debug_gpr_wen = IO(Output(Bool()))
    val debug_gpr_waddr = IO(Output(UInt(5.W)))
    val debug_pc = IO(Output(UInt(32.W)))
    val debug_next_pc = IO(Output(UInt(32.W)))
    val debug_arch_pc = IO(Output(UInt(32.W)))

    cpu.io.io_debug_gpr_raddr := debug_gpr_raddr
    debug_gpr_rdata := cpu.io.io_debug_gpr_rdata
    debug_gpr_wen := cpu.io.io_debug_gpr_wen
    debug_gpr_waddr := cpu.io.io_debug_gpr_waddr
    // 浮点观测口只做同拍直连，不在 SoC 层寄存或拆分低/高半字。
    val debug_fpr_raddr = IO(Input(UInt(5.W)))
    val debug_fpr_rdata = IO(Output(UInt(32.W)))
    val debug_fpr_rdata_hi = IO(Output(UInt(32.W)))
    val debug_fpr_wen = IO(Output(Bool()))
    val debug_fpr_waddr = IO(Output(UInt(5.W)))
    val debug_fcsr = IO(Output(UInt(32.W)))
    val debug_fp_valid = IO(Output(Bool()))
    cpu.io.io_debug_fpr_raddr := debug_fpr_raddr
    debug_fpr_rdata := cpu.io.io_debug_fpr_rdata
    debug_fpr_rdata_hi := cpu.io.io_debug_fpr_rdata_hi
    debug_fpr_wen := cpu.io.io_debug_fpr_wen
    debug_fpr_waddr := cpu.io.io_debug_fpr_waddr
    debug_fcsr := cpu.io.io_debug_fcsr
    debug_fp_valid := cpu.io.io_debug_fp_valid
    debug_pc := cpu.io.io_debug_pc
    debug_next_pc := cpu.io.io_debug_next_pc
    debug_arch_pc := cpu.io.io_debug_arch_pc
    val debug_instructions = IO(Output(UInt(32.W)))
    debug_instructions := cpu.io.io_debug_instructions
    // mtrace
    val debug_mtrace_valid = IO(Output(Bool()))
    val debug_mtrace_pc    = IO(Output(UInt(32.W)))
    val debug_mtrace_wen   = IO(Output(Bool()))
    val debug_mtrace_addr  = IO(Output(UInt(32.W)))
    val debug_mtrace_wdata = IO(Output(UInt(32.W)))
    val debug_mtrace_rdata = IO(Output(UInt(32.W)))
    val debug_mtrace_width = IO(Output(UInt(2.W)))
    debug_mtrace_valid := cpu.io.io_debug_mtrace_valid
    debug_mtrace_pc    := cpu.io.io_debug_mtrace_pc
    debug_mtrace_wen   := cpu.io.io_debug_mtrace_wen
    debug_mtrace_addr  := cpu.io.io_debug_mtrace_addr
    debug_mtrace_wdata := cpu.io.io_debug_mtrace_wdata
    debug_mtrace_rdata := cpu.io.io_debug_mtrace_rdata
    debug_mtrace_width := cpu.io.io_debug_mtrace_width
    // Access Fault
    val debug_access_fault = IO(Output(Bool()))
    debug_access_fault := cpu.io.io_debug_access_fault
    val debug_access_fault_pc = IO(Output(UInt(32.W)))
    debug_access_fault_pc := cpu.io.io_debug_access_fault_pc
    val debug_access_fault_resp = IO(Output(UInt(2.W)))
    debug_access_fault_resp := cpu.io.io_debug_access_fault_resp
    val debug_commit = IO(Output(Bool()))
    debug_commit := cpu.io.io_debug_commit
    val debug_trap_valid = IO(Output(Bool()))
    val debug_trap_pc = IO(Output(UInt(32.W)))
    val debug_trap_target = IO(Output(UInt(32.W)))
    val debug_trap_cause = IO(Output(UInt(32.W)))
    debug_trap_valid := cpu.io.io_debug_trap_valid
    debug_trap_pc := cpu.io.io_debug_trap_pc
    debug_trap_target := cpu.io.io_debug_trap_target
    debug_trap_cause := cpu.io.io_debug_trap_cause
    // tval 与 cause 同拍直连，低/高半字不做 SoC 侧拼接。
    val debug_trap_tval = IO(Output(UInt(32.W)))
    val debug_trap_tval_hi = IO(Output(UInt(32.W)))
    debug_trap_tval := cpu.io.io_debug_trap_tval
    debug_trap_tval_hi := cpu.io.io_debug_trap_tval_hi
    val debug_csr_mstatus = IO(Output(UInt(32.W)))
    val debug_csr_mtvec = IO(Output(UInt(32.W)))
    val debug_csr_mepc = IO(Output(UInt(32.W)))
    val debug_csr_mcause = IO(Output(UInt(32.W)))
    val debug_trap_mstatus = IO(Output(UInt(32.W)))
    val debug_trap_mtvec = IO(Output(UInt(32.W)))
    val debug_trap_mepc = IO(Output(UInt(32.W)))
    val debug_trap_mcause = IO(Output(UInt(32.W)))
    debug_csr_mstatus := cpu.io.io_debug_csr_mstatus
    debug_csr_mtvec := cpu.io.io_debug_csr_mtvec
    debug_csr_mepc := cpu.io.io_debug_csr_mepc
    debug_csr_mcause := cpu.io.io_debug_csr_mcause
    debug_trap_mstatus := cpu.io.io_debug_trap_mstatus
    debug_trap_mtvec := cpu.io.io_debug_trap_mtvec
    debug_trap_mepc := cpu.io.io_debug_trap_mepc
    debug_trap_mcause := cpu.io.io_debug_trap_mcause
    // 64 位观测 ABI：高 32 位原样上送，不在这一层做任何截断/拼接。
    val debug_gpr_rdata_hi = IO(Output(UInt(32.W)))
    val debug_pc_hi = IO(Output(UInt(32.W)))
    val debug_next_pc_hi = IO(Output(UInt(32.W)))
    val debug_arch_pc_hi = IO(Output(UInt(32.W)))
    val debug_mtrace_pc_hi = IO(Output(UInt(32.W)))
    val debug_mtrace_addr_hi = IO(Output(UInt(32.W)))
    val debug_mtrace_wdata_hi = IO(Output(UInt(32.W)))
    val debug_mtrace_rdata_hi = IO(Output(UInt(32.W)))
    val debug_access_fault_pc_hi = IO(Output(UInt(32.W)))
    val debug_trap_pc_hi = IO(Output(UInt(32.W)))
    val debug_trap_target_hi = IO(Output(UInt(32.W)))
    val debug_csr_mstatus_hi = IO(Output(UInt(32.W)))
    val debug_csr_mtvec_hi = IO(Output(UInt(32.W)))
    val debug_csr_mepc_hi = IO(Output(UInt(32.W)))
    val debug_csr_mcause_hi = IO(Output(UInt(32.W)))
    val debug_trap_mstatus_hi = IO(Output(UInt(32.W)))
    val debug_trap_mtvec_hi = IO(Output(UInt(32.W)))
    val debug_trap_mepc_hi = IO(Output(UInt(32.W)))
    val debug_trap_mcause_hi = IO(Output(UInt(32.W)))
    val trap_pc_hi = IO(Output(UInt(32.W)))
    debug_gpr_rdata_hi := cpu.io.io_debug_gpr_rdata_hi
    debug_pc_hi := cpu.io.io_debug_pc_hi
    debug_next_pc_hi := cpu.io.io_debug_next_pc_hi
    debug_arch_pc_hi := cpu.io.io_debug_arch_pc_hi
    debug_mtrace_pc_hi := cpu.io.io_debug_mtrace_pc_hi
    debug_mtrace_addr_hi := cpu.io.io_debug_mtrace_addr_hi
    debug_mtrace_wdata_hi := cpu.io.io_debug_mtrace_wdata_hi
    debug_mtrace_rdata_hi := cpu.io.io_debug_mtrace_rdata_hi
    debug_access_fault_pc_hi := cpu.io.io_debug_access_fault_pc_hi
    debug_trap_pc_hi := cpu.io.io_debug_trap_pc_hi
    debug_trap_target_hi := cpu.io.io_debug_trap_target_hi
    debug_csr_mstatus_hi := cpu.io.io_debug_csr_mstatus_hi
    debug_csr_mtvec_hi := cpu.io.io_debug_csr_mtvec_hi
    debug_csr_mepc_hi := cpu.io.io_debug_csr_mepc_hi
    debug_csr_mcause_hi := cpu.io.io_debug_csr_mcause_hi
    debug_trap_mstatus_hi := cpu.io.io_debug_trap_mstatus_hi
    debug_trap_mtvec_hi := cpu.io.io_debug_trap_mtvec_hi
    debug_trap_mepc_hi := cpu.io.io_debug_trap_mepc_hi
    debug_trap_mcause_hi := cpu.io.io_debug_trap_mcause_hi
    trap_pc_hi := cpu.io.io_trap_pc_hi
    // 性能计数器
    val perf_mem_waitslot = IO(Output(Bool()))
    perf_mem_waitslot := cpu.io.io_perf_mem_waitslot
    val perf_ifu_fetch = IO(Output(Bool()))
    perf_ifu_fetch := cpu.io.io_perf_ifu_fetch
    val perf_exu_done = IO(Output(Bool()))
    perf_exu_done := cpu.io.io_perf_exu_done
    val perf_lsu_load = IO(Output(Bool()))
    perf_lsu_load := cpu.io.io_perf_lsu_load
    val perf_lsu_store = IO(Output(Bool()))
    perf_lsu_store := cpu.io.io_perf_lsu_store
    val perf_alu_op = IO(Output(Bool()))
    perf_alu_op := cpu.io.io_perf_alu_op
    val perf_mem_op = IO(Output(Bool()))
    perf_mem_op := cpu.io.io_perf_mem_op
    val perf_csr_op = IO(Output(Bool()))
    perf_csr_op := cpu.io.io_perf_csr_op
    val perf_branch_op = IO(Output(Bool()))
    perf_branch_op := cpu.io.io_perf_branch_op
    val perf_jal_op = IO(Output(Bool()))
    perf_jal_op := cpu.io.io_perf_jal_op
    val perf_jalr_op = IO(Output(Bool()))
    perf_jalr_op := cpu.io.io_perf_jalr_op
    val perf_mdu_req = IO(Output(Bool()))
    perf_mdu_req := cpu.io.io_perf_mdu_req
    val perf_mdu_done = IO(Output(Bool()))
    perf_mdu_done := cpu.io.io_perf_mdu_done
    val perf_mdu_op = IO(Output(UInt(3.W)))
    perf_mdu_op := cpu.io.io_perf_mdu_op
    val perf_exu_event_kind = IO(Output(UInt(3.W)))
    perf_exu_event_kind := cpu.io.io_perf_exu_event_kind
    val perf_mdu_active = IO(Output(Bool()))
    perf_mdu_active := cpu.io.io_perf_mdu_active
    val perf_mdu_wait = IO(Output(Bool()))
    perf_mdu_wait := cpu.io.io_perf_mdu_wait
    val perf_ifu_stall_pipeline = IO(Output(Bool()))
    perf_ifu_stall_pipeline := cpu.io.io_perf_ifu_stall_pipeline
    val perf_ifu_stall_axi = IO(Output(Bool()))
    perf_ifu_stall_axi := cpu.io.io_perf_ifu_stall_axi
    val perf_ifu_stall_redirect = IO(Output(Bool()))
    perf_ifu_stall_redirect := cpu.io.io_perf_ifu_stall_redirect
    val perf_execution_active = IO(Output(Bool()))
    perf_execution_active := cpu.io.io_perf_execution_active
    val perf_lsu_active = IO(Output(Bool()))
    perf_lsu_active := cpu.io.io_perf_lsu_active
    val perf_ifu_stall_ar = IO(Output(Bool()))
    perf_ifu_stall_ar := cpu.io.io_perf_ifu_stall_ar
    val perf_ifu_stall_r = IO(Output(Bool()))
    perf_ifu_stall_r := cpu.io.io_perf_ifu_stall_r
    val perf_ifu_stall_idle = IO(Output(Bool()))
    perf_ifu_stall_idle := cpu.io.io_perf_ifu_stall_idle
    val perf_exu_stall_lsu = IO(Output(Bool()))
    perf_exu_stall_lsu := cpu.io.io_perf_exu_stall_lsu
    val perf_lsu_load_active = IO(Output(Bool()))
    perf_lsu_load_active := cpu.io.io_perf_lsu_load_active
    val perf_lsu_store_active = IO(Output(Bool()))
    perf_lsu_store_active := cpu.io.io_perf_lsu_store_active
    val perf_lsu_stall_read_ar = IO(Output(Bool()))
    perf_lsu_stall_read_ar := cpu.io.io_perf_lsu_stall_read_ar
    val perf_lsu_stall_read_r = IO(Output(Bool()))
    perf_lsu_stall_read_r := cpu.io.io_perf_lsu_stall_read_r
    val perf_lsu_stall_write_req = IO(Output(Bool()))
    perf_lsu_stall_write_req := cpu.io.io_perf_lsu_stall_write_req
    val perf_lsu_stall_write_b = IO(Output(Bool()))
    perf_lsu_stall_write_b := cpu.io.io_perf_lsu_stall_write_b
    val perf_icache_hit = IO(Output(Bool()))
    perf_icache_hit := cpu.io.io_perf_icache_hit
    val perf_icache_miss = IO(Output(Bool()))
    perf_icache_miss := cpu.io.io_perf_icache_miss
    val perf_dcache_hit = IO(Output(Bool()))
    perf_dcache_hit := cpu.io.io_perf_dcache_hit
    val perf_dcache_miss = IO(Output(Bool()))
    perf_dcache_miss := cpu.io.io_perf_dcache_miss
    val perf_dcache_refill_req = IO(Output(Bool()))
    perf_dcache_refill_req := cpu.io.io_perf_dcache_refill_req
    val perf_dcache_refill_resp = IO(Output(Bool()))
    perf_dcache_refill_resp := cpu.io.io_perf_dcache_refill_resp
    val perf_idu_stall_raw = IO(Output(Bool()))
    perf_idu_stall_raw := cpu.io.io_perf_idu_stall_raw
    val perf_idu_stall_raw_loaduse = IO(Output(Bool()))
    perf_idu_stall_raw_loaduse := cpu.io.io_perf_idu_stall_raw_loaduse
    val perf_idu_stall_raw_alu = IO(Output(Bool()))
    perf_idu_stall_raw_alu := cpu.io.io_perf_idu_stall_raw_alu
    val perf_exu_idle_noinput = IO(Output(Bool()))
    perf_exu_idle_noinput := cpu.io.io_perf_exu_idle_noinput
    val perf_trap = IO(Output(Bool()))
    perf_trap := cpu.io.io_perf_trap
    // 完整 DIC 架构计数器事件 ABI，逐口直连 NPC 顶层，不在 SoC 层寄存。
    val perf_btb_hit = IO(Output(Bool()))
    perf_btb_hit := cpu.io.io_perf_btb_hit
    val perf_btb_miss = IO(Output(Bool()))
    perf_btb_miss := cpu.io.io_perf_btb_miss
    val perf_jal_btb_hit = IO(Output(Bool()))
    perf_jal_btb_hit := cpu.io.io_perf_jal_btb_hit
    val perf_ras_hit = IO(Output(Bool()))
    perf_ras_hit := cpu.io.io_perf_ras_hit
    val perf_branch_mispredict = IO(Output(Bool()))
    perf_branch_mispredict := cpu.io.io_perf_branch_mispredict
    val perf_itlb_hit = IO(Output(Bool()))
    perf_itlb_hit := cpu.io.io_perf_itlb_hit
    val perf_itlb_miss = IO(Output(Bool()))
    perf_itlb_miss := cpu.io.io_perf_itlb_miss
    val perf_dtlb_hit = IO(Output(Bool()))
    perf_dtlb_hit := cpu.io.io_perf_dtlb_hit
    val perf_dtlb_miss = IO(Output(Bool()))
    perf_dtlb_miss := cpu.io.io_perf_dtlb_miss
    val perf_tlb_refill = IO(Output(Bool()))
    perf_tlb_refill := cpu.io.io_perf_tlb_refill
    val perf_fpu_req = IO(Output(Bool()))
    perf_fpu_req := cpu.io.io_perf_fpu_req
    val perf_fpu_done = IO(Output(Bool()))
    perf_fpu_done := cpu.io.io_perf_fpu_done
    val perf_axi_ar = IO(Output(Bool()))
    perf_axi_ar := cpu.io.io_perf_axi_ar
    val perf_axi_aw = IO(Output(Bool()))
    perf_axi_aw := cpu.io.io_perf_axi_aw
    val perf_axi_r = IO(Output(Bool()))
    perf_axi_r := cpu.io.io_perf_axi_r
    val perf_axi_w = IO(Output(Bool()))
    perf_axi_w := cpu.io.io_perf_axi_w
    val perf_axi_b = IO(Output(Bool()))
    perf_axi_b := cpu.io.io_perf_axi_b
    val perf_load_op = IO(Output(Bool()))
    perf_load_op := cpu.io.io_perf_load_op
    val perf_store_op = IO(Output(Bool()))
    perf_store_op := cpu.io.io_perf_store_op
    val perf_amo_op = IO(Output(Bool()))
    perf_amo_op := cpu.io.io_perf_amo_op
    val perf_branch_taken = IO(Output(Bool()))
    perf_branch_taken := cpu.io.io_perf_branch_taken
    val perf_branch_not_taken = IO(Output(Bool()))
    perf_branch_not_taken := cpu.io.io_perf_branch_not_taken
    val perf_tlb_flush = IO(Output(Bool()))
    perf_tlb_flush := cpu.io.io_perf_tlb_flush
    val perf_fpu_active = IO(Output(Bool()))
    perf_fpu_active := cpu.io.io_perf_fpu_active
    val perf_fence_op = IO(Output(Bool()))
    perf_fence_op := cpu.io.io_perf_fence_op
    val perf_rvc_inst = IO(Output(Bool()))
    perf_rvc_inst := cpu.io.io_perf_rvc_inst
    val perf_system_op = IO(Output(Bool()))
    perf_system_op := cpu.io.io_perf_system_op
    val perf_icache_refill = IO(Output(Bool()))
    perf_icache_refill := cpu.io.io_perf_icache_refill
    val perf_dcache_refill = IO(Output(Bool()))
    perf_dcache_refill := cpu.io.io_perf_dcache_refill
    val perf_timer_irq = IO(Output(Bool()))
    perf_timer_irq := cpu.io.io_perf_timer_irq
    val perf_external_irq = IO(Output(Bool()))
    perf_external_irq := cpu.io.io_perf_external_irq
    val perf_interrupt_taken = IO(Output(Bool()))
    perf_interrupt_taken := cpu.io.io_perf_interrupt_taken
    val perf_wbuf_full = IO(Output(Bool()))
    perf_wbuf_full := cpu.io.io_perf_wbuf_full
    val perf_wbuf_drain = IO(Output(Bool()))
    perf_wbuf_drain := cpu.io.io_perf_wbuf_drain
    val perf_csr_read = IO(Output(Bool()))
    perf_csr_read := cpu.io.io_perf_csr_read
    val perf_csr_write = IO(Output(Bool()))
    perf_csr_write := cpu.io.io_perf_csr_write
    val perf_mul_active = IO(Output(Bool()))
    perf_mul_active := cpu.io.io_perf_mul_active
    val perf_div_active = IO(Output(Bool()))
    perf_div_active := cpu.io.io_perf_div_active
    val perf_ptw_walk = IO(Output(Bool()))
    perf_ptw_walk := cpu.io.io_perf_ptw_walk
    val perf_ptw_walk_cycle = IO(Output(Bool()))
    perf_ptw_walk_cycle := cpu.io.io_perf_ptw_walk_cycle
    val perf_page_fault = IO(Output(Bool()))
    perf_page_fault := cpu.io.io_perf_page_fault
    val perf_ras_miss = IO(Output(Bool()))
    perf_ras_miss := cpu.io.io_perf_ras_miss
    val perf_redirect_branch = IO(Output(Bool()))
    perf_redirect_branch := cpu.io.io_perf_redirect_branch
    val perf_redirect_fence_i = IO(Output(Bool()))
    perf_redirect_fence_i := cpu.io.io_perf_redirect_fence_i
    val perf_redirect_trap = IO(Output(Bool()))
    perf_redirect_trap := cpu.io.io_perf_redirect_trap
    val perf_redirect_mret = IO(Output(Bool()))
    perf_redirect_mret := cpu.io.io_perf_redirect_mret
    val perf_fpu_stall = IO(Output(Bool()))
    perf_fpu_stall := cpu.io.io_perf_fpu_stall
    val perf_illegal_inst = IO(Output(Bool()))
    perf_illegal_inst := cpu.io.io_perf_illegal_inst
    val perf_axi_stall = IO(Output(Bool()))
    perf_axi_stall := cpu.io.io_perf_axi_stall
    val perf_axi_error = IO(Output(Bool()))
    perf_axi_error := cpu.io.io_perf_axi_error
    val perf_fpu_divsqrt = IO(Output(Bool()))
    perf_fpu_divsqrt := cpu.io.io_perf_fpu_divsqrt
    val perf_fpu_fma = IO(Output(Bool()))
    perf_fpu_fma := cpu.io.io_perf_fpu_fma
    val perf_fpu_exception = IO(Output(Bool()))
    perf_fpu_exception := cpu.io.io_perf_fpu_exception
    val perf_csr_illegal = IO(Output(Bool()))
    perf_csr_illegal := cpu.io.io_perf_csr_illegal
    val perf_plic_claim = IO(Output(Bool()))
    perf_plic_claim := cpu.io.io_perf_plic_claim
    val perf_plic_complete = IO(Output(Bool()))
    perf_plic_complete := cpu.io.io_perf_plic_complete
    val perf_flush_cycle = IO(Output(Bool()))
    perf_flush_cycle := cpu.io.io_perf_flush_cycle
    val perf_wbuf_count = IO(Output(UInt(5.W)))
    perf_wbuf_count := cpu.io.io_perf_wbuf_count
    val perf_uncached_access = IO(Output(Bool()))
    perf_uncached_access := cpu.io.io_perf_uncached_access
    val perf_irq_pending = IO(Output(Bool()))
    perf_irq_pending := cpu.io.io_perf_irq_pending
    val perf_wfi_inst = IO(Output(Bool()))
    perf_wfi_inst := cpu.io.io_perf_wfi_inst
    val perf_bubble_cycle = IO(Output(Bool()))
    perf_bubble_cycle := cpu.io.io_perf_bubble_cycle
    val perf_issue_occupancy = IO(Output(Bool()))
    perf_issue_occupancy := cpu.io.io_perf_issue_occupancy
    val perf_retire_occupancy = IO(Output(Bool()))
    perf_retire_occupancy := cpu.io.io_perf_retire_occupancy
    val perf_icache_line_fill = IO(Output(Bool()))
    perf_icache_line_fill := cpu.io.io_perf_icache_line_fill
    val perf_dcache_line_fill = IO(Output(Bool()))
    perf_dcache_line_fill := cpu.io.io_perf_dcache_line_fill
    val perf_dcache_writeback = IO(Output(Bool()))
    perf_dcache_writeback := cpu.io.io_perf_dcache_writeback
    val perf_div_iterations = IO(Output(UInt(16.W)))
    perf_div_iterations := cpu.io.io_perf_div_iterations
    val perf_div_early_out = IO(Output(Bool()))
    perf_div_early_out := cpu.io.io_perf_div_early_out
    val perf_div_done = IO(Output(Bool()))
    perf_div_done := cpu.io.io_perf_div_done
    val perf_tlb_superpage_hit = IO(Output(Bool()))
    perf_tlb_superpage_hit := cpu.io.io_perf_tlb_superpage_hit
    val perf_tlb_asid_hit = IO(Output(Bool()))
    perf_tlb_asid_hit := cpu.io.io_perf_tlb_asid_hit
  }
}
