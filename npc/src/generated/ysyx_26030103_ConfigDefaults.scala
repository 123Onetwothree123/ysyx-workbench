package ysyx_26030103.common

// 由 make npc-config 生成，不要手改。
object ysyx_26030103_KconfigDefaults {
  val PlicBase: Long = 0xc000000L
  val PlicSize: Long = 0x300000L
  val NpcRamBase: Long = 0x80000000L
  val NpcRamSize: Long = 0x4000000L
  val Pma: ysyx_26030103_PMAOptions = ysyx_26030103_PMAOptions(
    clintBase = 0x2000000L,
    clintSize = 0x10000L,
    sramBase = 0xf000000L,
    sramSize = 0x8000L,
    uartBase = 0x10000000L,
    uartSize = 0x1000L,
    spiBase = 0x10001000L,
    spiSize = 0x1000L,
    gpioBase = 0x10002000L,
    gpioSize = 0x10L,
    keyboardBase = 0x10011000L,
    keyboardSize = 0x8L,
    vgaBase = 0x21000000L,
    vgaSize = 0x200000L,
    mromBase = 0x30000000L,
    mromSize = 0x10000000L,
    psramBase = 0x80000000L,
    psramSize = 0x400000L,
    sdramBase = 0xa0000000L,
    sdramSize = 0x2000000L,
    chipLinkMmioBase = 0x40000000L,
    chipLinkMmioSize = 0x40000000L,
    chipLinkMemBase = 0xc0000000L,
    chipLinkMemSize = 0x40000000L,
    socResetAddr = 0x30000000L,
    npcRamBase = NpcRamBase
  )
}
