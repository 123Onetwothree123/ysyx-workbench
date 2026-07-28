package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class AXI4DelayerIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())
  val in = Flipped(
    new AXI4Bundle(
      AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4)
    )
  )
  val out = new AXI4Bundle(
    AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4)
  )
}

class axi4_delayer extends BlackBox {
  val io = IO(new AXI4DelayerIO)
}

class AXI4DelayerChisel(val CPU_MHZ: Int = sys.env.get("AXI_CPU_FREQ_MHZ").map(_.toInt).getOrElse(450),
    val DEVICE_MHZ: Int = sys.env.get("AXI_DEVICE_FREQ_MHZ").map(_.toInt).getOrElse(100),
    val S: Int = sys.env.get("AXI_DELAY_SCALE_FACTOR").map(_.toInt).getOrElse(64)) extends Module {
  val io = IO(new AXI4DelayerIO)

  val CounterWidth = 24
  val RTS = CPU_MHZ * S / DEVICE_MHZ
  val AMT = if (RTS > S) RTS - S else 0

  if (AMT == 0) {
    io.out <> io.in
  } else {
    val TargetWidth = CounterWidth + 10

    // ========== Read Channel ==========
    val rd_idle :: rd_active :: Nil = Enum(2)
    val rd_state = RegInit(rd_idle)
    val n_rd = RegInit(0.U(CounterWidth.W))

    val rd_arid = RegInit(0.U(4.W))

    val rd_fifo_wptr = RegInit(0.U(3.W))
    val rd_fifo_rptr = RegInit(0.U(3.W))
    val rd_fifo_count = RegInit(0.U(4.W))
    val rd_fifo_not_full = rd_fifo_count < 8.U
    val rd_fifo_not_empty = rd_fifo_count > 0.U

    val rd_fifo_data = Reg(Vec(8, UInt(32.W)))
    val rd_fifo_rid = Reg(Vec(8, UInt(4.W)))
    val rd_fifo_rresp = Reg(Vec(8, UInt(2.W)))
    val rd_fifo_rlast = Reg(Vec(8, Bool()))
    val rd_fifo_target = Reg(Vec(8, UInt(TargetWidth.W)))

    io.out.ar.valid := io.in.ar.valid
    io.in.ar.ready := io.out.ar.ready && (rd_state === rd_idle)
    io.out.ar.bits := io.in.ar.bits

    io.out.r.ready := false.B
    io.in.r.valid := false.B
    io.in.r.bits.id := 0.U
    io.in.r.bits.data := 0.U
    io.in.r.bits.resp := 0.U
    io.in.r.bits.last := false.B
    io.in.r.bits.user := DontCare
    io.in.r.bits.echo := DontCare

    switch(rd_state) {
      is(rd_idle) {
        when(io.in.ar.valid && io.in.ar.ready) {
          rd_state := rd_active
          n_rd := 1.U
          rd_arid := io.in.ar.bits.id
          rd_fifo_wptr := 0.U
          rd_fifo_rptr := 0.U
          rd_fifo_count := 0.U
        }
      }
      is(rd_active) {
        n_rd := n_rd + 1.U

        io.out.r.ready := rd_fifo_not_full
        // 注意: push(下游来拍)与pop(向CPU发拍)可能同拍发生,
        // count必须用加减合并, 两条独立的+1/-1赋值会被后者覆盖而丢表项
        val pushFire = io.out.r.valid && io.out.r.ready
        val popFire = WireDefault(false.B)
        when(pushFire) {
          rd_fifo_data(rd_fifo_wptr) := io.out.r.bits.data
          rd_fifo_rid(rd_fifo_wptr) := io.out.r.bits.id
          rd_fifo_rresp(rd_fifo_wptr) := io.out.r.bits.resp
          rd_fifo_rlast(rd_fifo_wptr) := io.out.r.bits.last
          rd_fifo_target(rd_fifo_wptr) := n_rd * RTS.U
          rd_fifo_wptr := rd_fifo_wptr + 1.U
        }

        val do_present = rd_fifo_not_empty && ((n_rd << 6) >= rd_fifo_target(rd_fifo_rptr))
        when(do_present) {
          io.in.r.valid := true.B
          io.in.r.bits.id := rd_fifo_rid(rd_fifo_rptr)
          io.in.r.bits.data := rd_fifo_data(rd_fifo_rptr)
          io.in.r.bits.resp := rd_fifo_rresp(rd_fifo_rptr)
          io.in.r.bits.last := rd_fifo_rlast(rd_fifo_rptr)
        }

        when(io.in.r.valid && io.in.r.ready) {
          popFire := true.B
          rd_fifo_rptr := rd_fifo_rptr + 1.U
          when(rd_fifo_rlast(rd_fifo_rptr)) {
            rd_state := rd_idle
          }
        }
        rd_fifo_count := rd_fifo_count + pushFire.asUInt - popFire.asUInt
      }
    }

    // ========== Write Channel ==========
    val wr_idle :: wr_aw_seen :: wr_w_hold :: wr_wait_b :: wr_b_hold :: Nil = Enum(5)
    val wr_state = RegInit(wr_idle)
    val n_wr = RegInit(0.U(CounterWidth.W))

    val w_arrival_n = RegInit(0.U(CounterWidth.W))
    val w_forwarded_n = RegInit(0.U(CounterWidth.W))
    val wdata_reg = RegInit(0.U(32.W))
    val wstrb_reg = RegInit(0.U(4.W))

    val b_id_reg = RegInit(0.U(4.W))
    val b_resp_reg = RegInit(0.U(2.W))
    val b_target = RegInit(0.U(TargetWidth.W))
    val b_latched = RegInit(false.B)

    io.out.aw.valid := io.in.aw.valid
    io.in.aw.ready := io.out.aw.ready && (wr_state === wr_idle)
    io.out.aw.bits := io.in.aw.bits

    io.out.w.valid := false.B
    io.out.w.bits.data := 0.U
    io.out.w.bits.strb := 0.U
    io.out.w.bits.last := false.B
    io.out.w.bits.user := DontCare
    io.in.w.ready := false.B

    io.out.b.ready := false.B
    io.in.b.valid := false.B
    io.in.b.bits.id := 0.U
    io.in.b.bits.resp := 0.U
    io.in.b.bits.user := DontCare
    io.in.b.bits.echo := DontCare

    switch(wr_state) {
      is(wr_idle) {
        when(io.in.aw.valid && io.in.aw.ready) {
          wr_state := wr_aw_seen
          n_wr := 1.U
          b_latched := false.B
        }
      }
      is(wr_aw_seen) {
        n_wr := n_wr + 1.U
        io.in.w.ready := true.B
        when(io.in.w.valid && io.in.w.ready) {
          wdata_reg := io.in.w.bits.data
          wstrb_reg := io.in.w.bits.strb
          w_arrival_n := n_wr
          wr_state := wr_w_hold
        }
      }
      is(wr_w_hold) {
        n_wr := n_wr + 1.U
        when((n_wr << 6) >= (w_arrival_n * RTS.U)) {
          io.out.w.valid := true.B
          io.out.w.bits.data := wdata_reg
          io.out.w.bits.strb := wstrb_reg
          io.out.w.bits.last := true.B
          when(io.out.w.ready) {
            w_forwarded_n := n_wr
            wr_state := wr_wait_b
          }
        }
      }
      is(wr_wait_b) {
        n_wr := n_wr + 1.U
        io.out.b.ready := !b_latched
        when(io.out.b.valid && io.out.b.ready) {
          b_id_reg := io.out.b.bits.id
          b_resp_reg := io.out.b.bits.resp
          b_latched := true.B
          val interval = n_wr - w_forwarded_n
          b_target := (w_forwarded_n << 6) + (interval * RTS.U)
        }
        when(b_latched) {
          wr_state := wr_b_hold
        }
      }
      is(wr_b_hold) {
        n_wr := n_wr + 1.U
        when((n_wr << 6) >= b_target) {
          io.in.b.valid := true.B
          io.in.b.bits.id := b_id_reg
          io.in.b.bits.resp := b_resp_reg
          when(io.in.b.ready) {
            wr_state := wr_idle
          }
        }
      }
    }
  }
}

class AXI4DelayerWrapper(implicit p: Parameters) extends LazyModule {
  val node = AXI4IdentityNode()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val delayer = Module(new AXI4DelayerChisel)
      delayer.io.clock := clock
      delayer.io.reset := reset
      delayer.io.in <> in
      out <> delayer.io.out
    }
  }
}

object AXI4Delayer {
  def apply()(implicit p: Parameters): AXI4Node = {
    val axi4delay = LazyModule(new AXI4DelayerWrapper)
    axi4delay.node
  }
}
