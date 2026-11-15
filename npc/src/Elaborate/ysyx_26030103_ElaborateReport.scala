import _root_.ysyx_26030103.config._
import java.nio.file.Path
object ysyx_26030103_ElaborateReport {
  def print(config: ysyx_26030103_NPCConfig, source: Path): Unit =
    print(config, Some(source))

  private def print(
      config: ysyx_26030103_NPCConfig,
      source: Option[Path]
  ): Unit = {
    source.foreach(path => println(s"[NPC config] source: $path"))
    println(s"[NPC config] ${config.Describe}")
    if (config.PiptCacheLineExceedsPage) {
      println(
        "[NPC config] PIPT cache line 大于 MMU 页大小：" +
          "索引与 refill 使用完整物理地址，不按页内偏移截断；" +
          "VIPT 下该几何会在 elaboration 期被拒绝。"
      )
    }
    if (config.ICacheVIPTAliasHardware || config.DCacheVIPTAliasHardware) {
      val mode = config.MMU.cacheVIPTAliasScanMode
      val detail = mode match {
        case "parallel_n" =>
          s"ICache=${config.ICacheVIPTAliasScanWidth}/" +
            s"DCache=${config.DCacheVIPTAliasScanWidth} set/拍；"
        case "directory" =>
          s"目录项 ICache=${config.ICacheVIPTAliasDirectoryEntries}/" +
            s"DCache=${config.DCacheVIPTAliasDirectoryEntries}；"
        case "serial" => "serial 模式在扫描期间反压新请求；"
        case _ => ""
      }
      println(
        s"[NPC config] VIPT alias 维护已生成：scan_mode=$mode；$detail" +
          "refill/store/snoop 在旧副本清理完成前不交付新请求。"
      )
    }
    println(
      s"[NPC config] ICache 一致性: ${config.ICacheCoherence.Name}" +
        (if (config.ICacheCoherence ==
              ysyx_26030103_ICacheCoherence.HardwareSnoop) {
           if (config.ICacheEnable) "（store B 完成时失效对应行；仍需 fence.i）"
           else "（未生效：ICACHE=n）"
         } else "（标准语义：store 后需执行 fence.i）")
    )
    println(
      s"[NPC config] DMA 一致性: ${config.DMACoherence.Name}" +
        (if (config.DMACoherence == ysyx_26030103_DMACoherence.Uncached) {
           if (config.DMAWindowPresent)
             "（ChipLink DMA 窗口在 CPU 侧不可缓存，绕过 DCache/ICache）"
           else "（未生效：当前 PMA 没有可代理的 DMA 窗口，CHIPLINK=n？）"
         } else if (config.DMACoherence ==
                      ysyx_26030103_DMACoherence.HardwareSnoop) {
           if (!config.DMAWindowPresent)
             "（未生效：当前 PMA 没有可代理的 DMA 窗口，CHIPLINK=n？）"
           else if (!config.ICacheEnable && !config.DCacheEnable)
             "（未生效：ICACHE=n 且 DCACHE=n，没有可失效的缓存）"
           else
             "（DMA 写 B 到达后按物理字节范围失效对应行，B 等待失效完成；" +
               "DMA 读不产生动作，已取入流水线的旧指令仍需 fence.i）"
         } else "（保持 DMA 写 B 完成时失效 DCache；软件负责 DMA 完成顺序）")
    )
    if (config.SocXLENNeedsAXIBridge) {
      println(
        s"[NPC config] EffectiveSocXLEN=${config.EffectiveSocXLEN}: " +
          "顶层 master/slave AXI 使用 64 位地址/数据，跨 SoC 边界桥已启用；" +
          s"内部 fabric 使用 ${config.EffectiveInternalBusWidth} 位数据 beat。"
      )
    }
  }
}
