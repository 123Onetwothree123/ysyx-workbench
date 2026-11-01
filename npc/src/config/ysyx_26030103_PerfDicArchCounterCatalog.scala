package ysyx_26030103.config

/** 性能计数器目录：分组 → 组内单计数器键。
  *
  * 键名与Kconfig符号后缀（PERF_DIC_ARCH_COUNTER_<键大写>）、RTL端口后缀以及
  * C++宏名（CONFIG_PERF_DIC_ARCH_COUNTER_<键大写>）一一对应。
  * 各组总开关与单计数器开关的相与逻辑在 `ysyx_26030103_NPCConfig` 内完成。
  */
object ysyx_26030103_PerfDicArchCounterCatalog {
  val PerfDicArchCounterGroups: Seq[(String, Seq[String])] = Seq(
    "basic" -> Seq("ifu_fetch", "exu_done", "lsu_load", "lsu_store"),
    "opclass" -> Seq(
      "alu_op",
      "mem_op",
      "csr_op",
      "branch_op",
      "jal_op",
      "jalr_op",
      "execution_active"
    ),
    "mdu" -> Seq("mdu_req", "mdu_done", "mdu_op", "mdu_active", "mdu_wait"),
    "ifu_stall" -> Seq(
      "ifu_stall_pipeline",
      "ifu_stall_axi",
      "ifu_stall_ar",
      "ifu_stall_r",
      "ifu_stall_redirect",
      "ifu_stall_idle"
    ),
    "icache" -> Seq("icache_hit", "icache_miss"),
    "dcache" -> Seq(
      "dcache_hit",
      "dcache_miss",
      "dcache_refill_req",
      "dcache_refill_resp"
    ),
    "exu_stall" -> Seq("exu_stall_lsu", "exu_idle_noinput", "mem_waitslot"),
    "lsu" -> Seq(
      "lsu_active",
      "lsu_load_active",
      "lsu_store_active",
      "lsu_stall_read_ar",
      "lsu_stall_read_r",
      "lsu_stall_write_req",
      "lsu_stall_write_b"
    ),
    "idu_stall" -> Seq(
      "idu_stall_raw",
      "idu_stall_raw_loaduse",
      "idu_stall_raw_alu"
    ),
    "trap" -> Seq("trap_count"),
    "branch_pred" -> Seq(
      "btb_hit",
      "btb_miss",
      "jal_btb_hit",
      "ras_hit",
      "branch_mispredict",
      "branch_taken",
      "branch_not_taken",
      "ras_miss",
      "redirect_branch",
      "redirect_fence_i",
      "redirect_trap",
      "redirect_mret"
    ),
    "mmu" -> Seq(
      "itlb_hit",
      "itlb_miss",
      "dtlb_hit",
      "dtlb_miss",
      "tlb_refill",
      "tlb_flush",
      "ptw_walk",
      "ptw_walk_cycle",
      "page_fault",
      "tlb_superpage_hit",
      "tlb_asid_hit"
    ),
    "fpu" -> Seq(
      "fpu_req",
      "fpu_done",
      "fpu_active",
      "fpu_stall",
      "fpu_divsqrt",
      "fpu_fma",
      "fpu_exception"
    ),
    "axi" -> Seq(
      "axi_ar",
      "axi_aw",
      "axi_r",
      "axi_w",
      "axi_b",
      "axi_stall",
      "axi_error"
    ),
    "inst_mix" -> Seq(
      "load_op",
      "store_op",
      "amo_op",
      "fence_op",
      "rvc_inst",
      "system_op",
      "illegal_inst",
      "flush_cycle",
      "wfi_inst"
    ),
    "cache_detail" -> Seq(
      "icache_refill",
      "dcache_refill",
      "uncached_access",
      "icache_line_fill",
      "dcache_line_fill",
      "dcache_writeback"
    ),
    "interrupt" -> Seq(
      "timer_irq",
      "external_irq",
      "interrupt_taken",
      "plic_claim",
      "plic_complete",
      "interrupt_pending_cycle",
      "interrupt_latency"
    ),
    "wbuf" -> Seq("wbuf_full", "wbuf_drain", "wbuf_occupancy"),
    "csr" -> Seq("csr_read", "csr_write", "csr_illegal"),
    "mdu_detail" -> Seq(
      "mul_active",
      "div_active",
      "div_iterations",
      "div_early_out"
    ),
    "queue" -> Seq("issue_occupancy", "retire_occupancy", "bubble_cycle")
  )
  val AllPerfDicArchCounters: Set[String] = PerfDicArchCounterGroups.flatMap(_._2).toSet
}
