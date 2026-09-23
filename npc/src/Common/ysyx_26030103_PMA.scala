package ysyx_26030103.common

/** A physical-address region visible to the CPU.
  *
  * `Cacheable` describes whether an instruction fetch may allocate an ICache
  * line. `Executable` is an independent permission: MMIO remains reachable by
  * loads/stores, but an attempted fetch is rejected locally without touching
  * the device.
  */
case class ysyx_26030103_PMARegion(
    Base: Long,
    Size: Long,
    Cacheable: Boolean = false,
    Executable: Boolean = false
) {
  require(Base >= 0L, "PMA区域基地址必须非负")
  require(Size > 0L, "PMA区域大小必须为正")
  require(
    BigInt(Base) + BigInt(Size) <= (BigInt(1) << 32),
    "PMA区域必须位于32位物理地址空间内"
  )

  final val EndExclusive: Long = Base + Size
}

/** The two concrete physical maps supported by this core. */
object ysyx_26030103_PhysicalMemoryMap {
  val CLINT = ysyx_26030103_PMARegion(0x02000000L, 0x00010000L)

  val NPC: Seq[ysyx_26030103_PMARegion] = Seq(
    CLINT,
    // The direct-NPC UART is a single write-only word in AXIRAM.
    ysyx_26030103_PMARegion(0x10000000L, 0x4L),
    ysyx_26030103_PMARegion(
      0x80000000L,
      0x00040000L,
      Cacheable = true,
      Executable = true
    )
  )

  def SoC(HasChipLink: Boolean): Seq[ysyx_26030103_PMARegion] = {
    val Local = Seq(
      CLINT,
      ysyx_26030103_PMARegion(
        0x0f000000L,
        0x00008000L,
        Cacheable = true,
        Executable = true
      ), // SRAM
      ysyx_26030103_PMARegion(0x10000000L, 0x00001000L), // UART
      ysyx_26030103_PMARegion(0x10001000L, 0x00001000L), // SPI controller
      ysyx_26030103_PMARegion(0x10002000L, 0x00000010L), // GPIO
      ysyx_26030103_PMARegion(0x10011000L, 0x00000008L), // keyboard
      ysyx_26030103_PMARegion(0x21000000L, 0x00200000L), // VGA framebuffer
      ysyx_26030103_PMARegion(
        0x30000000L,
        0x10000000L,
        Cacheable = true,
        Executable = true
      ), // MROM
      ysyx_26030103_PMARegion(
        0x80000000L,
        0x00400000L,
        Cacheable = true,
        Executable = true
      ), // PSRAM
      ysyx_26030103_PMARegion(
        0xa0000000L,
        0x02000000L,
        Cacheable = true,
        Executable = true
      ) // SDRAM
    )
    val ChipLink =
      if (HasChipLink)
        Seq(
          // ChipLink MMIO is intentionally non-executable/non-cacheable.
          ysyx_26030103_PMARegion(0x40000000L, 0x40000000L),
          ysyx_26030103_PMARegion(
            0xc0000000L,
            0x40000000L,
            Cacheable = true,
            Executable = true
          )
        )
      else Seq.empty
    Local ++ ChipLink
  }
}
