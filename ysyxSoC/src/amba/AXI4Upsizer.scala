package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._

/** AXI4 32 位到 64 位适配。
  *
  * AW/AR 形状保持不变，每个上游 beat 放到下游 64 位总线的对应半区
  */
class AXI4Upsizer(
    upstreamAddrBits: Int,
    upstreamDataBits: Int,
    downstreamDataBits: Int = 64
)(implicit p: Parameters)
    extends LazyModule {
  require(upstreamAddrBits == 32 || upstreamAddrBits == 64)
  require(upstreamDataBits == 32, s"AXI4Upsizer 上游必须是 32 位，收到 $upstreamDataBits")
  require(downstreamDataBits == 64,
    s"AXI4Upsizer 下游必须是 64 位，收到 $downstreamDataBits")

  private val upstreamBytes = upstreamDataBits / 8
  private val downstreamBytes = downstreamDataBits / 8

  val node = AXI4AdapterNode(
    masterFn = { m => m },
    slaveFn = { s => s.copy(beatBytes = upstreamBytes) }
  )

  lazy val module = new LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val (out, _) = node.out(0)

    def beatAddressOf(
        base: UInt,
        beat: UInt,
        size: UInt,
        burst: UInt,
        length: UInt
    ): UInt = {
      val width = math.max(upstreamAddrBits, 32) + 1
      val baseExt = base.pad(width)
      val sizeMask = ((1.U << size) - 1.U).pad(width)
      val alignedBase = (baseExt & (~sizeMask).asUInt)(width - 1, 0)
      val step = (beat.pad(width) << size)(width - 1, 0)
      val inc = (alignedBase + step)(width - 1, 0)
      val incAddress = Mux(beat === 0.U, baseExt, inc)
      val wrapMask =
        (((length + 1.U) << size) - 1.U).pad(width)
      val wrapped =
        ((baseExt & (~wrapMask).asUInt) | (inc & wrapMask))(width - 1, 0)
      Mux(
        burst === AXI4Parameters.BURST_WRAP,
        wrapped,
        Mux(burst === AXI4Parameters.BURST_FIXED, baseExt, incAddress)
      )
    }

    val idle :: sendAW :: sendW :: waitB :: Nil = Enum(4)
    val Idle = idle
    val SendAW = sendAW
    val SendW = sendW
    val WaitB = waitB
    val WriteState = RegInit(Idle)
    val WId = Reg(UInt(in.aw.bits.id.getWidth.W))
    val WAddress = Reg(UInt(upstreamAddrBits.W))
    val WLength = Reg(UInt(8.W))
    val WSize = Reg(UInt(3.W))
    val WBurst = Reg(UInt(2.W))
    val WBeat = Reg(UInt(8.W))

    in.aw.ready := WriteState === Idle
    out.aw.valid := WriteState === SendAW
    out.aw.bits := DontCare
    out.aw.bits.id := WId
    out.aw.bits.addr := WAddress.pad(out.aw.bits.addr.getWidth)
    out.aw.bits.len := WLength
    out.aw.bits.size := WSize
    out.aw.bits.burst := WBurst
    out.aw.bits.lock := 0.U
    out.aw.bits.cache := 0.U
    out.aw.bits.prot := 0.U
    out.aw.bits.qos := 0.U
    out.aw.bits.user := in.aw.bits.user
    out.aw.bits.echo := in.aw.bits.echo

    in.w.ready := WriteState === SendW && out.w.ready
    out.w.valid := WriteState === SendW && in.w.valid
    out.w.bits := DontCare
    val WriteBeatAddress =
      beatAddressOf(WAddress, WBeat, WSize, WBurst, WLength)
    val WriteLane = Mux(WriteBeatAddress(2), 4.U, 0.U)
    out.w.bits.data := (in.w.bits.data.pad(downstreamDataBits) <<
      (WriteLane << 3))(downstreamDataBits - 1, 0)
    out.w.bits.strb := (in.w.bits.strb.pad(downstreamBytes) << WriteLane)(
      downstreamBytes - 1, 0)
    out.w.bits.last := in.w.bits.last
    out.w.bits.user := in.w.bits.user

    out.b.ready := WriteState === WaitB
    in.b.valid := WriteState === WaitB && out.b.valid
    in.b.bits := out.b.bits

    when(WriteState === Idle) {
      when(in.aw.fire) {
        WId := in.aw.bits.id
        WAddress := in.aw.bits.addr(upstreamAddrBits - 1, 0)
        WLength := in.aw.bits.len
        WSize := in.aw.bits.size
        WBurst := in.aw.bits.burst
        WBeat := 0.U
        WriteState := SendAW
      }
    }.elsewhen(WriteState === SendAW) {
      when(out.aw.fire) {
        WriteState := SendW
      }
    }.elsewhen(WriteState === SendW) {
      when(in.w.fire) {
        when(in.w.bits.last) {
          WriteState := WaitB
        }.otherwise {
          WBeat := WBeat + 1.U
        }
      }
    }.elsewhen(WriteState === WaitB) {
      when(in.b.fire) {
        WriteState := Idle
      }
    }

    val rIdle :: sendAR :: receiveR :: Nil = Enum(3)
    val RIdle = rIdle
    val SendAR = sendAR
    val ReceiveR = receiveR
    val ReadState = RegInit(RIdle)
    val RId = Reg(UInt(in.ar.bits.id.getWidth.W))
    val RAddress = Reg(UInt(upstreamAddrBits.W))
    val RLength = Reg(UInt(8.W))
    val RSize = Reg(UInt(3.W))
    val RBurst = Reg(UInt(2.W))
    val RBeat = Reg(UInt(8.W))

    in.ar.ready := ReadState === RIdle
    out.ar.valid := ReadState === SendAR
    out.ar.bits := DontCare
    out.ar.bits.id := RId
    out.ar.bits.addr := RAddress.pad(out.ar.bits.addr.getWidth)
    out.ar.bits.len := RLength
    out.ar.bits.size := RSize
    out.ar.bits.burst := RBurst
    out.ar.bits.lock := 0.U
    out.ar.bits.cache := 0.U
    out.ar.bits.prot := 0.U
    out.ar.bits.qos := 0.U
    out.ar.bits.user := in.ar.bits.user
    out.ar.bits.echo := in.ar.bits.echo

    val ReadBeatAddress =
      beatAddressOf(RAddress, RBeat, RSize, RBurst, RLength)
    val ReadLane = Mux(ReadBeatAddress(2), 4.U, 0.U)
    out.r.ready := ReadState === ReceiveR && in.r.ready
    in.r.valid := ReadState === ReceiveR && out.r.valid
    in.r.bits := out.r.bits
    in.r.bits.data := (out.r.bits.data.pad(downstreamDataBits) >>
      (ReadLane << 3))(upstreamDataBits - 1, 0)

    when(ReadState === RIdle) {
      when(in.ar.fire) {
        RId := in.ar.bits.id
        RAddress := in.ar.bits.addr(upstreamAddrBits - 1, 0)
        RLength := in.ar.bits.len
        RSize := in.ar.bits.size
        RBurst := in.ar.bits.burst
        RBeat := 0.U
        ReadState := SendAR
      }
    }.elsewhen(ReadState === SendAR) {
      when(out.ar.fire) {
        ReadState := ReceiveR
      }
    }.elsewhen(ReadState === ReceiveR) {
      when(out.r.fire) {
        when(out.r.bits.last) {
          ReadState := RIdle
        }.otherwise {
          RBeat := RBeat + 1.U
        }
      }
    }

    assert(!in.aw.valid || in.aw.bits.size <= 2.U,
      "AXI4Upsizer: upstream AWSIZE must be <= 4 bytes")
    assert(!in.ar.valid || in.ar.bits.size <= 2.U,
      "AXI4Upsizer: upstream ARSIZE must be <= 4 bytes")
  }
}

object AXI4Upsizer {
  def apply(upstreamAddrBits: Int, upstreamDataBits: Int)(implicit p: Parameters): AXI4Node = {
    val bridge = LazyModule(new AXI4Upsizer(upstreamAddrBits, upstreamDataBits))
    bridge.node
  }
}
