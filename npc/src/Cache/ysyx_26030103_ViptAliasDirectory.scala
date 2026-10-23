package ysyx_26030103.cache

import chisel3._
import chisel3.util._

/** VIPT alias 的“物理行 -> set”目录。
  *
  * Cache 为直接映射：每个 set 最多一行。目录项按 set 的低位分桶
  * （Entries 必须是 2 的幂且不超过 set 数）：
  *   - Entries == NumSets 时每个 set 独占一个桶，目录完整、无冲突，
  *     任何有效行都能被精确追踪；
  *   - Entries < NumSets 时多个 set 共享一个桶，安装新行会挤掉同桶
  *     另一个 set 的目录项。此时必须同时失效被挤出的 Cache 行，因此
  *     “有 valid 行必有目录项”的不变量始终成立；目录命中缺失就代表
  *     没有该物理行的有效副本。
  *
  * 目录只在事件拍（refill 安装、store 完成、ICache store snoop、DMA
  * 地址范围 snoop）做 CAM 比较，普通 load/fetch 命中路径完全不经过
  * 目录，因此面积/时序代价与访问频率解耦。目录更新和旧副本 valid
  * 清零在同一时钟沿生效；新行由 refill 最后一拍置 valid，因此不会
  * 出现两份可观测的有效副本。flush/fence/原子提交通过 clear 全量清空。
  */
class ysyx_26030103_ViptAliasDirectory(
    val LineBits: Int,
    val SetBits: Int,
    val NumSets: Int,
    val Entries: Int
) extends Module {
  require(LineBits >= 1, s"目录物理行号位宽非法: $LineBits")
  require(SetBits >= 1, s"目录 set 索引位宽非法: $SetBits")
  require(NumSets == (1 << SetBits),
    s"目录 NumSets($NumSets) 必须等于 2^SetBits($SetBits)")
  require(Entries >= 1 && Entries <= NumSets,
    s"目录项数($Entries) 必须在 1..NumSets($NumSets)")
  require((Entries & (Entries - 1)) == 0,
    s"目录项数($Entries) 必须是 2 的幂")

  val io = IO(new Bundle {
    /** 新物理行将安装到 installSet（refill AR 被接受）。 */
    val install = Input(Bool())
    val installLine = Input(UInt(LineBits.W))
    val installSet = Input(UInt(SetBits.W))
    /** 按物理行失效（DCache store / ICache snoop）。 */
    val invalidateLine = Input(Bool())
    val invalidateLineAddr = Input(UInt(LineBits.W))
    /** store 命中时保留目标 set 的那一份。 */
    val keepSet = Input(Bool())
    val keepSetIndex = Input(UInt(SetBits.W))
    /** 第二路 snoop 地址（8 字节 store 可能跨 cache line）。 */
    val invalidateLine2 = Input(Bool())
    val invalidateLine2Addr = Input(UInt(LineBits.W))
    /** DMA 地址级 snoop 的物理行范围（闭区间）。 */
    val invalidateRange = Input(Bool())
    val invalidateRangeStart = Input(UInt(LineBits.W))
    val invalidateRangeEnd = Input(UInt(LineBits.W))
    /** 上级 Cache 已自行失效某个 set（refill/store 错误等）。 */
    val release = Input(Bool())
    val releaseSet = Input(UInt(SetBits.W))
    /** flush/fence 清空目录。 */
    val clear = Input(Bool())
    /** 需要失效的 Cache set 位图，由上级 Cache 清零对应 valid。 */
    val invalidate = Output(Vec(NumSets, Bool()))
  })

  val dirValid = RegInit(VecInit(Seq.fill(Entries)(false.B)))
  val dirLine = Reg(Vec(Entries, UInt(LineBits.W)))
  val dirSet = Reg(Vec(Entries, UInt(SetBits.W)))
  val bucketBits = log2Ceil(Entries).max(1)
  private def Bucket(set: UInt): UInt =
    if (Entries == 1) 0.U(1.W) else set(bucketBits - 1, 0)

  val invalidate = WireInit(VecInit(Seq.fill(NumSets)(false.B)))
  io.invalidate := invalidate

  for (e <- 0 until Entries) {
    val Valid = dirValid(e)
    // refill 安装同一物理行：清掉其它 set 的旧副本。
    val installAliasKill = io.install && Valid &&
      dirLine(e) === io.installLine && dirSet(e) =/= io.installSet
    // 目标 set 的目录桶会被新行覆盖；桶里若住着另一个 set 的行，
    // 该行已经无处可追踪，必须一并失效（目录容量冲突）。
    val bucketKill = io.install && (e.U === Bucket(io.installSet)) &&
      Valid && dirSet(e) =/= io.installSet
    // store/snoop 按完整物理行比较；store 命中时保留目标 set。
    val lookupKill = io.invalidateLine && Valid &&
      dirLine(e) === io.invalidateLineAddr &&
      (!io.keepSet || dirSet(e) =/= io.keepSetIndex)
    val lookup2Kill = io.invalidateLine2 && Valid &&
      dirLine(e) === io.invalidateLine2Addr
    // DMA 写的 burst 范围：范围内所有物理行都不再允许命中。
    val rangeKill = io.invalidateRange && Valid &&
      dirLine(e) >= io.invalidateRangeStart &&
      dirLine(e) <= io.invalidateRangeEnd
    // 上级 Cache 自己清掉了该 set；目录项同步释放。
    val releaseKill = io.release && Valid && dirSet(e) === io.releaseSet

    when(
      installAliasKill || bucketKill || lookupKill || lookup2Kill ||
        rangeKill
    ) {
      invalidate(dirSet(e)) := true.B
    }
    when(
      installAliasKill || bucketKill || lookupKill || lookup2Kill ||
        rangeKill || releaseKill
    ) {
      dirValid(e) := false.B
    }
    // 安装写回放在失效之后：同一桶的旧行被挤出后写入新映射。
    // 同拍 DMA 范围命中该行时仍以安装为准：refill 的 AR 尚未被 snoop
    // 抑制时读到的是 DMA 完成后的数据，且 Cache valid 要等 RLAST 才置位。
    when(io.install && e.U === Bucket(io.installSet)) {
      dirValid(e) := true.B
      dirLine(e) := io.installLine
      dirSet(e) := io.installSet
    }
    when(io.clear) {
      dirValid(e) := false.B
    }
  }
}
