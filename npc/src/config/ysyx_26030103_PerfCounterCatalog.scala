package ysyx_26030103.config

/** 性能计数器目录：分组 → 组内单计数器键。
  *
  * 键名与Kconfig符号后缀（PERF_COUNTER_<键大写>）、RTL端口后缀以及
  * C++宏名（CONFIG_PERF_COUNTER_<键大写>）一一对应。
  * 各组总开关与单计数器开关的相与逻辑在 `ysyx_26030103_NPCConfig` 内完成。
  */
object ysyx_26030103_PerfCounterCatalog {
  val PerfCounterGroups: Seq[(String, Seq[String])] = Seq(
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
    "trap" -> Seq("trap_count")
  )
  val AllPerfCounters: Set[String] = PerfCounterGroups.flatMap(_._2).toSet
}
