package ysyx_26030103.cache

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults

/** VIPT alias 失效扫描。 */
object ysyx_26030103_ViptAliasScan {
  val AllSets = "all_sets"
  val Serial = "serial"
  val ParallelN = "parallel_n"
  val Directory = "directory"
  val Modes: Seq[String] = Seq(AllSets, Serial, ParallelN, Directory)

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

  // configured<=0 时自动取 min(4, sets)；超过 sets 时截断。
  def resolveScanWidth(mode: String, configured: Int, sets: Int): Int = {
    require(sets >= 2, s"VIPT alias 扫描至少需要 2 个 set，收到 $sets")
    mode match {
      case AllSets => sets
      case Serial => 1
      case Directory => 1
      case ParallelN =>
        if (configured <= 0) {
          math.min(ysyx_26030103_KconfigDefaults.MmuCacheViptAliasScanWidth, sets)
        } else {
          require(configured >= 2,
            s"PARALLEL_N 扫描宽度必须 >=2，收到 $configured")
          math.min(configured, sets)
        }
      case other =>
        throw new IllegalArgumentException(s"未知 VIPT alias 扫描模式: $other")
    }
  }

  final case class IcacheAliasScanStatus(
      busy: Bool,
      installAllowed: Bool
  )

  final case class DcacheAliasScanStatus(
      busy: Bool,
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

  /** 两种扫描引擎共用的几何与补齐参数。 */
  private final class Geometry(
      val valid: Vec[Bool],
      val tag: Vec[UInt],
      scanWidth: Int
  ) {
    val numSets: Int = valid.length
    require(numSets >= 2, "VIPT alias 扫描至少需要 2 个 set")
    val width: Int = scanWidth.min(numSets).max(1)
    val idxWidth: Int = log2Ceil(numSets).max(1)
    val chunks: Int = (numSets + width - 1) / width
    val chunkWidth: Int = log2Ceil(chunks).max(1)
    val tagWidth: Int = tag.head.getWidth.max(1)
    val paddedTotal: Int = chunks * width
    val offWidth: Int = log2Ceil(paddedTotal).max(1)
    val paddedValid: Vec[Bool] = paddedBool(valid, paddedTotal)
    val paddedTag: Vec[UInt] = paddedUInt(tag, paddedTotal, tagWidth)
  }

  def chunkedRefillScan(
      valid: Vec[Bool],
      tag: Vec[UInt],
      trigger: Bool,
      triggerTag: UInt,
      targetIndex: UInt,
      clear: Bool,
      scanWidth: Int
  ): IcacheAliasScanStatus = {
    val g = new Geometry(valid, tag, scanWidth)
    val numSets = g.numSets
    val width = g.width
    val idxWidth = g.idxWidth
    val chunks = g.chunks
    val chunkWidth = g.chunkWidth
    val tagWidth = g.tagWidth
    val offWidth = g.offWidth
    val paddedValid = g.paddedValid
    val paddedTag = g.paddedTag

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
    // 新事件优先于扫描状态更新；ICache 同时只有一个在途 refill。
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
    IcacheAliasScanStatus(
      busy = (active || trigger) && !clear,
      // trigger 拍不安装，避免旧 tag 判定新行可安装。
      installAllowed = !trigger && (!active || lastChunk)
    )
  }

  def chunkedEventQueueScan(
      valid: Vec[Bool],
      tag: Vec[UInt],
      refillTrigger: Bool,
      refillTag: UInt,
      refillTarget: UInt,
      storeTrigger: Bool,
      storeTag: UInt,
      clear: Bool,
      scanWidth: Int,
      eventQueueDepth: Int =
        ysyx_26030103_KconfigDefaults.MmuCacheViptAliasEventQueueDepth
  ): DcacheAliasScanStatus = {
    require(eventQueueDepth >= 2 &&
      (eventQueueDepth & (eventQueueDepth - 1)) == 0,
      s"VIPT alias 事件队列深度必须是 >=2 的 2 的幂，收到 $eventQueueDepth")
    val g = new Geometry(valid, tag, scanWidth)
    val numSets = g.numSets
    val width = g.width
    val idxWidth = g.idxWidth
    val chunks = g.chunks
    val chunkWidth = g.chunkWidth
    val tagWidth = g.tagWidth
    val offWidth = g.offWidth
    val paddedValid = g.paddedValid
    val paddedTag = g.paddedTag
    val depth = eventQueueDepth
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
    // 只有 refill 事件带目标 set；store 事件失效所有匹配副本。
    val refillCleared = pop && qTargetValid(head) && !clear && !overflow

    when(!overflow) {
      // 先释放队首槽位，再写入本拍新事件。
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
      // 队列溢出时整 Cache 失效。
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
    DcacheAliasScanStatus(
      busy = (scanning || pushCount =/= 0.U) && !clear,
      refillCleared = refillCleared,
      overflow = overflow && !clear
    )
  }

  def icacheScan(
      active: Boolean,
      valid: Vec[Bool],
      tag: Vec[UInt],
      trigger: Bool,
      triggerTag: UInt,
      targetIndex: UInt,
      clear: Bool,
      scanWidth: Int
  ): IcacheAliasScanStatus =
    if (active) {
      chunkedRefillScan(
        valid,
        tag,
        trigger,
        triggerTag,
        targetIndex,
        clear,
        scanWidth
      )
    } else {
      IcacheAliasScanStatus(false.B, true.B)
    }

  def dcacheScan(
      active: Boolean,
      valid: Vec[Bool],
      tag: Vec[UInt],
      refillTrigger: Bool,
      refillTag: UInt,
      refillTarget: UInt,
      storeTrigger: Bool,
      storeTag: UInt,
      clear: Bool,
      scanWidth: Int,
      eventQueueDepth: Int =
        ysyx_26030103_KconfigDefaults.MmuCacheViptAliasEventQueueDepth
  ): DcacheAliasScanStatus =
    if (active) {
      chunkedEventQueueScan(
        valid,
        tag,
        refillTrigger,
        refillTag,
        refillTarget,
        storeTrigger,
        storeTag,
        clear,
        scanWidth,
        eventQueueDepth
      )
    } else {
      DcacheAliasScanStatus(false.B, false.B, false.B)
    }
}
