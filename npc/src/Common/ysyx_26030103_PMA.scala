package ysyx_26030103.common

/** CPU 可见的物理地址区域。
  *
  * `Readable` 和 `Writable` 表示总线权限，`Executable` 控制指令取值权限。
  * 保持这些权限相互独立对 SoC MROM 之类的只读存储器十分重要：store 必须
  * 由 CPU 侧交叉开关拒绝，而不能发送到没有写通道的从设备。`Cacheable`
  * 表示取指是否允许分配一条 ICache 缓存行。
  */
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
    BigInt(Base) + BigInt(Size) <= (BigInt(1) << 32),
    "PMA区域必须位于32位物理地址空间内"
  )
  require(!Cacheable || Readable, "可缓存PMA区域必须可读")
  require(!Executable || Readable, "可执行PMA区域必须可读")

  final val EndExclusive: Long = Base + Size
}

/** 本核心支持的两种具体物理地址映射。 */
object ysyx_26030103_PhysicalMemoryMap {
  val CLINT = ysyx_26030103_PMARegion(0x02000000L, 0x00010000L)

  val NPC: Seq[ysyx_26030103_PMARegion] = Seq(
    CLINT,
    // 直接 NPC 的 UART 是 AXIRAM 中一个只写的单字寄存器。
    ysyx_26030103_PMARegion(
      0x10000000L,
      0x4L,
      Readable = false
    ),
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
      ), // SRAM（静态随机存取存储器）
      ysyx_26030103_PMARegion(0x10000000L, 0x00001000L), // UART（通用异步收发器）
      ysyx_26030103_PMARegion(0x10001000L, 0x00001000L), // SPI 控制器
      ysyx_26030103_PMARegion(0x10002000L, 0x00000010L), // GPIO（通用输入输出）
      ysyx_26030103_PMARegion(0x10011000L, 0x00000008L), // 键盘
      ysyx_26030103_PMARegion(0x21000000L, 0x00200000L), // VGA 帧缓冲区
      ysyx_26030103_PMARegion(
        0x30000000L,
        0x10000000L,
        Writable = false,
        Cacheable = true,
        Executable = true
      ), // MROM（掩模只读存储器）
      ysyx_26030103_PMARegion(
        0x80000000L,
        0x00400000L,
        Cacheable = true,
        Executable = true
      ), // PSRAM（伪静态随机存取存储器）
      ysyx_26030103_PMARegion(
        0xa0000000L,
        0x02000000L,
        Cacheable = true,
        Executable = true
      ) // SDRAM（同步动态随机存取存储器）
    )
    val ChipLink =
      if (HasChipLink)
        Seq(
          // ChipLink MMIO 被有意设置为不可执行且不可缓存。
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
