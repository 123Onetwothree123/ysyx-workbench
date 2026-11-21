package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

case class AXI4ToAPBNode(axiBytes: Int = 4)(implicit valName: ValName)
    extends MixedAdapterNode(AXI4Imp, APBImp)(
  dFn = { mp =>
    APBMasterPortParameters(
      masters = mp.masters.map { m => APBMasterParameters(name = m.name, nodePath = m.nodePath) },
      requestFields = mp.requestFields.filter(!_.isInstanceOf[AMBAProtField]),
      responseKeys  = mp.responseKeys
    )
  },
  uFn = { sp =>
    require(axiBytes == 4 || axiBytes == 8,
      s"AXI4ToAPB 的 AXI 宽度必须是 32 或 64，当前 $axiBytes 字节")
    AXI4SlavePortParameters(
    slaves = sp.slaves.map { s =>
      AXI4SlaveParameters(
        address       = s.address,
        resources     = s.resources,
        regionType    = s.regionType,
        executable    = s.executable,
        nodePath      = s.nodePath,
        supportsWrite = if (s.supportsWrite) TransferSizes(1, axiBytes) else TransferSizes.none,
        supportsRead  = if (s.supportsRead)  TransferSizes(1, axiBytes) else TransferSizes.none,
        interleavedId = Some(0))}, // never interleaves D beats
    beatBytes = axiBytes,
    responseFields = sp.responseFields,
    requestKeys    = sp.requestKeys.filter(_ != AMBAProt))
  }
)

class AXI4ToAPB(val axiBytes: Int = 4, val aFlow: Boolean = true)(implicit p: Parameters)
    extends LazyModule {
  require(axiBytes == 4 || axiBytes == 8,
    s"AXI4ToAPB 的 AXI 宽度必须是 32 或 64，当前 $axiBytes 字节")
  val node = AXI4ToAPBNode(axiBytes)

  lazy val module = new LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val axiDataBits = edgeIn.bundle.dataBits
      val apbDataBits = edgeOut.bundle.dataBits
      val apbBytes = apbDataBits / 8
      require(axiDataBits == axiBytes * 8,
        s"AXI4ToAPB 实际 AXI 宽度 $axiDataBits 与参数 ${axiBytes * 8} 不一致")
      require(apbBytes == 4 || apbBytes == 8,
        s"APB 数据宽度必须是 32 或 64，当前 $apbDataBits")

      def fitAddr(value: UInt): UInt =
        if (value.getWidth == out.paddr.getWidth) value
        else if (value.getWidth < out.paddr.getWidth) value.pad(out.paddr.getWidth)
        else value(out.paddr.getWidth - 1, 0)

      def alignDown(value: UInt, bytes: Int): UInt =
        (value >> log2Ceil(bytes)) << log2Ceil(bytes)

      val (ar, r, aw, w, b) = (in.ar, in.r, in.aw, in.w, in.b)

      // s_setup 只用于拆分成多笔 APB 后的后续事务
      val s_idle :: s_setup :: s_inflight :: s_wait_rready_bready :: Nil = Enum(4)
      val state = RegInit(s_idle)
      val accept_read = (state === s_idle) && ar.valid
      val accept_write = !accept_read && (state === s_idle) && aw.valid && w.valid
      val is_write = accept_write holdUnless (state === s_idle)

      // burst 仍不支持；AXI 侧宽度由参数决定
      assert(!(ar.valid && ar.bits.len =/= 0.U))
      assert(!(aw.valid && aw.bits.len =/= 0.U))
      assert(!(ar.valid && ar.bits.size > log2Ceil(axiBytes).U))
      assert(!(aw.valid && aw.bits.size > log2Ceil(axiBytes).U))

      val rid_reg    = RegEnable(ar.bits.id, accept_read)
      val bid_reg    = RegEnable(aw.bits.id, accept_write)
      val araddr_reg = ar.bits.addr holdUnless accept_read
      val arsize_reg = ar.bits.size holdUnless accept_read
      val awaddr_reg = aw.bits.addr holdUnless accept_write
      val wdata_reg  =  w.bits.data holdUnless accept_write
      val wstrb_reg  =  w.bits.strb holdUnless accept_write

      // ReadBeat / ReadLow 只在 64->32 读路径使用
      val ReadBeat = RegInit(0.U(1.W))
      val ReadLow = RegInit(0.U(32.W))

      // 写拆分状态：部分 WSTRB 逐字节，整字按 APB 宽度分块
      val WriteStrobeLeft = RegInit(0.U(axiBytes.W))
      val WriteChunkIndex = RegInit(0.U(1.W))
      val writeActiveStrobe = Mux(state === s_idle, wstrb_reg, WriteStrobeLeft)
      val writeActiveChunk = Mux(state === s_idle, 0.U(1.W), WriteChunkIndex)
      val writeFullWord = writeActiveStrobe.andR
      val writeByteIdx = PriorityEncoder(writeActiveStrobe)
      val writeClearMask =
        (1.U(axiBytes.W) << writeByteIdx)(axiBytes - 1, 0)
      val writeNextStrobe = WriteStrobeLeft & ~writeClearMask
      val writeByteFinal = writeNextStrobe === 0.U
      val axiWriteBase = alignDown(awaddr_reg, axiBytes)

      val writeBeatFinal =
        if (axiBytes == 4 && apbBytes == 8) true.B
        else if (axiBytes == 8 && apbBytes == 4)
          Mux(writeFullWord, writeActiveChunk === 1.U, writeByteFinal)
        else if (axiBytes == apbBytes)
          Mux(writeFullWord, true.B, writeByteFinal)
        else true.B

      val writeAddr =
        if (axiBytes == 4 && apbBytes == 8) awaddr_reg
        else if (axiBytes == 8 && apbBytes == 4)
          Mux(writeFullWord,
            axiWriteBase + (writeActiveChunk << 2),
            axiWriteBase + writeByteIdx)
        else if (axiBytes == apbBytes)
          Mux(writeFullWord, axiWriteBase, axiWriteBase + writeByteIdx)
        else awaddr_reg

      val writeStrobe =
        if (axiBytes == 4 && apbBytes == 8)
          (wstrb_reg.pad(8) << Mux(awaddr_reg(2), 4.U, 0.U))(7, 0)
        else if (axiBytes == 8 && apbBytes == 4)
          Mux(writeFullWord, "hF".U(4.W),
            (1.U(4.W) << writeByteIdx(1, 0))(3, 0))
        else if (axiBytes == apbBytes)
          Mux(writeFullWord, ((1 << apbBytes) - 1).U(apbBytes.W),
            (1.U(apbBytes.W) << writeByteIdx)(apbBytes - 1, 0))
        else 0.U(apbBytes.W)

      val writeData =
        if (axiBytes == 4 && apbBytes == 8)
          (wdata_reg.pad(64) << Mux(awaddr_reg(2), 32.U, 0.U))(63, 0)
        else if (axiBytes == 8 && apbBytes == 4)
          Mux(writeFullWord,
            Mux(writeActiveChunk === 0.U, wdata_reg(31, 0), wdata_reg(63, 32)),
            Mux(writeByteIdx(2), wdata_reg(63, 32), wdata_reg(31, 0)))
        else if (axiBytes == apbBytes) wdata_reg
        else 0.U(apbDataBits.W)

      // 读拆分：64 位 AXI + 32 位 APB 时按 32 位半区读，最多两笔
      val readBase8 = alignDown(araddr_reg, 8)
      val readTwoBeats =
        if (axiBytes == 8 && apbBytes == 4) arsize_reg === 3.U else false.B
      val readActiveBeat = Mux(state === s_idle, 0.U(1.W), ReadBeat)
      val readBeatFinal =
        if (axiBytes == 8 && apbBytes == 4)
          !(readTwoBeats && readActiveBeat === 0.U)
        else true.B
      val readAddr =
        if (axiBytes == 8 && apbBytes == 4)
          Mux(readTwoBeats,
            Mux(readActiveBeat === 0.U, readBase8, readBase8 + 4.U),
            araddr_reg)
        else araddr_reg

      val apbAddr = fitAddr(Mux(is_write, writeAddr, readAddr))
      val apbWriteStrobe =
        Mux(writeActiveStrobe === 0.U, 0.U(apbBytes.W), writeStrobe)

      out.psel    := (accept_read || accept_write) ||
        state === s_setup || state === s_inflight
      out.penable := state === s_inflight
      out.pwrite  := is_write
      out.paddr   := apbAddr
      out.pprot   := APBParameters.PROT_DEFAULT
      out.pwdata  := Mux(is_write, writeData, 0.U(apbDataBits.W))
      out.pstrb   := Mux(is_write, apbWriteStrobe, 0.U(apbBytes.W))

      ar.ready := accept_read
      w.ready  := accept_write
      aw.ready := accept_write

      val resp = Mux(out.pslverr, AXI4Parameters.RESP_SLVERR, AXI4Parameters.RESP_OKAY)
      val resp_hold = resp holdUnless (state === s_inflight)
      val apbRData =
        if (axiBytes == 8 && apbBytes == 4) {
          Mux(readTwoBeats,
            Cat(out.prdata, ReadLow),
            Mux(araddr_reg(2),
              (out.prdata.pad(64) << 32)(63, 0), out.prdata.pad(64)))
        } else if (axiBytes == 8 && apbBytes == 8) {
          out.prdata
        } else if (axiBytes == 4 && apbBytes == 8) {
          Mux(araddr_reg(2), out.prdata(63, 32), out.prdata(31, 0))
        } else {
          out.prdata
        }
      r.valid  := !is_write && (((state === s_inflight) && out.pready && readBeatFinal) ||
        (state === s_wait_rready_bready))
      r.bits.data := (apbRData holdUnless (state === s_inflight)).pad(axiDataBits)
      r.bits.id   := rid_reg
      r.bits.resp := resp_hold
      r.bits.last := true.B

      b.valid  := is_write && (((state === s_inflight) && out.pready && writeBeatFinal) ||
        (state === s_wait_rready_bready))
      b.bits.resp := resp_hold
      b.bits.id   := bid_reg

      switch(state) {
        is(s_idle) {
          state := Mux(ar.valid || (aw.valid && w.valid), s_inflight, s_idle)
          when(accept_write) {
            WriteStrobeLeft := wstrb_reg
            WriteChunkIndex := 0.U
          }
          when(accept_read) {
            ReadBeat := 0.U
            ReadLow := 0.U
          }
        }
        is(s_setup) {
          state := s_inflight
        }
        is(s_inflight) {
          when(out.pready) {
            when(is_write) {
              when(!writeBeatFinal) {
                if (axiBytes == 8 && apbBytes == 4) {
                  when(writeFullWord) {
                    WriteChunkIndex := 1.U
                  }.otherwise {
                    WriteStrobeLeft := writeNextStrobe
                  }
                } else if (axiBytes == apbBytes) {
                  WriteStrobeLeft := writeNextStrobe
                }
                state := s_setup
              }.otherwise {
                state := Mux(b.fire, s_idle, s_wait_rready_bready)
              }
            }.otherwise {
              when(!readBeatFinal) {
                ReadLow := out.prdata(31, 0)
                ReadBeat := 1.U
                state := s_setup
              }.otherwise {
                state := Mux(r.fire, s_idle, s_wait_rready_bready)
              }
            }
          }
        }
        is(s_wait_rready_bready) {
          state := Mux(r.fire || b.fire, s_idle, s_wait_rready_bready)
        }
      }
    }
  }
}

object AXI4ToAPB {
  def apply(axiBytes: Int = 4, aFlow: Boolean = true)(implicit p: Parameters) = {
    val axi42apb = LazyModule(new AXI4ToAPB(axiBytes, aFlow))
    axi42apb.node
  }
}
