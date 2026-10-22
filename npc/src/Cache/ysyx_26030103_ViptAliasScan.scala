package ysyx_26030103.cache

import chisel3._
import chisel3.util._

/** VIPT alias 失效扫描的公共实现。
  *
  * `all_sets` 保持原来的单拍全扫描：refill 安装或 store 完成时，用
  * NumSets 个比较器在同一拍失效所有其它 set 的同物理行副本。面积随
  * set 数线性增长，但 refill 不增加额外等待。
  *
  * `serial` 每个周期只扫描一个 set：只有一套 tag 比较器和一套 valid
  * 写译码，面积明显更小；代价是扫描期间反压新的 Cache 请求。扫描完成
  * 前绝不接受可能命中旧 alias 副本的请求，因此不会牺牲正确性。
  */
object ysyx_26030103_ViptAliasScan {
  val AllSets = "all_sets"
  val Serial = "serial"
  val Modes: Seq[String] = Seq(AllSets, Serial)

  // store 失效事件队列深度。DCache 的写直达 store 完成通知不能被反压，
  // 因此用一个小队列缓存“待扫描的物理行”；队列满时直接清空整个 Cache，
  // 用一次性能损失换取绝对正确，不生成额外的全扫描比较器阵列。
  private val EventQueueDepth = 4

  def requireMode(mode: String, where: String): Unit =
    require(
      Modes.contains(mode),
      s"$where 的 ViptAliasScanMode=$mode 非法，只支持 ${Modes.mkString("/")}"
    )

  def isSerial(mode: String): Boolean = mode == Serial

  /** ICache 的串行扫描：单 MSHR 结构保证同一时刻只有一个 refill 事件。
    *
    * 在 refill AR 握手时启动，照常清掉目标 set 的旧行，然后每周期扫描
    * 一个 set。返回 true 表示扫描/启动拍需要反压新的取指请求。
    */
  def serialRefillScan(
      valid: Vec[Bool],
      tag: Vec[UInt],
      trigger: Bool,
      triggerTag: UInt,
      targetIndex: UInt,
      clear: Bool
  ): Bool = {
    val numSets = valid.length
    require(numSets >= 2, "串行 alias 扫描至少需要 2 个 set")
    val idxWidth = log2Ceil(numSets).max(1)
    val tagWidth = tag.head.getWidth.max(1)
    val active = RegInit(false.B)
    val scanTag = Reg(UInt(tagWidth.W))
    val scanTarget = Reg(UInt(idxWidth.W))
    val scanIndex = RegInit(0.U(idxWidth.W))
    val lastSet = scanIndex === (numSets - 1).U

    when(active) {
      // 每个周期只读一个 set 的 valid/tag，也只写一个 valid 位。
      when(
        valid(scanIndex) && tag(scanIndex) === scanTag &&
          scanIndex =/= scanTarget
      ) {
        valid(scanIndex) := false.B
      }
      when(lastSet) {
        active := false.B
        scanIndex := 0.U
      }.otherwise {
        scanIndex := scanIndex + 1.U
      }
    }
    // 放在 active 之后：同一拍出现新事件时以新事件为准。ICache 只有
    // 一个在途 refill，正常时序下两者不会同时发生。
    when(trigger) {
      active := true.B
      scanTag := triggerTag
      scanTarget := targetIndex
      scanIndex := 0.U
      valid(targetIndex) := false.B
    }
    when(clear) {
      active := false.B
      scanIndex := 0.U
    }
    (active || trigger) && !clear
  }

  /** DCache 的串行扫描：refill 安装与写直达 store 完成都可能需要失效
    * 其它 set 的同物理行副本。
    *
    * refill 事件带目标 set（扫描时跳过刚安装的新行）；store 事件失效
    * 所有匹配副本。事件进入小队列后依次扫描，扫描期间反压新的 load。
    * 队列溢出时直接清空全部 valid，避免任何陈旧 alias 副本残留。
    */
  def serialEventQueueScan(
      valid: Vec[Bool],
      tag: Vec[UInt],
      refillTrigger: Bool,
      refillTag: UInt,
      refillTarget: UInt,
      storeTrigger: Bool,
      storeTag: UInt,
      clear: Bool
  ): Bool = {
    val numSets = valid.length
    require(numSets >= 2, "串行 alias 扫描至少需要 2 个 set")
    val idxWidth = log2Ceil(numSets).max(1)
    val tagWidth = tag.head.getWidth.max(1)
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
    val scanIndex = RegInit(0.U(idxWidth.W))

    val pushCount = refillTrigger.asUInt + storeTrigger.asUInt
    val scanning = count =/= 0.U
    val lastSet = scanIndex === (numSets - 1).U
    val pop = scanning && lastSet
    val available = depth.U + pop.asUInt - count
    val overflow = pushCount > available

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
        when(
          valid(scanIndex) && tag(scanIndex) === qTag(head) &&
            (!qTargetValid(head) || scanIndex =/= qTarget(head))
        ) {
          valid(scanIndex) := false.B
        }
        when(lastSet) {
          scanIndex := 0.U
        }.otherwise {
          scanIndex := scanIndex + 1.U
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
      scanIndex := 0.U
    }
    when(clear) {
      qValid.foreach(_ := false.B)
      head := 0.U
      tail := 0.U
      count := 0.U
      scanIndex := 0.U
    }
    (scanning || pushCount =/= 0.U) && !clear
  }
}
