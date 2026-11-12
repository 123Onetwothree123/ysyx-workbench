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
    clintBase: Long,
    clintSize: Long,
    sramBase: Long,
    sramSize: Long,
    uartBase: Long,
    uartSize: Long,
    spiBase: Long,
    spiSize: Long,
    gpioBase: Long,
    gpioSize: Long,
    keyboardBase: Long,
    keyboardSize: Long,
    vgaBase: Long,
    vgaSize: Long,
    mromBase: Long,
    mromSize: Long,
    psramBase: Long,
    psramSize: Long,
    sdramBase: Long,
    sdramSize: Long,
    chipLinkMmioBase: Long,
    chipLinkMmioSize: Long,
    chipLinkMemBase: Long,
    chipLinkMemSize: Long,
    socResetAddr: Long,
    npcRamBase: Long
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
      npcRamBase,
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
object ysyx_26030103_PMAOptions {
  lazy val Default: ysyx_26030103_PMAOptions =
    ysyx_26030103_KconfigDefaults.Pma
}
object ysyx_26030103_PhysicalMemoryMap {
  val DefaultPlicBase: Long = ysyx_26030103_KconfigDefaults.PlicBase
  val DefaultPlicSize: Long = ysyx_26030103_KconfigDefaults.PlicSize
  val CLINT = ysyx_26030103_PMARegion(
    ysyx_26030103_PMAOptions.Default.clintBase,
    ysyx_26030103_PMAOptions.Default.clintSize
  )
  def NPCWithSize(
      ramBytes: Long,
      PlicBase: Long = DefaultPlicBase,
      PlicSize: Long = DefaultPlicSize
  ): Seq[ysyx_26030103_PMARegion] =
    ysyx_26030103_PMAOptions.Default.npcRegions(ramBytes, PlicBase, PlicSize)
  val NPC: Seq[ysyx_26030103_PMARegion] =
    NPCWithSize(ysyx_26030103_KconfigDefaults.NpcRamSize)
  def SoC(
      HasChipLink: Boolean,
      PlicBase: Long = DefaultPlicBase,
      PlicSize: Long = DefaultPlicSize
  ): Seq[ysyx_26030103_PMARegion] =
    ysyx_26030103_PMAOptions.Default.socRegions(HasChipLink, PlicBase, PlicSize)
}
