package ysyx_26030103.mem

import chisel3._
import chisel3.util._

/** LSU 非对齐/跨页访存的纯地址 helper。
  *
  * 普通 load 分段、store 写缓冲分段和分段写数据拼装共用同一套
  * “段地址 / 页 1 判定 / 页 1 物理地址”规则；这里只放组合函数，
  * 不持有状态，也不改变 MMIO、跨页或非对齐策略。
  */
private[mem] object ysyx_26030103_LSUSegment {
  /** 跨页访问的第二页虚拟地址：页号 +1，页内偏移清零。 */
  def Page1Va(va: UInt, addressWidth: Int): UInt =
    Cat(va(addressWidth - 1, 12) + 1.U, 0.U(12.W))

  /** 两个地址是否落在不同 4KiB 页。 */
  def CrossesPage(upper: UInt, base: UInt, addressWidth: Int): Bool =
    upper(addressWidth - 1, 12) =/= base(addressWidth - 1, 12)

  /** 第 SegmentIndex 个粒度块的虚拟地址。
    *
    * 段按配置粒度对齐后递增，而不是简单 VA+offset；这样每一段都能
    * 独立产生 PMA/MMU 标签，和非对齐分段访存的原有语义一致。
    */
  def SegmentAddress(
      baseVa: UInt,
      segmentIndex: UInt,
      segmentLog2: Int,
      addressWidth: Int
  ): UInt =
    Cat(baseVa(addressWidth - 1, segmentLog2), 0.U(segmentLog2.W)) +
      (segmentIndex << segmentLog2)

  /** 当前粒度块是否已经跨到第二页。 */
  def SegmentUsesPage1(
      segmentVa: UInt,
      baseVa: UInt,
      addressWidth: Int
  ): Bool =
    CrossesPage(segmentVa, baseVa, addressWidth)

  /** 第二页物理地址：有翻译结果用翻译结果，否则按连续物理页外推。 */
  def Page1Paddr(
      base: UInt,
      page1Pa: UInt,
      page1Valid: Bool,
      va: UInt,
      addressWidth: Int
  ): UInt = {
    val page1Base = Mux(
      page1Valid,
      page1Pa & ~"hfff".U(addressWidth.W),
      (base & ~"hfff".U(addressWidth.W)) + "h1000".U(addressWidth.W)
    )
    page1Base | va(11, 0)
  }

  /** 某个粒度段的物理地址：第一页直接用基页，第二页走 Page1Paddr。
    *
    * `crossPage` 是整笔访问是否跨页；只有跨页访问才可能落到第二页。
    * 写缓冲用它保持原实现的 `CrossPage && PageDiff` 判定。
    */
  def SegmentPaddr(
      base: UInt,
      page1Pa: UInt,
      page1Valid: Bool,
      segmentVa: UInt,
      baseVa: UInt,
      crossPage: Bool,
      addressWidth: Int
  ): UInt =
    Mux(
      crossPage && SegmentUsesPage1(segmentVa, baseVa, addressWidth),
      Page1Paddr(base, page1Pa, page1Valid, segmentVa, addressWidth),
      (base & ~"hfff".U(addressWidth.W)) | segmentVa(11, 0)
    )
}
