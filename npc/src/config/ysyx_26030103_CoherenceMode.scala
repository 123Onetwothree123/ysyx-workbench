package ysyx_26030103.config

// ICache 对自修改代码的可见性：fence.i 是标准语义，本开关只决定是否额外提供
// store snoop，不能把 FENCE_I 模式解释为“不需要 fence.i”。
sealed trait ysyx_26030103_ICacheCoherence {
  def Name: String
}

object ysyx_26030103_ICacheCoherence {
  // 标准语义：只有 `fence.i` 失效 ICache/取指流水，默认模式。
  case object FenceI extends ysyx_26030103_ICacheCoherence {
    val Name = "fence_i"
  }

  // 可选扩展：store B 完成时按物理地址失效对应 ICache 行；fence.i 仍保留，
  // 该模式只缩短旧行窗口，不能替代软件屏障。
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

// 入站 DMA 直接访问内存、不经过 DCache；这里描述 CPU 侧如何避免继续用旧行。
sealed trait ysyx_26030103_DMACoherence {
  def Name: String
}

object ysyx_26030103_DMACoherence {
  /** 现有默认行为：写直达 DCache 无脏数据；DMA 写 B 完成时全局失效
    * DCache。软件仍需在 DMA 完成后才访问目标区域，改代码后执行 fence.i。
    */
  case object SoftwareFlush extends ysyx_26030103_DMACoherence {
    val Name = "software_flush"
  }

  /** DMA 窗口在 CPU 侧按不可缓存访问：load/store/取指绕过 Cache 直接
    * 访问内存。DMA 传输在途时软件仍不得消费目标区域，但不再依赖
    * DCache 失效来观察已完成的 DMA 写。
    */
  case object Uncached extends ysyx_26030103_DMACoherence {
    val Name = "uncached"
  }

  /** DMA 地址级硬件 snoop：DMA 写的 B 到达后按本次 burst 覆盖的物理字节范围
    * 失效 ICache/DCache 匹配行；B 保持到两个 Cache 完成失效，因此软件看到 B
    * 时旧副本必然已失效。DMA 读不动作：写直达无脏数据，内存始终最新。
    */
  case object HardwareSnoop extends ysyx_26030103_DMACoherence {
    val Name = "hardware_snoop"
  }

  def FromString(value: String): ysyx_26030103_DMACoherence = value match {
    case "software_flush" => SoftwareFlush
    case "uncached"       => Uncached
    case "hardware_snoop" => HardwareSnoop
    case other =>
      throw new IllegalArgumentException(
        s"DMA_COHERENCE 只支持 software_flush/uncached/hardware_snoop，收到: $other"
      )
  }
}
