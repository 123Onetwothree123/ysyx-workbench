package ysyx_26030103.common
case class ysyx_26030103_PMARegion(
    Base: Long,
    Size: Long,
    Readable: Boolean = true,
    Writable: Boolean = true,
    Cacheable: Boolean = false,
    Executable: Boolean = false
) {
  require(Base >= 0L, "PMA区域基地址必须非负")
  require(Size > 0L, "PMA区域大小必须为正")
  require(
    BigInt(Base) + BigInt(Size) <= (BigInt(1) << 63),
    "PMA区域必须位于可表示的64位物理地址空间内"
  )
  require(!Cacheable || Readable, "可缓存PMA区域必须可读")
  require(!Executable || Readable, "可执行PMA区域必须可读")
  final val EndExclusive: Long = Base + Size
}
final case class ysyx_26030103_PMAOptions(
    clintBase: Long = 0x02000000L,
    clintSize: Long = 0x00010000L,
    sramBase: Long = 0x0f000000L,
    sramSize: Long = 0x00008000L,
    uartBase: Long = 0x10000000L,
    uartSize: Long = 0x00001000L,
    spiBase: Long = 0x10001000L,
    spiSize: Long = 0x00001000L,
    gpioBase: Long = 0x10002000L,
    gpioSize: Long = 0x00000010L,
    keyboardBase: Long = 0x10011000L,
    keyboardSize: Long = 0x00000008L,
    vgaBase: Long = 0x21000000L,
    vgaSize: Long = 0x00200000L,
    mromBase: Long = 0x30000000L,
    mromSize: Long = 0x10000000L,
    psramBase: Long = 0x80000000L,
    psramSize: Long = 0x00400000L,
    sdramBase: Long = 0xa0000000L,
    sdramSize: Long = 0x02000000L,
    chipLinkMmioBase: Long = 0x40000000L,
    chipLinkMmioSize: Long = 0x40000000L,
    chipLinkMemBase: Long = 0xc0000000L,
    chipLinkMemSize: Long = 0x40000000L,
    socResetAddr: Long = 0x30000000L
) {
  def npcRegions(
      ramBytes: Long,
      plicBase: Long,
      plicSize: Long
  ): Seq[ysyx_26030103_PMARegion] = Seq(
    ysyx_26030103_PMARegion(clintBase, clintSize),
    ysyx_26030103_PMARegion(uartBase, uartSize),
    ysyx_26030103_PMARegion(plicBase, plicSize),
    ysyx_26030103_PMARegion(
      0x80000000L,
      ramBytes,
      Cacheable = true,
      Executable = true
    )
  )
  def socRegions(
      hasChipLink: Boolean,
      plicBase: Long,
      plicSize: Long
  ): Seq[ysyx_26030103_PMARegion] = {
    val local = Seq(
      ysyx_26030103_PMARegion(clintBase, clintSize),
      ysyx_26030103_PMARegion(plicBase, plicSize),
      ysyx_26030103_PMARegion(sramBase, sramSize, Cacheable = true, Executable = true),
      ysyx_26030103_PMARegion(uartBase, uartSize),
      ysyx_26030103_PMARegion(spiBase, spiSize),
      ysyx_26030103_PMARegion(gpioBase, gpioSize),
      ysyx_26030103_PMARegion(keyboardBase, keyboardSize),
      ysyx_26030103_PMARegion(vgaBase, vgaSize),
      ysyx_26030103_PMARegion(mromBase, mromSize, Writable = false, Cacheable = true, Executable = true),
      ysyx_26030103_PMARegion(psramBase, psramSize, Cacheable = true, Executable = true),
      ysyx_26030103_PMARegion(sdramBase, sdramSize, Cacheable = true, Executable = true)
    )
    val chipLink =
      if (hasChipLink)
        Seq(
          ysyx_26030103_PMARegion(chipLinkMmioBase, chipLinkMmioSize),
          ysyx_26030103_PMARegion(chipLinkMemBase, chipLinkMemSize, Cacheable = true, Executable = true)
        )
      else Seq.empty
    local ++ chipLink
  }
}
object ysyx_26030103_PhysicalMemoryMap {
  val CLINT = ysyx_26030103_PMARegion(0x02000000L, 0x00010000L)
  def NPCWithSize(
      ramBytes: Long,
      PlicBase: Long = 0x0c000000L,
      PlicSize: Long = 0x00300000L
  ): Seq[ysyx_26030103_PMARegion] =
    ysyx_26030103_PMAOptions().npcRegions(ramBytes, PlicBase, PlicSize)
  val NPC: Seq[ysyx_26030103_PMARegion] = NPCWithSize(0x04000000L)
  def SoC(
      HasChipLink: Boolean,
      PlicBase: Long = 0x0c000000L,
      PlicSize: Long = 0x00300000L
  ): Seq[ysyx_26030103_PMARegion] =
    ysyx_26030103_PMAOptions().socRegions(HasChipLink, PlicBase, PlicSize)
}
