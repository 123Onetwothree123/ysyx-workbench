package ysyx_26030103.config

/** ICache 对自修改代码的可见性策略。
  *
  * RISC-V 软件必须执行 `fence.i` 才能保证 store 之后的取指看到新指令；
  * 本开关只描述 `fence.i` 之外是否再提供硬件 store snoop，不能把
  * `FENCE_I` 模式下“无需 fence.i”当作标准语义。
  */
sealed trait ysyx_26030103_ICacheCoherence {
  def Name: String
}

object ysyx_26030103_ICacheCoherence {
  /** 标准语义：只有 `fence.i` 失效 ICache/取指流水，默认模式。 */
  case object FenceI extends ysyx_26030103_ICacheCoherence {
    val Name = "fence_i"
  }

  /** 可选扩展：CPU store B 完成时按物理地址失效对应 ICache 行。
    *
    * `fence.i` 仍然保留并继续冲刷取指流水；该模式只缩短“store 已完成、
    * 但软件尚未执行 fence.i”时 ICache 持有旧行的窗口，不能替代软件屏障。
    */
  case object HardwareSnoop extends ysyx_26030103_ICacheCoherence {
    val Name = "hardware_snoop"
  }

  def FromString(value: String): ysyx_26030103_ICacheCoherence = value match {
    case "fence_i"        => FenceI
    case "hardware_snoop" => HardwareSnoop
    case other =>
      throw new IllegalArgumentException(
        s"ICACHE_COHERENCE 只支持 fence_i/hardware_snoop，收到: $other"
      )
  }
}

/** DMA 与 CPU 数据缓存之间的一致性策略。
  *
  * 入站 DMA 经由仲裁器直接访问内存，本身不经过 DCache。这里描述的是
  * CPU 侧如何避免继续使用 DMA 修改前的旧缓存行。
  */
sealed trait ysyx_26030103_DmaCoherence {
  def Name: String
}

object ysyx_26030103_DmaCoherence {
  /** 现有默认行为：写直达 DCache 无脏数据；DMA 写 B 完成时全局失效
    * DCache。软件仍需在 DMA 完成后才访问目标区域，改代码后执行 fence.i。
    */
  case object SoftwareFlush extends ysyx_26030103_DmaCoherence {
    val Name = "software_flush"
  }

  /** DMA 窗口在 CPU 侧按不可缓存访问：load/store/取指绕过 Cache 直接
    * 访问内存。DMA 传输在途时软件仍不得消费目标区域，但不再依赖
    * DCache 失效来观察已完成的 DMA 写。
    */
  case object Uncached extends ysyx_26030103_DmaCoherence {
    val Name = "uncached"
  }

  def FromString(value: String): ysyx_26030103_DmaCoherence = value match {
    case "software_flush" => SoftwareFlush
    case "uncached"       => Uncached
    case "hardware_snoop" =>
      // 明确未实现，绝不注册成看似可用、实际不生效的 Kconfig 选项。
      throw new IllegalArgumentException(
        "DMA_COHERENCE=hardware_snoop 未实现：当前没有 DMA 地址级 " +
          "snoop/失效硬件。请选择 software_flush 或 uncached"
      )
    case other =>
      throw new IllegalArgumentException(
        s"DMA_COHERENCE 只支持 software_flush/uncached，收到: $other"
      )
  }
}
