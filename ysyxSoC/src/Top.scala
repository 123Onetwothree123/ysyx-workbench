package ysyx

import chisel3._
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import freechips.rocketchip.diplomacy.LazyModule

object Config {
  def hasChipLink: Boolean =
    SoCConfig.boolean("soc.chiplink", false)

  def sdramUseAXI: Boolean =
    SoCConfig.boolean("soc.sdram_use_axi", true)

  lazy val socXlen: Int = {
    val value = SoCConfig.int("soc.soc_xlen", 32)
    require(value == 32 || value == 64,
      s"SOC_XLEN 必须是 32 或 64，当前值 $value")
    value
  }

  def cpuAXI4AddrBits: Int = socXlen
  def cpuAXI4DataBits: Int = socXlen

  def apbCpuFreqMhz: Int = SoCConfig.int("soc.apb.cpu_freq_mhz", 450)
  def apbDeviceFreqMhz: Int = SoCConfig.int("soc.apb.device_freq_mhz", 100)
  def apbDelayScale: Int = SoCConfig.int("soc.apb.delay_scale", 64)
  def axiCpuFreqMhz: Int = SoCConfig.int("soc.axi.cpu_freq_mhz", 450)
  def axiDeviceFreqMhz: Int = SoCConfig.int("soc.axi.device_freq_mhz", 100)
  def axiDelayScale: Int = SoCConfig.int("soc.axi.delay_scale", 64)

  def source: String = SoCConfig.source
}

class ysyxSoCTop extends Module {
  // Edge32BitConfig 固定内部 TileLink/AXI 互联为 32 位。64 位 CPU
  // 构建仍使用 32 位内部总线，只是 CPU 端口经由 AXI4Downsizer 连接。
  private val rvConfig =
    if (Config.socXlen == 64) new Edge32BitConfig
    else new Edge32BitConfig ++ new DefaultRV32Config
  implicit val config: Parameters = new Config(rvConfig)

  val io = IO(new Bundle { })
  val dut = LazyModule(new ysyxSoCFull)
  val mdut = Module(dut.module)
  mdut.dontTouchPorts()
  mdut.externalPins := DontCare
  mdut.debug_gpr_raddr := 0.U
  // ysyxSoCTop 只是图形/Verilog 导出壳，不接仿真调试读口；
  // 真正的调试/DiffTest 顶层 ysyxSoCFull 由 npc 直接实例化。
  mdut.debug_fpr_raddr := 0.U
}

object Elaborate extends App {
  println(
    s"[ysyxSoC] config source: ${Config.source}; " +
      s"soc_xlen=${Config.socXlen}, chiplink=${Config.hasChipLink}, " +
      s"sdram_axi=${Config.sdramUseAXI}, " +
      s"apb=${Config.apbCpuFreqMhz}/${Config.apbDeviceFreqMhz}MHz/S=${Config.apbDelayScale}, " +
      s"axi=${Config.axiCpuFreqMhz}/${Config.axiDeviceFreqMhz}MHz/S=${Config.axiDelayScale}"
  )
  if (Config.socXlen == 64) {
    println(
      "[ysyxSoC] soc_xlen=64: CPU AXI4 is 64-bit. Debug/trap ports expose " +
        "the historical 32-bit low words plus `_hi` words, so RV64 " +
        "PC/GPR/CSR/trap state is observable without truncation."
    )
  }
  val firtoolOptions = Array("--disable-annotation-unknown")
  circt.stage.ChiselStage.emitSystemVerilogFile(new ysyxSoCTop, args, firtoolOptions)
}
