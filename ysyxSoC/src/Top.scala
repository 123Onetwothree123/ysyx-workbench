package ysyx

import chisel3._
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import freechips.rocketchip.subsystem.WithEdgeDataBits
import freechips.rocketchip.diplomacy.LazyModule

class Edge64BitConfig extends Config(new WithEdgeDataBits(64) ++ new DefaultConfig)

object Config {
  def hasChipLink: Boolean =
    SoCConfig.boolean("SOC.chiplink", false)

  def sdramUseAXI: Boolean =
    SoCConfig.boolean("SOC.sdram_use_axi", true)

  lazy val socXlen: Int = {
    val value = SoCConfig.int("SOC.soc_xlen", 32)
    require(value == 32 || value == 64,
      s"SOC_XLEN 必须是 32 或 64，当前值 $value")
    value
  }

  def apbXlen: Int = SoCConfig.xlen("SOC.apb_xlen")
  def peripheralXlen(name: String): Int = SoCConfig.peripheralXlen(name)

  // 内存映射的唯一来源是 Kconfig 的 PMA_PSRAM_*/PMA_SDRAM_*。typed JSON
  // 的 PMA 字段已经包含这些值，SoC 的 AddressSet 直接读取，避免 RTL
  // 与 CPU PMA/DTB/C++ 模型各写一份地址。
  def psramBase: Long = SoCConfig.long("PMA.psram_base", 0x80000000L)
  def psramSize: Long = SoCConfig.long("PMA.psram_size", 0x00400000L)
  def sdramBase: Long = SoCConfig.long("PMA.sdram_base", 0xa0000000L)
  def sdramSize: Long = SoCConfig.long("PMA.sdram_size", 0x02000000L)
  def mromBase: Long = SoCConfig.long("PMA.mrom_base", 0x20000000L)
  def mromSize: Long = SoCConfig.long("PMA.mrom_size", 0x00001000L)
  def flashBase: Long = SoCConfig.long("PMA.flash_base", 0x30000000L)
  def flashSize: Long = SoCConfig.long("PMA.flash_size", 0x10000000L)
  def spiBase: Long = SoCConfig.long("PMA.spi_base", 0x10001000L)
  def spiSize: Long = SoCConfig.long("PMA.spi_size", 0x00001000L)

  def linuxMemoryMap: Boolean =
    SoCConfig.boolean("SOC.memory_map_linux", false)

  def fastFlashXip: Boolean =
    sys.env.get("SOC_FLASH_XIP_FAST").exists { value =>
      val normalized = value.trim.toLowerCase
      normalized == "1" || normalized == "y" || normalized == "yes" ||
        normalized == "true" || normalized == "on"
    }

  def cpuAXI4AddrBits: Int = socXlen
  def cpuAXI4DataBits: Int = socXlen

  def apbCpuFreqMhz: Int = SoCConfig.int("SOC.apb.cpu_freq_mhz", 450)
  def apbDeviceFreqMhz: Int = SoCConfig.int("SOC.apb.device_freq_mhz", 100)
  def apbDelayScale: Int = SoCConfig.int("SOC.apb.delay_scale", 64)
  def axiCpuFreqMhz: Int = SoCConfig.int("SOC.AXI.cpu_freq_mhz", 450)
  def axiDeviceFreqMhz: Int = SoCConfig.int("SOC.AXI.device_freq_mhz", 100)
  def axiDelayScale: Int = SoCConfig.int("SOC.AXI.delay_scale", 64)

  def source: String = SoCConfig.source
}

class ysyxSoCTop extends Module {
  private val rvConfig =
    if (Config.socXlen == 64) new Edge64BitConfig
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
      s"memory_map=${if (Config.linuxMemoryMap) "linux" else "act"} " +
      s"psram=0x${Config.psramBase.toHexString}+0x${Config.psramSize.toHexString} " +
      s"sdram=0x${Config.sdramBase.toHexString}+0x${Config.sdramSize.toHexString}, " +
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
