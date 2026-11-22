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
    CLINTBase: Long,
    CLINTSize: Long,
    sramBase: Long,
    sramSize: Long,
    UARTBase: Long,
    UARTSize: Long,
    SPIBase: Long,
    SPISize: Long,
    GPIOBase: Long,
    GPIOSize: Long,
    keyboardBase: Long,
    keyboardSize: Long,
    VGABase: Long,
    VGASize: Long,
    mromBase: Long,
    mromSize: Long,
    flashBase: Long,
    flashSize: Long,
    psramBase: Long,
    psramSize: Long,
    sdramBase: Long,
    sdramSize: Long,
    ChipLinkMMIOBase: Long,
    ChipLinkMMIOSize: Long,
    chipLinkMemBase: Long,
    chipLinkMemSize: Long,
    SOCResetAddress: Long,
    NpcRAMBase: Long
) {
  def npcRegions( // NPC模式的
      RAMBytes: Long,
      PLICBase: Long,
      PLICSize: Long
  ): Seq[ysyx_26030103_PMARegion] = Seq(
    ysyx_26030103_PMARegion(CLINTBase, CLINTSize),
    ysyx_26030103_PMARegion(UARTBase, UARTSize),
    ysyx_26030103_PMARegion(PLICBase, PLICSize),
    ysyx_26030103_PMARegion(
      NpcRAMBase,
      RAMBytes,
      Cacheable = true,
      Executable = true
    )
  )
  def socRegions( // SOC模式的
      hasChipLink: Boolean,
      PLICBase: Long,
      PLICSize: Long
  ): Seq[ysyx_26030103_PMARegion] = {
    val local = Seq(
      ysyx_26030103_PMARegion(CLINTBase, CLINTSize),
      ysyx_26030103_PMARegion(PLICBase, PLICSize),
      ysyx_26030103_PMARegion(
        sramBase,
        sramSize,
        Cacheable = true,
        Executable = true
      ),
      ysyx_26030103_PMARegion(UARTBase, UARTSize),
      ysyx_26030103_PMARegion(SPIBase, SPISize),
      ysyx_26030103_PMARegion(GPIOBase, GPIOSize),
      ysyx_26030103_PMARegion(keyboardBase, keyboardSize),
      ysyx_26030103_PMARegion(VGABase, VGASize),
      ysyx_26030103_PMARegion(
        mromBase,
        mromSize,
        Writable = false,
        Cacheable = true,
        Executable = true
      ),
      ysyx_26030103_PMARegion(
        flashBase,
        flashSize,
        Writable = false,
        Cacheable = true,
        Executable = true
      ),
      ysyx_26030103_PMARegion(
        psramBase,
        psramSize,
        Cacheable = true,
        Executable = true
      ),
      ysyx_26030103_PMARegion(
        sdramBase,
        sdramSize,
        Cacheable = true,
        Executable = true
      )
    )
    val chipLink =
      if (hasChipLink)
        Seq(
          ysyx_26030103_PMARegion(ChipLinkMMIOBase, ChipLinkMMIOSize),
          ysyx_26030103_PMARegion(
            chipLinkMemBase,
            chipLinkMemSize,
            Cacheable = true,
            Executable = true
          )
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
  val DefaultPLICBase: Long = ysyx_26030103_KconfigDefaults.PLICBase
  val DefaultPLICSize: Long = ysyx_26030103_KconfigDefaults.PLICSize
  def NPCWithSize(
      RAMBytes: Long,
      PLICBase: Long = DefaultPLICBase,
      PLICSize: Long = DefaultPLICSize
  ): Seq[ysyx_26030103_PMARegion] =
    ysyx_26030103_PMAOptions.Default.npcRegions(RAMBytes, PLICBase, PLICSize)
  val NPC: Seq[ysyx_26030103_PMARegion] =
    NPCWithSize(ysyx_26030103_KconfigDefaults.NpcRamSize)
  def SoC(
      HasChipLink: Boolean,
      PLICBase: Long = DefaultPLICBase,
      PLICSize: Long = DefaultPLICSize
  ): Seq[ysyx_26030103_PMARegion] =
    ysyx_26030103_PMAOptions.Default.socRegions(HasChipLink, PLICBase, PLICSize)
}
