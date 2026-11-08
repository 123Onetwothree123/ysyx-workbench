import _root_.ysyx_26030103.config._
object ysyx_26030103_Elaborate extends App {
  private val targetDir = args(args.indexOf("--target-dir") + 1)
  private val configJsonPath = ysyx_26030103_JsonConfig.resolvePath()
  private val cfg = ysyx_26030103_JsonConfig.load(configJsonPath)
  println(s"[NPC config] source: $configJsonPath")
  private val settings = ysyx_26030103_ElaborateConfig.build(cfg)
  ysyx_26030103_ElaborateReport.print(settings.config)
  ysyx_26030103_ElaborateEmitter.emit(settings, targetDir)
}
