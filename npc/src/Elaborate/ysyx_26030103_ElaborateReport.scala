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
    config.PiptLineReport.foreach(report =>
      println(s"[NPC config] $report"))
    config.VIPTAliasReport.foreach(report =>
      println(s"[NPC config] $report"))
    println(s"[NPC config] ${config.ICacheCoherenceReport}")
    println(s"[NPC config] ${config.DMACoherenceReport}")
    if (config.SocXLENNeedsAXIBridge) {
      println(
        s"[NPC config] EffectiveSocXLEN=${config.EffectiveSocXLEN}: " +
          "顶层 master/slave AXI 使用 64 位地址/数据，跨 SoC 边界桥已启用；" +
          s"内部 fabric 使用 ${config.EffectiveInternalBusWidth} 位数据 beat。"
      )
    }
  }
}
