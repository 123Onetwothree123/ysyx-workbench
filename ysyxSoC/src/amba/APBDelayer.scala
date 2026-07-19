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

class APBDelayerChisel extends Module {
  val io = IO(new APBDelayerIO)
  io.out <> io.in
  val CPU_MHZ = 1200
  val DEVICE_MHZ = 100
  val S = 64
  val RTS = CPU_MHZ * S / DEVICE_MHZ // 因为r * S，整数截断
  val AMT = if (RTS > S) {
    RTS - S // (r-1) * S 的整数近似
  } else {
    0
  }
  if (AMT == 0) {
    // r <= 1, 无需延迟, 直通
    io.out <> io.in
  } else {
    val CounterWidth = 24
    val state_idle :: state_count :: state_delay :: Nil = Enum(3)
    val state = RegInit(state_idle)
    val counter = RegInit(0.S(CounterWidth.W))
    val rdata = RegInit(0.U(32.W))
    val slverr = RegInit(false.B)
    io.out.psel := io.in.psel
    io.out.penable := io.in.penable
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
        // 空闲时直通，用于检测事务开始
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
          // 事务异常中止
          state := state_idle
        }.elsewhen(io.out.pready && io.in.penable) {
          // 设备回复到达之后就捕获数据，进入排空阶段
          state := state_delay
          rdata := io.out.prdata
          slverr := io.out.pslverr
          // 本周期不再累加（设备已完成处理）
        }.otherwise {
          // 等待设备处理，每周期累加(r-1)*S
          counter := counter + AMT.S(CounterWidth.W)
        }
      }
      is(state_delay) {
        io.in.prdata := rdata
        io.in.pslverr := slverr
        when(counter <= 0.S) {
          // 排空完毕，向上游返回pready
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
      // val delayer = Module(new apb_delayer)
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
