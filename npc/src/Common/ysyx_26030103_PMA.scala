package ysyx_26030103.common

import chisel3._

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
  def Contains(address: BigInt): Boolean =
    BigInt(Base) <= address && address < BigInt(EndExclusive)
  def Covers(base: Long, size: Long): Boolean =
    BigInt(Base) <= BigInt(base) &&
      BigInt(EndExclusive) >= BigInt(base) + BigInt(size)
}
object ysyx_26030103_PMAAddress {
  def InRegion(
      address: UInt,
      region: ysyx_26030103_PMARegion,
      width: Int
  ): Bool = {
    val compareWidth = width + 1
    val extended = address.pad(compareWidth)
    extended >= region.Base.U(compareWidth.W) &&
    extended < region.EndExclusive.U(compareWidth.W)
  }
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

  def FromConfig(
      get: (String, Long) => Long,
      defaults: ysyx_26030103_PMAOptions
  ): ysyx_26030103_PMAOptions =
    ysyx_26030103_PMAOptions(
      CLINTBase = get("PMA.clint_base", defaults.CLINTBase),
      CLINTSize = get("PMA.clint_size", defaults.CLINTSize),
      sramBase = get("PMA.sram_base", defaults.sramBase),
      sramSize = get("PMA.sram_size", defaults.sramSize),
      UARTBase = get("PMA.uart_base", defaults.UARTBase),
      UARTSize = get("PMA.uart_size", defaults.UARTSize),
      SPIBase = get("PMA.spi_base", defaults.SPIBase),
      SPISize = get("PMA.spi_size", defaults.SPISize),
      GPIOBase = get("PMA.gpio_base", defaults.GPIOBase),
      GPIOSize = get("PMA.gpio_size", defaults.GPIOSize),
      keyboardBase = get("PMA.keyboard_base", defaults.keyboardBase),
      keyboardSize = get("PMA.keyboard_size", defaults.keyboardSize),
      VGABase = get("PMA.vga_base", defaults.VGABase),
      VGASize = get("PMA.vga_size", defaults.VGASize),
      mromBase = get("PMA.mrom_base", defaults.mromBase),
      mromSize = get("PMA.mrom_size", defaults.mromSize),
      flashBase = get("PMA.flash_base", defaults.flashBase),
      flashSize = get("PMA.flash_size", defaults.flashSize),
      psramBase = get("PMA.psram_base", defaults.psramBase),
      psramSize = get("PMA.psram_size", defaults.psramSize),
      sdramBase = get("PMA.sdram_base", defaults.sdramBase),
      sdramSize = get("PMA.sdram_size", defaults.sdramSize),
      ChipLinkMMIOBase = get("PMA.chiplink_mmio_base", defaults.ChipLinkMMIOBase),
      ChipLinkMMIOSize = get("PMA.chiplink_mmio_size", defaults.ChipLinkMMIOSize),
      chipLinkMemBase = get("PMA.chiplink_mem_base", defaults.chipLinkMemBase),
      chipLinkMemSize = get("PMA.chiplink_mem_size", defaults.chipLinkMemSize),
      SOCResetAddress = get("PMA.soc_reset_addr", defaults.SOCResetAddress),
      NpcRAMBase = defaults.NpcRAMBase
    )

  def ValidateNpcRam(
      base: Long,
      size: Long,
      sizePowerOfTwoMessage: => String,
      sizeAlignmentMessage: => String,
      baseMessage: => String,
      windowMessage: => String
  ): Unit = {
    ysyx_26030103_Validation.RequirePowerOfTwo(size, sizePowerOfTwoMessage)
    require(size % 4L == 0L, sizeAlignmentMessage)
    require(base >= 0L && base % 4L == 0L, baseMessage)
    require(
      BigInt(base) + BigInt(size) <= (BigInt(1) << 32),
      windowMessage
    )
  }
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
