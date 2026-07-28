package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class APBDelayerIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())
  val in = Flipped(
    new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32))
  )
  val out = new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32))
}

class apb_delayer extends BlackBox {
  val io = IO(new APBDelayerIO)
}

class APBDelayerChisel(val CPU_MHZ: Int = sys.env.get("APB_CPU_FREQ_MHZ").map(_.toInt).getOrElse(450),
    val DEVICE_MHZ: Int = sys.env.get("APB_DEVICE_FREQ_MHZ").map(_.toInt).getOrElse(100),
    val S: Int = sys.env.get("APB_DELAY_SCALE_FACTOR").map(_.toInt).getOrElse(64)) extends Module {
  val io = IO(new APBDelayerIO)
  val RTS = CPU_MHZ * S / DEVICE_MHZ
  val AMT = if (RTS > S) {
    RTS - S
  } else {
    0
  }
  if (AMT == 0) {
    io.out <> io.in
  } else {
    val CounterWidth = 24
    val state_idle :: state_count :: state_delay :: Nil = Enum(3)
    val state = RegInit(state_idle)
    val counter = RegInit(0.S(CounterWidth.W))
    val rdata = RegInit(0.U(32.W))
    val slverr = RegInit(false.B)
    // 注意: 从设备视角, 事务在其pready返回时就已结束. 在state_delay(单纯拖延主机)期间
    // 必须撤销发往从设备的psel/penable, 否则带"ST_IDLE看到wb_valid就重新触发"逻辑的
    // 从设备(如EF_PSRAM_CTRL)会把同一事务重复执行多遍, 并在连续写时丢失事务
    io.out.psel := io.in.psel && (state =/= state_delay)
    io.out.penable := io.in.penable && (state =/= state_delay)
    io.out.pwrite := io.in.pwrite
    io.out.paddr := io.in.paddr
    io.out.pprot := io.in.pprot
    io.out.pwdata := io.in.pwdata
    io.out.pstrb := io.in.pstrb
    io.out.pauser := io.in.pauser
    io.in.pready := false.B
    io.in.prdata := 0.U
    io.in.pslverr := false.B
    io.in.pduser := io.out.pduser
    switch(state) {
      is(state_idle) {
        io.in.pready := io.out.pready
        io.in.prdata := io.out.prdata
        io.in.pslverr := io.out.pslverr
        when(io.in.psel && !io.in.penable) {
          state := state_count
          counter := AMT.S(CounterWidth.W)
        }
      }
      is(state_count) {
        when(!io.in.psel) {
          state := state_idle
        }.elsewhen(io.out.pready && io.in.penable) {
          state := state_delay
          rdata := io.out.prdata
          slverr := io.out.pslverr
        }.otherwise {
          counter := counter + AMT.S(CounterWidth.W)
        }
      }
      is(state_delay) {
        io.in.prdata := rdata
        io.in.pslverr := slverr
        when(counter <= 0.S) {
          state := state_idle
          io.in.pready := true.B
        }.otherwise {
          counter := counter - S.S
        }
      }
    }
  }
}

class APBDelayerWrapper(implicit p: Parameters) extends LazyModule {
  val node = APBIdentityNode()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val delayer = Module(new APBDelayerChisel)
      delayer.io.clock := clock
      delayer.io.reset := reset
      delayer.io.in <> in
      out <> delayer.io.out
    }
  }
}

object APBDelayer {
  def apply()(implicit p: Parameters): APBNode = {
    val apbdelay = LazyModule(new APBDelayerWrapper)
    apbdelay.node
  }
}
