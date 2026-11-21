package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._

/** AXI4 64 位到 32 位适配。
  *
  * 每个上游 beat 拆成单拍 4 字节事务；WSTRB 只覆盖部分字节时按真实字节
  * 地址发 AWSIZE=0 的字节事务。高地址和跨 4GiB burst 返回 DECERR。
  */
class AXI4Downsizer(
    upstreamAddrBits: Int,
    upstreamDataBits: Int,
    downstreamDataBits: Int = 32,
    ExtendAddressSpace: Boolean = true
)(implicit p: Parameters)
    extends LazyModule {

  require(upstreamAddrBits > 32, s"AXI4Downsizer 只用于宽地址端口，收到 $upstreamAddrBits")
  require(upstreamDataBits == 64, s"AXI4Downsizer 当前只支持 64 位上游数据，收到 $upstreamDataBits")
  require(downstreamDataBits == 32, s"AXI4Downsizer 当前只支持 32 位下游数据，收到 $downstreamDataBits")

  private val upstreamBytes = upstreamDataBits / 8

  val node = AXI4AdapterNode(
    masterFn = { mp =>
      mp.copy(masters = mp.masters.map { m =>
        m.copy(aligned = true, maxFlight = Some(1))
      })
    },
    slaveFn = { sp =>
      if (ExtendAddressSpace) {
        val fullSpace = AddressSet(0, (BigInt(1) << upstreamAddrBits) - 1)
        val first = sp.slaves.head
        sp.copy(
          slaves = Seq(
            first.copy(
              address = Seq(fullSpace),
              resources = Nil,
              executable = sp.slaves.exists(_.executable),
              supportsWrite = TransferSizes(1, upstreamBytes),
              supportsRead = TransferSizes(1, upstreamBytes),
              interleavedId = Some(0)
            )
          ),
          beatBytes = upstreamBytes
        )
      } else {
        sp.copy(
          beatBytes = upstreamBytes,
          slaves = sp.slaves.map(_.copy(
            supportsWrite = TransferSizes(1, upstreamBytes),
            supportsRead = TransferSizes(1, upstreamBytes)))
        )
      }
    }
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val (out, _) = node.out(0)

    private val uBits = upstreamDataBits
    private val DownstreamAddressWidth = 32
    private val uBytes = upstreamBytes

    /** 计算 AXI burst 中第 beat 拍的地址。 */
    def beatAddress(base: UInt, beat: UInt, size: UInt, burst: UInt, len: UInt): UInt = {
      val inc = base + (beat << size)
      val wrapMask = ((((len + 1.U) << size) - 1.U)).pad(base.getWidth)
      val wrapped = (base & (~wrapMask).asUInt) | (inc & wrapMask)
      Mux(
        burst === AXI4Parameters.BURST_WRAP,
        wrapped,
        Mux(burst === AXI4Parameters.BURST_FIXED, base, inc)
      )
    }

    /** 判断整笔 burst 是否越过低 4GiB。保守检查起始地址和结束地址。 */
    def outOfRange(addr: UInt, len: UInt, size: UInt): Bool = {
      val AddressBits = addr.getWidth
      val GuardBits = math.max(AddressBits, DownstreamAddressWidth)
      val totalBytes = (len + 1.U) << size
      val end = addr.pad(GuardBits + 1) +
        (totalBytes - 1.U).pad(GuardBits + 1)
      val highStart =
        if (AddressBits > DownstreamAddressWidth)
          addr(AddressBits - 1, DownstreamAddressWidth).orR
        else false.B
      val highEnd =
        if (GuardBits > DownstreamAddressWidth)
          end(GuardBits, DownstreamAddressWidth).orR
        else false.B
      highStart || highEnd
    }

    /** 32 位下游数据落到 64 位上游数据的哪个字节半区。 */
    def dataShift(size: UInt, beatAddr: UInt, chunk: UInt): UInt =
      Mux(size === 3.U, chunk << 5, Mux(beatAddr(2), 32.U, 0.U))

    /** 上游 WSTRB 的 8 个 bit 按 4 字节字对齐后，取低 4 位给下游。 */
    def strbShift(size: UInt, beatAddr: UInt, chunk: UInt): UInt =
      Mux(size === 3.U, chunk << 2, Mux(beatAddr(2), 4.U, 0.U))

    // ------------------------------------------------------------------
    // 读通道：AR -> 若干笔 4 字节 AR/R -> 合并成上游 R beat
    // ------------------------------------------------------------------
    val rIdle :: rSend :: rWait :: rOut :: Nil = Enum(4)
    val rState = RegInit(rIdle)
    val rId = Reg(chiselTypeOf(in.ar.bits.id))
    val rAddr = Reg(UInt(32.W))
    val rSize = Reg(UInt(3.W))
    val rLen = Reg(UInt(8.W))
    val rBurst = Reg(UInt(2.W))
    val rBeat = Reg(UInt(8.W))
    val rChunk = Reg(UInt(1.W))
    val rData = Reg(UInt(uBits.W))
    val rResp = Reg(UInt(AXI4Parameters.respBits.W))
    val rErr = RegInit(false.B)

    in.ar.ready := rState === rIdle
    val arError = outOfRange(in.ar.bits.addr, in.ar.bits.len, in.ar.bits.size)
    when(in.ar.fire) {
      rId := in.ar.bits.id
      rAddr := in.ar.bits.addr.pad(32)(31, 0)
      rSize := in.ar.bits.size
      rLen := in.ar.bits.len
      rBurst := in.ar.bits.burst
      rBeat := 0.U
      rChunk := 0.U
      rData := 0.U
      rResp := AXI4Parameters.RESP_OKAY
      rErr := arError
      rState := Mux(arError, rOut, rSend)
    }

    out.ar.valid := false.B
    out.ar.bits := DontCare
    out.r.ready := false.B
    in.r.valid := false.B
    in.r.bits := DontCare
    in.r.bits.id := rId
    in.r.bits.data := Mux(rErr, 0.U, rData)
    in.r.bits.resp := Mux(rErr, AXI4Parameters.RESP_DECERR, rResp)
    in.r.bits.last := rErr || (rBeat === rLen)

    when(rState === rSend) {
      val beatAddr = beatAddress(rAddr, rBeat, rSize, rBurst, rLen)
      val wordAddr = Mux(rSize === 3.U, beatAddr + (rChunk << 2), (beatAddr >> 2) << 2)
      out.ar.valid := true.B
      out.ar.bits.id := rId
      out.ar.bits.addr := wordAddr
      out.ar.bits.len := 0.U
      out.ar.bits.size := 2.U
      out.ar.bits.burst := AXI4Parameters.BURST_INCR
      out.ar.bits.lock := 0.U
      out.ar.bits.cache := 0.U
      out.ar.bits.prot := 0.U
      out.ar.bits.qos := 0.U
      when(out.ar.fire) { rState := rWait }
    }

    when(rState === rWait) {
      out.r.ready := true.B
      when(out.r.fire) {
        val beatAddr = beatAddress(rAddr, rBeat, rSize, rBurst, rLen)
        val shift = dataShift(rSize, beatAddr, rChunk)
        val shifted = (out.r.bits.data.pad(uBits) << shift)(uBits - 1, 0)
        rData := rData | shifted
        when(out.r.bits.resp =/= AXI4Parameters.RESP_OKAY) {
          rResp := out.r.bits.resp
        }
        when(rSize === 3.U && rChunk === 0.U) {
          rChunk := 1.U
          rState := rSend
        }.otherwise {
          rState := rOut
        }
      }
    }

    when(rState === rOut) {
      in.r.valid := true.B
      when(in.r.fire) {
        when(rErr || rBeat === rLen) {
          rState := rIdle
        }.otherwise {
          rBeat := rBeat + 1.U
          rChunk := 0.U
          rData := 0.U
          rResp := AXI4Parameters.RESP_OKAY
          rState := rSend
        }
      }
    }

    // ------------------------------------------------------------------
    // 写通道：AW+W -> 若干笔 4 字节 AW/W/B -> 合并成一个上游 B
    // ------------------------------------------------------------------
    val wIdle :: wWaitW :: wSend :: wWaitB :: wOut :: wDrain :: Nil = Enum(6)
    val wState = RegInit(wIdle)
    val awFull = RegInit(false.B)
    val wId = Reg(chiselTypeOf(in.aw.bits.id))
    val wAddr = Reg(UInt(32.W))
    val wSize = Reg(UInt(3.W))
    val wLen = Reg(UInt(8.W))
    val wBurst = Reg(UInt(2.W))
    val wBeat = Reg(UInt(8.W))
    val wChunk = Reg(UInt(1.W))
    // 当前 chunk 内已发送到的字节下标
    val DownstreamByteIndex = RegInit(0.U(2.W))
    val wResp = Reg(UInt(AXI4Parameters.respBits.W))
    val wErr = RegInit(false.B)
    val wHoldValid = RegInit(false.B)
    val wHoldData = Reg(UInt(uBits.W))
    val wHoldStrb = Reg(UInt(uBytes.W))
    val wHoldLast = RegInit(false.B)
    val awSent = RegInit(false.B)
    val wSent = RegInit(false.B)

    val awError = outOfRange(in.aw.bits.addr, in.aw.bits.len, in.aw.bits.size)
    in.aw.ready := wState === wIdle && !awFull
    when(in.aw.fire) {
      awFull := true.B
      wId := in.aw.bits.id
      wAddr := in.aw.bits.addr.pad(32)(31, 0)
      wSize := in.aw.bits.size
      wLen := in.aw.bits.len
      wBurst := in.aw.bits.burst
      wBeat := 0.U
      wChunk := 0.U
      DownstreamByteIndex := 0.U
      wResp := AXI4Parameters.RESP_OKAY
      wErr := awError
      wState := Mux(awError, wDrain, wWaitW)
    }

    // 高地址写仍需吃完整笔 W burst 再回 DECERR
    in.w.ready := (wState === wDrain && awFull) ||
      (wState === wWaitW && awFull && !wHoldValid)
    when(in.w.fire) {
      when(wState === wDrain) {
        when(in.w.bits.last) { wState := wOut }
      }.otherwise {
        wHoldValid := true.B
        wHoldData := in.w.bits.data
        wHoldStrb := in.w.bits.strb
        wHoldLast := in.w.bits.last
        awSent := false.B
        wSent := false.B
        wState := wSend
      }
    }

    out.aw.valid := false.B
    out.aw.bits := DontCare
    out.w.valid := false.B
    out.w.bits := DontCare
    out.b.ready := false.B
    in.b.valid := false.B
    in.b.bits := DontCare
    in.b.bits.id := wId
    in.b.bits.resp := Mux(wErr, AXI4Parameters.RESP_DECERR, wResp)

    def advanceWriteChunk(): Unit = {
      when(wSize === 3.U && wChunk === 0.U) {
        wChunk := 1.U
        DownstreamByteIndex := 0.U
        awSent := false.B
        wSent := false.B
        wState := wSend
      }.otherwise {
        when(wHoldLast) {
          wState := wOut
        }.otherwise {
          wBeat := wBeat + 1.U
          wChunk := 0.U
          DownstreamByteIndex := 0.U
          wHoldValid := false.B
          awSent := false.B
          wSent := false.B
          wState := wWaitW
        }
      }
    }

    when(wState === wSend) {
      val beatAddr = beatAddress(wAddr, wBeat, wSize, wBurst, wLen)
      val wordAddr = Mux(wSize === 3.U, beatAddr + (wChunk << 2), (beatAddr >> 2) << 2)
      val chunkData = Mux(
        wSize === 3.U,
        Mux(wChunk === 0.U, wHoldData(31, 0), wHoldData(63, 32)),
        Mux(beatAddr(2), wHoldData(63, 32), wHoldData(31, 0))
      )
      val ChunkStrobe = (wHoldStrb >> strbShift(wSize, beatAddr, wChunk))(3, 0)
      val FullWord = ChunkStrobe.andR
      val HasActiveByte = (ChunkStrobe >> DownstreamByteIndex).orR
      val ActiveByteIndex =
        DownstreamByteIndex + PriorityEncoder(ChunkStrobe >> DownstreamByteIndex)
      val TransactionValid = FullWord || HasActiveByte

      // 部分 WSTRB 按真实字节地址拆成单字节事务，lane 保持原位置
      val DownstreamAddress =
        Mux(FullWord, wordAddr, wordAddr + ActiveByteIndex)
      val DownstreamData = chunkData
      val DownstreamStrobe = Mux(
        FullWord,
        ChunkStrobe,
        (1.U(4.W) << ActiveByteIndex)(3, 0)
      )

      out.aw.valid := !awSent && TransactionValid
      out.aw.bits.id := wId
      out.aw.bits.addr := DownstreamAddress
      out.aw.bits.len := 0.U
      out.aw.bits.size := Mux(FullWord, 2.U(3.W), 0.U(3.W))
      out.aw.bits.burst := AXI4Parameters.BURST_INCR
      out.aw.bits.lock := 0.U
      out.aw.bits.cache := 0.U
      out.aw.bits.prot := 0.U
      out.aw.bits.qos := 0.U

      out.w.valid := !wSent && TransactionValid
      out.w.bits.data := DownstreamData
      out.w.bits.strb := DownstreamStrobe
      out.w.bits.last := true.B

      when(!TransactionValid) { advanceWriteChunk() }
    }

    when(out.aw.fire) { awSent := true.B }
    when(out.w.fire) { wSent := true.B }
    when(wState === wSend && (awSent || out.aw.fire) && (wSent || out.w.fire)) {
      awSent := false.B
      wSent := false.B
      val beatAddr = beatAddress(wAddr, wBeat, wSize, wBurst, wLen)
      val ChunkStrobe = (wHoldStrb >> strbShift(wSize, beatAddr, wChunk))(3, 0)
      when(!ChunkStrobe.andR) {
        val ActiveByteIndex =
          DownstreamByteIndex + PriorityEncoder(ChunkStrobe >> DownstreamByteIndex)
        DownstreamByteIndex := ActiveByteIndex
      }
      wState := wWaitB
    }

    when(wState === wWaitB) {
      out.b.ready := true.B
      when(out.b.fire) {
        when(out.b.bits.resp =/= AXI4Parameters.RESP_OKAY) {
          wResp := out.b.bits.resp
        }
        val beatAddr = beatAddress(wAddr, wBeat, wSize, wBurst, wLen)
        val ChunkStrobe = (wHoldStrb >> strbShift(wSize, beatAddr, wChunk))(3, 0)
        val FullWord = ChunkStrobe.andR
        val NextByteIndex = DownstreamByteIndex +& 1.U
        val HasRemainingByte = (ChunkStrobe >> NextByteIndex).orR
        when(FullWord) {
          advanceWriteChunk()
        }.otherwise {
          when(HasRemainingByte) {
            DownstreamByteIndex := NextByteIndex(1, 0)
            awSent := false.B
            wSent := false.B
            wState := wSend
          }.otherwise {
            advanceWriteChunk()
          }
        }
      }
    }

    when(wState === wOut) {
      in.b.valid := true.B
      when(in.b.fire) {
        awFull := false.B
        wHoldValid := false.B
        wErr := false.B
        wResp := AXI4Parameters.RESP_OKAY
        wState := wIdle
      }
    }

    // 上游只允许最大 8 字节 beat；SoC 的 CPU 端口不应出现更大的 size。
    assert(!in.ar.valid || in.ar.bits.size <= 3.U, "AXI4Downsizer: read size > 8 bytes")
    assert(!in.aw.valid || in.aw.bits.size <= 3.U, "AXI4Downsizer: write size > 8 bytes")
  }
}

object AXI4Downsizer {
  def apply(upstreamAddrBits: Int, upstreamDataBits: Int)(implicit p: Parameters): AXI4Node = {
    val bridge = LazyModule(new AXI4Downsizer(upstreamAddrBits, upstreamDataBits))
    bridge.node
  }
}
