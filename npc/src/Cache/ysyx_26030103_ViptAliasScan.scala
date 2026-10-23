package ysyx_26030103.cache

import chisel3._
import chisel3.util._

/** VIPT alias 失效扫描的公共实现。
  *
  * 四种模式：
  *   - `all_sets`：单拍并行比较所有 set，refill/store 不增加额外等待，
  *     面积随 set 数线性增长（默认）。
  *   - `serial`：每周期扫描一个 set，只有一套 tag 比较/valid 写译码；
  *     扫描完成前反压新请求。
  *   - `parallel_n`：每周期扫描 N 个 set，N 可配置；比较器/写译码面积
  *     约为 all_sets 的 N/sets，而扫描等待只有 ceil(sets/N) 拍。
  *   - `directory`：不扫描 tag 阵列，维护“物理行 -> set”目录；refill
  *     安装和 store/snoop 只查目录项。目录项不足时按 set 低位分桶，
  *     冲突会失效被挤出的 Cache 行，保证不留下无法追踪的有效副本。
  *
  * 所有扫描模式都在扫描完成前拒绝可能命中旧 alias 副本的请求；扫描
  * 模式还会把新行的 valid 推迟到本轮扫描完成，避免扫描窗口内出现两份
  * 同时有效（但不可同时观测）的副本。`directory` 在 AR 握手拍完成
  * 目录更新并失效旧副本，新行要到 refill 最后一拍才置 valid。
  */
object ysyx_26030103_ViptAliasScan {
  val AllSets = "all_sets"
  val Serial = "serial"
  val ParallelN = "parallel_n"
  val Directory = "directory"
  val Modes: Seq[String] = Seq(AllSets, Serial, ParallelN, Directory)

  // store 失效事件队列深度。DCache 的写直达 store 完成通知不能被反压，
  // 因此用一个小队列缓存“待扫描的物理行”；队列满时直接清空整个 Cache，
  // 用一次性能损失换取绝对正确，不生成额外的全扫描比较器阵列。
  private val EventQueueDepth = 4

  def requireMode(mode: String, where: String): Unit =
    require(
      Modes.contains(mode),
      s"$where 的 ViptAliasScanMode=$mode 非法，只支持 ${Modes.mkString("/")}"
    )

  def isAllSets(mode: String): Boolean = mode == AllSets
  def isSerial(mode: String): Boolean = mode == Serial
  def isParallel(mode: String): Boolean = mode == ParallelN
  def isDirectory(mode: String): Boolean = mode == Directory
  def isScan(mode: String): Boolean = isSerial(mode) || isParallel(mode)

  /** 把配置的扫描宽度解析成实际 RTL 宽度。
    *
    * `parallel_n` 的配置值允许为 0（自动），自动值取 min(4, sets)；
    * 显式值必须 >=2，超过 sets 时按 sets 截断（等价于单拍全扫描）。
    * 其它模式忽略配置值：serial=1、all_sets=sets、directory 不扫描。
    */
  def resolveScanWidth(mode: String, configured: Int, sets: Int): Int = {
    require(sets >= 2, s"VIPT alias 扫描至少需要 2 个 set，收到 $sets")
    mode match {
      case AllSets => sets
      case Serial => 1
      case Directory => 1
      case ParallelN =>
        if (configured <= 0) {
          math.min(4, sets)
        } else {
          require(configured >= 2,
            s"PARALLEL_N 扫描宽度必须 >=2，收到 $configured")
          math.min(configured, sets)
        }
      case other =>
        throw new IllegalArgumentException(s"未知 VIPT alias 扫描模式: $other")
    }
  }

  /** 一次扫描/目录事件的握手状态。
    *
    * `busy`               本拍必须反压新的 Cache 请求；
    * `installAllowed`     本拍可以把 refill 新行置为 valid（扫描已覆盖
    *                      到最后一组，或目录模式恒为 true）；
    * `refillCleared`      DCache 事件队列刚完成“refill 事件”的扫描；
    * `overflow`           事件队列溢出并清空了全部 Cache 行。
    */
  final case class ScanStatus(
      busy: Bool,
      installAllowed: Bool,
      refillCleared: Bool,
      overflow: Bool
  )

  private def paddedBool(items: Vec[Bool], total: Int): Vec[Bool] =
    VecInit((0 until total).map { i =>
      if (i < items.length) items(i) else false.B
    })

  private def paddedUInt(items: Vec[UInt], total: Int, width: Int): Vec[UInt] =
    VecInit((0 until total).map { i =>
      if (i < items.length) items(i) else 0.U(width.W)
    })

  /** ICache/DCache refill 的定宽扫描（单 MSHR，同一时刻只有一个 refill）。
    *
    * 在 refill AR 握手时启动：立即清掉目标 set 的旧行，然后每周期扫描
    * `scanWidth` 个 set。`installAllowed` 只在最后一组扫描拍或扫描结束
    * 后为真，保证新行不会与尚未清理的旧 alias 副本同时有效。
    */
  def chunkedRefillScan(
      valid: Vec[Bool],
      tag: Vec[UInt],
      trigger: Bool,
      triggerTag: UInt,
      targetIndex: UInt,
      clear: Bool,
      scanWidth: Int
  ): ScanStatus = {
    val numSets = valid.length
    require(numSets >= 2, "VIPT alias 扫描至少需要 2 个 set")
    val width = scanWidth.min(numSets).max(1)
    val idxWidth = log2Ceil(numSets).max(1)
    val chunks = (numSets + width - 1) / width
    val chunkWidth = log2Ceil(chunks).max(1)
    val tagWidth = tag.head.getWidth.max(1)
    val paddedTotal = chunks * width
    val offWidth = log2Ceil(paddedTotal).max(1)
    val paddedValid = paddedBool(valid, paddedTotal)
    val paddedTag = paddedUInt(tag, paddedTotal, tagWidth)

    val active = RegInit(false.B)
    val scanTag = Reg(UInt(tagWidth.W))
    val scanTarget = Reg(UInt(idxWidth.W))
    val scanChunk = RegInit(0.U(chunkWidth.W))
    val lastChunk = scanChunk === (chunks - 1).U

    when(active) {
      for (k <- 0 until width) {
        val offset = (scanChunk * width.U + k.U)(offWidth - 1, 0)
        val inRange = offset < numSets.U
        val idx = offset(idxWidth - 1, 0)
        when(
          inRange && paddedValid(offset) && paddedTag(offset) === scanTag &&
            idx =/= scanTarget
        ) {
          valid(idx) := false.B
        }
      }
      when(lastChunk) {
        active := false.B
        scanChunk := 0.U
      }.otherwise {
        scanChunk := scanChunk + 1.U
      }
    }
    // 放在 active 之后：同一拍出现新事件时以新事件为准。ICache 只有
    // 一个在途 refill，正常时序下两者不会同时发生。
    when(trigger) {
      active := true.B
      scanTag := triggerTag
      scanTarget := targetIndex
      scanChunk := 0.U
      valid(targetIndex) := false.B
    }
    when(clear) {
      active := false.B
      scanChunk := 0.U
    }
    ScanStatus(
      busy = (active || trigger) && !clear,
      // 扫描最后一组与置 valid 同拍完成：旧副本和写译码在同一时钟沿
      // 生效，时钟沿之后只保留新行。trigger 拍不允许安装，避免新 refill
      // 在扫描尚未开始时就被旧 tag 判定为可安装。
      installAllowed = !trigger && (!active || lastChunk),
      refillCleared = false.B,
      overflow = false.B
    )
  }

  /** DCache 的定宽事件队列扫描。
    *
    * refill 安装与写直达 store 完成都可能需要失效其它 set 的同物理行
    * 副本。事件进入小队列后按 FIFO 扫描，每周期扫描 `scanWidth` 个 set，
    * 扫描期间反压新的 load。队列溢出时直接清空全部 valid，避免任何
    * 陈旧 alias 副本残留。
    */
  def chunkedEventQueueScan(
      valid: Vec[Bool],
      tag: Vec[UInt],
      refillTrigger: Bool,
      refillTag: UInt,
      refillTarget: UInt,
      storeTrigger: Bool,
      storeTag: UInt,
      clear: Bool,
      scanWidth: Int
  ): ScanStatus = {
    val numSets = valid.length
    require(numSets >= 2, "VIPT alias 扫描至少需要 2 个 set")
    val width = scanWidth.min(numSets).max(1)
    val idxWidth = log2Ceil(numSets).max(1)
    val chunks = (numSets + width - 1) / width
    val chunkWidth = log2Ceil(chunks).max(1)
    val tagWidth = tag.head.getWidth.max(1)
    val paddedTotal = chunks * width
    val offWidth = log2Ceil(paddedTotal).max(1)
    val paddedValid = paddedBool(valid, paddedTotal)
    val paddedTag = paddedUInt(tag, paddedTotal, tagWidth)
    val depth = EventQueueDepth
    val ptrWidth = log2Ceil(depth).max(1)
    val countWidth = log2Ceil(depth + 1).max(1)

    val qValid = RegInit(VecInit(Seq.fill(depth)(false.B)))
    val qTag = Reg(Vec(depth, UInt(tagWidth.W)))
    val qTargetValid = RegInit(VecInit(Seq.fill(depth)(false.B)))
    val qTarget = Reg(Vec(depth, UInt(idxWidth.W)))
    val head = RegInit(0.U(ptrWidth.W))
    val tail = RegInit(0.U(ptrWidth.W))
    val count = RegInit(0.U(countWidth.W))
    val scanChunk = RegInit(0.U(chunkWidth.W))

    val pushCount = refillTrigger.asUInt + storeTrigger.asUInt
    val scanning = count =/= 0.U
    val lastChunk = scanChunk === (chunks - 1).U
    val pop = scanning && lastChunk
    val available = depth.U + pop.asUInt - count
    val overflow = pushCount > available
    // 只有 refill 事件带目标 set；store 事件需要失效所有匹配副本。
    val refillCleared = pop && qTargetValid(head) && !clear && !overflow

    when(!overflow) {
      // 先释放队首槽位，再写入本拍新事件，避免 ring 满时两者命中同一项。
      when(pop) { qValid(head) := false.B }
      when(refillTrigger) {
        qValid(tail) := true.B
        qTag(tail) := refillTag
        qTargetValid(tail) := true.B
        qTarget(tail) := refillTarget
      }
      when(storeTrigger) {
        val storeSlot = tail + refillTrigger.asUInt
        qValid(storeSlot) := true.B
        qTag(storeSlot) := storeTag
        qTargetValid(storeSlot) := false.B
        qTarget(storeSlot) := 0.U
      }
      when(scanning) {
        for (k <- 0 until width) {
          val offset = (scanChunk * width.U + k.U)(offWidth - 1, 0)
          val inRange = offset < numSets.U
          val idx = offset(idxWidth - 1, 0)
          when(
            inRange && paddedValid(offset) &&
              paddedTag(offset) === qTag(head) &&
              (!qTargetValid(head) || idx =/= qTarget(head))
          ) {
            valid(idx) := false.B
          }
        }
        when(lastChunk) {
          scanChunk := 0.U
        }.otherwise {
          scanChunk := scanChunk + 1.U
        }
      }
      head := head + pop.asUInt
      tail := tail + pushCount
      count := count + pushCount - pop.asUInt
    }.otherwise {
      // 极端 store 风暴下队列可能溢出；整 Cache 失效仍然正确。
      valid.foreach(_ := false.B)
      qValid.foreach(_ := false.B)
      head := 0.U
      tail := 0.U
      count := 0.U
      scanChunk := 0.U
    }
    when(clear) {
      qValid.foreach(_ := false.B)
      head := 0.U
      tail := 0.U
      count := 0.U
      scanChunk := 0.U
    }
    ScanStatus(
      busy = (scanning || pushCount =/= 0.U) && !clear,
      installAllowed = refillCleared,
      refillCleared = refillCleared,
      overflow = overflow && !clear
    )
  }
}
