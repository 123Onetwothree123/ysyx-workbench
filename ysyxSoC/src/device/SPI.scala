package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class SPIIO(val ssWidth: Int = 8) extends Bundle {
  val sck = Output(Bool())
  val ss = Output(UInt(ssWidth.W))
  val mosi = Output(Bool())
  val miso = Input(Bool())
}

class spi_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in =
      Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val spi = new SPIIO
    val spi_irq_out = Output(Bool())
  })
}

class flash extends BlackBox {
  val io = IO(Flipped(new SPIIO(1)))
}

class APBSPI(
    address: Seq[AddressSet],
    val xlen: Int = Config.peripheralXlen("spi"),
    val spiBase: Long = Config.spiBase,
    val spiSize: Long = Config.spiSize,
    val flashBase: Long = Config.flashBase,
    val flashSize: Long = Config.flashSize,
    val fastFlash: Boolean = Config.fastFlashXip
)(implicit p: Parameters)
    extends LazyModule {
  private val busBytes = APBConfig.apbBytes
  private val coreBytes = 4 // opencores SPI 固定 32 位 Wishbone 接口
  APBConfig.checkXlen(xlen, "spi")

  val node = APBSlaveNode(
    Seq(
      APBSlavePortParameters(
        Seq(
          APBSlaveParameters(
            address = address,
            executable = true,
            supportsRead = true,
            supportsWrite = true
          )
        ),
        beatBytes = busBytes
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val spi_bundle = IO(new SPIIO)

    val mspi = Module(new spi_top_apb)
    mspi.io.clock := clock
    mspi.io.reset := reset
    // mspi.io.in <> in
    spi_bundle <> mspi.io.spi

    // SPI 内部始终按 32 位 APB 工作, 64 位总线从这里适配
    val apb = Wire(Flipped(new APBBundle(
      APBBundleParameters(addrBits = in.paddr.getWidth, dataBits = coreBytes * 8))))
    if (busBytes == coreBytes) {
      apb <> in
    } else {
      val bridge = Module(new APBWidthConverter(
        in.paddr.getWidth, busBytes * 8, coreBytes * 8,
        readFullWidth = true))
      bridge.io.clock := clock
      bridge.io.reset := reset
      bridge.io.in <> in
      apb <> bridge.io.out
    }
    // 区间比较显式按 APB 地址位宽扩展。原实现用 `paddr & ~MASK`
    // 判断，但 Chisel 的 `~` 保持字面量自身位宽（例如 0xfff 取反
    // 得到 12 位的 0），导致 32 位 paddr 与短掩码相与后恒为 0，
    // SPI 寄存器和 Flash XIP 都永远译码失败。这里用完整位宽比较，
    // 同时避免依赖掩码必须是 2 的幂。
    private def inRange(addr: UInt, base: Long, size: Long): Bool = {
      val width = addr.getWidth
      val limit = BigInt(1) << width
      require(base >= 0 && size > 0,
        s"地址区间必须非负且非空：base=$base size=$size")
      require(BigInt(base) + BigInt(size) <= limit,
        s"地址区间 [0x${base.toHexString}, 0x${(base + size).toHexString}) " +
          s"超出 $width 位 APB 地址空间")
      // 用闭区间上界比较：当区间恰好止于 2^width 时，end-1 仍能
      // 用 width 位表示，不能把 2^width 本身做成字面量。
      addr >= base.U(width.W) && addr <= (base + size - 1).U(width.W)
    }
    val IsSpiRegSpace = inRange(apb.paddr, spiBase, spiSize)
    val IsFlashSpace = inRange(apb.paddr, flashBase, flashSize)
    val XIPReq = apb.psel && !apb.penable && IsFlashSpace
    val XIPStates = Enum(10)
    val XIPIdle = XIPStates(0)
    val XIPDiv  = XIPStates(1)
    val XIPSs0  = XIPStates(2)
    val XIPSs1  = XIPStates(3)
    val XIPCfg  = XIPStates(4)
    val XIPTx   = XIPStates(5)
    val XIPGo   = XIPStates(6)
    val XIPPoll = XIPStates(7)
    val XIPRx   = XIPStates(8)
    val XIPDone = XIPStates(9)
    val XIPStep = RegInit(XIPIdle)
    val XIPActive = RegInit(false.B)
    val XIPPhase = RegInit(false.B) // 状态机枚举的子状态
    val XIPAddress = RegInit(0.U(32.W))
    val XIPRxData = RegInit(0.U(32.W))
    val XIPDoneReg = RegInit(false.B)

    // flash命令的24位地址反转
    val FlashWordAddr = Cat(XIPAddress(23, 2), 0.U(2.W))
    val XIPAddressBitRev = Reverse(FlashWordAddr)

    when(XIPReq && (!apb.pwrite)) {
      XIPActive := true.B
      XIPAddress := apb.paddr
      XIPStep := XIPDiv
      XIPPhase := false.B
      // 旧的读已经通过 XIPDoneReg 向 APB 返回 pready；新请求若和
      // Done 同拍到达，必须在启动新事务时立刻清掉旧 done，否则
      // 上游会把上一笔的 prdata 当成新事务的数据。
      XIPDoneReg := false.B
    }
    when(XIPActive && !XIPReq) {
      when(XIPStep === XIPDone) {
        // pready 已经在 Done 周期由 XIPDoneReg 拉高，这里只需要
        // 用一拍收尾，不能再等一次 SPI 子事务，否则会和新请求竞争。
        XIPActive := false.B
        XIPStep := XIPIdle
        XIPDoneReg := false.B
      }.otherwise {
        when(!XIPPhase) {
          XIPPhase := true.B
        }.elsewhen(mspi.io.in.pready) {
          XIPPhase := false.B
        }
        when(XIPPhase && mspi.io.in.pready) {
          when(XIPStep === XIPPoll) {
            when(!mspi.io.in.prdata(8)) {
              XIPStep := XIPRx
            }
          }.elsewhen(XIPStep === XIPRx) {
            XIPRxData := mspi.io.in.prdata
            XIPStep := XIPDone
            XIPDoneReg := true.B
          }.otherwise {
            XIPStep := XIPStep + 1.U
          }
        }
      }
    }
    val XIPPaddr = WireDefault(0.U(32.W))
    val XIPPwrite = WireDefault(false.B)
    val XIPPwdata = WireDefault(0.U(32.W))
    val XIPPsel = WireDefault(false.B)
    val XIPPenable = WireDefault(false.B)
    when(XIPActive) {
      XIPPsel := true.B
      XIPPenable := XIPPhase
      XIPPaddr := MuxLookup(XIPStep, 0.U)(
        Seq(
          XIPDiv -> 0x14.U,
          XIPSs0 -> 0x18.U,
          XIPSs1 -> 0x18.U,
          XIPCfg -> 0x10.U,
          XIPTx -> 0x00.U,
          XIPGo -> 0x10.U,
          XIPPoll -> 0x10.U,
          XIPRx -> 0x04.U
        )
      )
      XIPPwrite := MuxLookup(XIPStep, false.B)(
        Seq(
          XIPDiv -> true.B,
          XIPSs0 -> true.B,
          XIPSs1 -> true.B,
          XIPCfg -> true.B,
          XIPTx -> true.B,
          XIPGo -> true.B,
          XIPPoll -> false.B,
          XIPRx -> false.B
        )
      )
      XIPPwdata := MuxLookup(XIPStep, 0.U)(
        Seq(
          XIPDiv -> 1.U,
          XIPSs0 -> 0.U,
          XIPSs1 -> 1.U,
          XIPCfg -> "h00000C40".U,
          XIPTx -> Cat(XIPAddressBitRev, "hC0".U(8.W)),
          XIPGo -> "h00000D40".U
        )
      )
    }
    // 32位数据直接全反转+字节交换
    val RxReversed = Reverse(XIPRxData)
    val XIPResult =
      Cat(RxReversed(7, 0), RxReversed(15, 8), RxReversed(23, 16), RxReversed(31, 24))
    if (fastFlash) {
      // 仿真加速路径：spi_top_apb 的 FAST_FLASH 直接把 APB 读地址
      // 送给 flash_cmd/DPI，不再逐位跑 SPI 时序。默认关闭，真实 XIP
      // 路径仍走下面的 XIP 状态机。该模式只用于大批量 ACT 回归，
      // SPI 控制寄存器语义不参与此模式。
      mspi.io.in.paddr := apb.paddr
      mspi.io.in.pwrite := apb.pwrite
      mspi.io.in.pwdata := apb.pwdata
      mspi.io.in.pstrb := apb.pstrb
      mspi.io.in.pprot := apb.pprot
      mspi.io.in.psel := apb.psel
      mspi.io.in.penable := apb.penable
    } else {
      mspi.io.in.paddr := Mux(XIPActive, XIPPaddr, apb.paddr)
      mspi.io.in.pwrite := Mux(XIPActive, XIPPwrite, apb.pwrite)
      mspi.io.in.pwdata := Mux(XIPActive, XIPPwdata, apb.pwdata)
      mspi.io.in.pstrb := Mux(XIPActive, "hF".U, apb.pstrb)
      mspi.io.in.pprot := apb.pprot
      mspi.io.in.psel := Mux(XIPActive, XIPPsel, apb.psel && IsSpiRegSpace)
      mspi.io.in.penable := Mux(
        XIPActive,
        XIPPenable,
        apb.penable && IsSpiRegSpace
      )
    }
    val WriteError = IsFlashSpace && apb.psel && apb.pwrite
    val WriteErrorActive = RegInit(false.B)
    when(WriteError && !apb.penable) {
      WriteErrorActive := true.B
    }
    when(apb.pready) {
      WriteErrorActive := false.B
    }
    if (fastFlash) {
      apb.pready := Mux(WriteErrorActive, true.B, mspi.io.in.pready)
      apb.prdata := mspi.io.in.prdata
      apb.pslverr := Mux(WriteErrorActive, true.B, mspi.io.in.pslverr)
    } else {
      apb.pready := Mux(
        WriteErrorActive,
        true.B,
        Mux(XIPDoneReg, true.B, Mux(IsSpiRegSpace, mspi.io.in.pready, false.B))
      )
      apb.prdata := Mux(XIPDoneReg, XIPResult, mspi.io.in.prdata)
      apb.pslverr := Mux(WriteErrorActive, true.B, mspi.io.in.pslverr)
    }
  }
}
