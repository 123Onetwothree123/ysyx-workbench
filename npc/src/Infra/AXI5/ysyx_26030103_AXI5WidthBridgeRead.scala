package ysyx_26030103.infra

import chisel3._
import chisel3.util._

private[infra] trait AXI5WidthBridgeRead {
  this: ysyx_26030103_AXI5WidthBridge =>

  def connectRead(): Unit = {
    // BypassReadID 使用独立第二读槽：CPU 的读可能停在上游迟迟不返回，
    // DMA 读必须还能继续下发。其余情况保持单 outstanding；槽之间按 RID
    // 分派响应，同一 ID 同时只允许一笔，因此每个 ID 的 R 顺序仍严格保持。
    val BypassReadIDValue = BypassReadID
    val ReadSlots = if (BypassReadIDValue.isDefined) 2 else 1
    val rIdle :: rUpsize :: rErrData :: rDnSend :: rDnWait :: rDnOut :: Nil =
      Enum(6)
    val rState = RegInit(VecInit(Seq.fill(ReadSlots)(rIdle)))
    val ReadBurstShapes = RegInit(VecInit(Seq.fill(ReadSlots)(
      ysyx_26030103_AXI5BurstShape.Default(UpstreamAddressWidth, IDWidth))))
    val rLock = RegInit(VecInit(Seq.fill(ReadSlots)(false.B)))
    val rBeat = RegInit(VecInit(Seq.fill(ReadSlots)(0.U(8.W))))
    val rChunk = RegInit(VecInit(Seq.fill(ReadSlots)(0.U(2.W))))
    val rResponseCode = RegInit(VecInit(Seq.fill(ReadSlots)(OKAY)))
    val rData = RegInit(
      VecInit(Seq.fill(ReadSlots)(0.U(UpstreamDataWidth.W)))
    )
    val RARSent = RegInit(VecInit(Seq.fill(ReadSlots)(false.B)))
    val rDnOutstanding = RegInit(VecInit(Seq.fill(ReadSlots)(0.U(2.W))))
    val rUpRLastSeen = RegInit(VecInit(Seq.fill(ReadSlots)(false.B)))
    val RTxnIssuedAR = RegInit(VecInit(Seq.fill(ReadSlots)(false.B)))

    val slotFree = Wire(Vec(ReadSlots, Bool()))
    val slotARValid = Wire(Vec(ReadSlots, Bool()))
    val slotAR = Wire(
      Vec(ReadSlots, new ysyx_26030103_AXI5BurstShape(
        UpstreamAddressWidth,
        IDWidth
      ))
    )
    val slotRValid = Wire(Vec(ReadSlots, Bool()))
    val slotR = Wire(
      Vec(ReadSlots, new ysyx_26030103_AXI5RPayload(
        IDWidth,
        UpstreamDataWidth
      ))
    )
    val slotDnRReady = Wire(Vec(ReadSlots, Bool()))
    val slotDnREligible = Wire(Vec(ReadSlots, Bool()))
    for (slot <- 0 until ReadSlots) {
      slotFree(slot) := rState(slot) === rIdle
      slotARValid(slot) := false.B
      slotAR(slot).ID := ReadBurstShapes(slot).ID
      slotAR(slot).address := downAddress(ReadBurstShapes(slot).address)
      slotAR(slot).length := ReadBurstShapes(slot).length
      slotAR(slot).size := ReadBurstShapes(slot).size
      slotAR(slot).burst := ReadBurstShapes(slot).burst
      slotAR(slot).prot := ReadBurstShapes(slot).prot
      slotAR(slot).cache := ReadBurstShapes(slot).cache
      slotAR(slot).qos := ReadBurstShapes(slot).qos
      slotRValid(slot) := false.B
      slotR(slot).ID := ReadBurstShapes(slot).ID
      slotR(slot).data := 0.U
      slotR(slot).response := OKAY
      slotR(slot).last := false.B
      slotDnRReady(slot) := false.B
      slotDnREligible(slot) := false.B
    }

    val freeMask = VecInit((0 until ReadSlots).map(slotFree(_))).asUInt
    val incomingIDBusy = VecInit((0 until ReadSlots).map { slot =>
      rState(slot) =/= rIdle && ReadBurstShapes(slot).ID === io.in.AR.ARID
    }).asUInt.orR
    // 第 0 槽保持 CPU 侧单事务串行；BypassReadID 优先用第 1 槽，必要时
    // 也可落到空闲的第 0 槽，非 bypass ID 永远不能占用第 1 槽。
    val (startSlot, canAccept) = BypassReadIDValue match {
      case Some(bypassID) =>
        val isBypass = io.in.AR.ARID === bypassID.U(IDWidth.W)
        val select = Mux(isBypass && slotFree(1), 1.U, 0.U)
        val accept = Mux(isBypass, slotFree(0) || slotFree(1), slotFree(0))
        (select, accept)
      case None =>
        (PriorityEncoder(freeMask), freeMask.orR)
    }
    io.in.AR.ARREADY := canAccept && !incomingIDBusy
    val start = io.in.AR.ARVALID && io.in.AR.ARREADY

    val ARMask = VecInit((0 until ReadSlots).map(slotARValid(_))).asUInt
    val ARSelect = PriorityEncoder(ARMask)
    val ARFire = io.out.AR.ARVALID && io.out.AR.ARREADY
    io.out.AR.ARVALID := ARMask.orR
    io.out.AR.ARID := slotAR(ARSelect).ID
    io.out.AR.ARADDR := slotAR(ARSelect).address
    io.out.AR.ARLEN := slotAR(ARSelect).length
    io.out.AR.ARSIZE := slotAR(ARSelect).size
    io.out.AR.ARBURST := slotAR(ARSelect).burst
    io.out.AR.ARPROT := slotAR(ARSelect).prot
    io.out.AR.ARCACHE := slotAR(ARSelect).cache
    io.out.AR.ARQOS := slotAR(ARSelect).qos

    val RMask = VecInit((0 until ReadSlots).map(slotRValid(_))).asUInt
    val RSelect = PriorityEncoder(RMask)
    io.in.R.RVALID := RMask.orR
    io.in.R.RID := slotR(RSelect).ID
    io.in.R.RDATA := slotR(RSelect).data
    io.in.R.RRESP := slotR(RSelect).response
    io.in.R.RLAST := slotR(RSelect).last

    val DnRMask = VecInit((0 until ReadSlots).map(slotDnREligible(_))).asUInt
    val DnRSelect = PriorityEncoder(DnRMask)
    io.out.R.RREADY := DnRMask.orR && slotDnRReady(DnRSelect)

    val incomingRError = outOfRange(
      io.in.AR.ARADDR,
      io.in.AR.ARLEN,
      io.in.AR.ARSIZE,
      io.in.AR.ARBURST
    )

    for (slot <- 0 until ReadSlots) {
      val dnRMatch = io.out.R.RID === ReadBurstShapes(slot).ID
      val dnRSelect = io.out.R.RVALID && slotDnREligible(slot) &&
        DnRSelect === slot.U
      val dnRFire = dnRSelect && io.out.R.RREADY
      val upRFire = slotRValid(slot) && RSelect === slot.U &&
        io.in.R.RREADY
      val slotARFire = ARFire && ARSelect === slot.U && slotARValid(slot)

      switch(rState(slot)) {
        is(rIdle) {
          when(start && startSlot === slot.U) {
            assert(
              io.in.AR.ARSIZE <= upLaneBits.U,
              "AXI5WidthBridge: ARSIZE exceeds upstream data bus width"
            )
            assert(
              io.in.AR.ARBURST =/= 3.U,
              "AXI5WidthBridge: reserved ARBURST encoding"
            )
            ReadBurstShapes(slot).ID := io.in.AR.ARID
            ReadBurstShapes(slot).address := io.in.AR.ARADDR
            ReadBurstShapes(slot).length := io.in.AR.ARLEN
            ReadBurstShapes(slot).size := io.in.AR.ARSIZE
            ReadBurstShapes(slot).burst := io.in.AR.ARBURST
            ReadBurstShapes(slot).prot := io.in.AR.ARPROT
            ReadBurstShapes(slot).cache := io.in.AR.ARCACHE
            ReadBurstShapes(slot).qos := io.in.AR.ARQOS
            rLock(slot) := InARLock
            rBeat(slot) := 0.U
            rChunk(slot) := 0.U
            rResponseCode(slot) := OKAY
            rData(slot) := 0.U
            RARSent(slot) := false.B
            rUpRLastSeen(slot) := false.B
            RTxnIssuedAR(slot) := false.B
            rState(slot) := Mux(
              incomingRError,
              rErrData,
              if (downsize) { rDnSend }
              else { rUpsize }
            )
          }
        }

        is(rUpsize) {
          // 32->64：AR 形状不变，R 数据按 beat 地址从宽总线对应半区取回。
          slotARValid(slot) := !RARSent(slot)
          slotAR(slot).ID := ReadBurstShapes(slot).ID
          slotAR(slot).address := downAddress(ReadBurstShapes(slot).address)
          slotAR(slot).length := ReadBurstShapes(slot).length
          slotAR(slot).size := ReadBurstShapes(slot).size
          slotAR(slot).burst := ReadBurstShapes(slot).burst
          slotAR(slot).prot := ReadBurstShapes(slot).prot
          slotAR(slot).cache := ReadBurstShapes(slot).cache
          slotAR(slot).qos := ReadBurstShapes(slot).qos
          when(slotARFire) {
            RARSent(slot) := true.B
          }

          val beatAddress =
            beatAddressOf(ReadBurstShapes(slot).address, rBeat(slot), ReadBurstShapes(slot).size,
              ReadBurstShapes(slot).burst, ReadBurstShapes(slot).length)
          val lane = upsizeLane(beatAddress)
          slotDnREligible(slot) := dnRMatch
          slotDnRReady(slot) := io.in.R.RREADY && RSelect === slot.U
          slotRValid(slot) := dnRSelect
          slotR(slot).ID := io.out.R.RID
          slotR(slot).data := downDataToUp(io.out.R.RDATA, lane)
          slotR(slot).response := io.out.R.RRESP
          slotR(slot).last := io.out.R.RLAST
          when(dnRSelect) {
            assert(
              io.out.R.RID === ReadBurstShapes(slot).ID,
              "AXI5WidthBridge: downstream read response ID mismatch"
            )
            assert(
              io.out.R.RLAST === (ysyx_26030103_AXI5Beat.IsLast(rBeat(slot), ReadBurstShapes(slot).length)),
              "AXI5WidthBridge: downstream RLAST does not match ARLEN"
            )
            // 32 位上游数据放进 64 位总线的一半；非对齐首拍只占该 size
            // 自然窗口的剩余部分，后续拍必须是完整且对齐的 size 窗口。
            val sizeBytes = 1.U << ReadBurstShapes(slot).size
            val partial = rBeat(slot) === 0.U || ReadBurstShapes(slot).burst === 0.U
            val beatBytes =
              Mux(
                partial,
                sizeBytes - (beatAddress & (sizeBytes - 1.U)),
                sizeBytes
              )
            assert(
              lane + beatBytes <= downBytes.U,
              "AXI5WidthBridge: upstream read beat does not fit a downstream wide beat"
            )
          }
          when(dnRFire) {
            when(ysyx_26030103_AXI5Beat.IsLast(rBeat(slot), ReadBurstShapes(slot).length)) {
              rState(slot) := rIdle
            }.otherwise {
              rBeat(slot) := ysyx_26030103_AXI5Beat.Next(rBeat(slot))
            }
          }
        }

        is(rErrData) {
          // 地址越界读：不产生下游 AR，按 ARLEN 返回等长 DECERR burst。
          slotRValid(slot) := true.B
          slotR(slot).ID := ReadBurstShapes(slot).ID
          slotR(slot).data := 0.U
          slotR(slot).response := DECERR
          slotR(slot).last := ysyx_26030103_AXI5Beat.IsLast(rBeat(slot), ReadBurstShapes(slot).length)
          when(upRFire) {
            when(ysyx_26030103_AXI5Beat.IsLast(rBeat(slot), ReadBurstShapes(slot).length)) {
              rState(slot) := rIdle
            }.otherwise {
              rBeat(slot) := ysyx_26030103_AXI5Beat.Next(rBeat(slot))
            }
          }
        }

        is(rDnSend) {
          // 64->32：为当前上游 beat 的一个 4 字节块发一笔单拍 AR。
          val beatAddress =
            beatAddressOf(ReadBurstShapes(slot).address, rBeat(slot), ReadBurstShapes(slot).size,
              ReadBurstShapes(slot).burst, ReadBurstShapes(slot).length)
          val chunkAddress = chunkAddressOf(beatAddress, rChunk(slot))
          slotARValid(slot) := true.B
          slotAR(slot).ID := ReadBurstShapes(slot).ID
          slotAR(slot).address := downAddress(chunkAddress)
          slotAR(slot).length := 0.U
          slotAR(slot).size := downLaneBits.U(3.W)
          slotAR(slot).burst := 1.U
          slotAR(slot).prot := ReadBurstShapes(slot).prot
          slotAR(slot).cache := ReadBurstShapes(slot).cache
          slotAR(slot).qos := ReadBurstShapes(slot).qos
          when(slotARFire) {
            rState(slot) := rDnWait
          }
        }

        is(rDnWait) {
          val beatAddress =
            beatAddressOf(ReadBurstShapes(slot).address, rBeat(slot), ReadBurstShapes(slot).size,
              ReadBurstShapes(slot).burst, ReadBurstShapes(slot).length)
          val byteBase = beatByteBase(beatAddress)
          val chunkAddress = chunkAddressOf(beatAddress, rChunk(slot))
          val byteOffset = (chunkAddress - byteBase)(upLaneBits - 1, 0)
          val partial = rBeat(slot) === 0.U || ReadBurstShapes(slot).burst === 0.U
          val lastChunk = beatChunkCount(beatAddress, ReadBurstShapes(slot).size, partial) - 1.U
          slotDnREligible(slot) := dnRMatch
          slotDnRReady(slot) := true.B
          when(dnRSelect) {
            assert(
              io.out.R.RID === ReadBurstShapes(slot).ID,
              "AXI5WidthBridge: downstream read response ID mismatch"
            )
            assert(
              io.out.R.RLAST,
              "AXI5WidthBridge: single-beat downstream read missing RLAST"
            )
            val shift = byteOffset << 3
            rData(slot) := rData(slot) |
              ((io.out.R.RDATA.pad(UpstreamDataWidth) << shift)(
                UpstreamDataWidth - 1,
                0
              ))
            when(io.out.R.RRESP =/= OKAY) {
              rResponseCode(slot) := io.out.R.RRESP
            }
            when(rChunk(slot) === lastChunk) {
              rState(slot) := rDnOut
            }.otherwise {
              rChunk(slot) := rChunk(slot) + 1.U
              rState(slot) := rDnSend
            }
          }
        }

        is(rDnOut) {
          slotRValid(slot) := true.B
          slotR(slot).ID := ReadBurstShapes(slot).ID
          slotR(slot).data := rData(slot)
          slotR(slot).response := rResponseCode(slot)
          slotR(slot).last := ysyx_26030103_AXI5Beat.IsLast(rBeat(slot), ReadBurstShapes(slot).length)
          when(upRFire) {
            when(ysyx_26030103_AXI5Beat.IsLast(rBeat(slot), ReadBurstShapes(slot).length)) {
              rState(slot) := rIdle
            }.otherwise {
              rBeat(slot) := ysyx_26030103_AXI5Beat.Next(rBeat(slot))
              rChunk(slot) := 0.U
              rData(slot) := 0.U
              rResponseCode(slot) := OKAY
              rState(slot) := rDnSend
            }
          }
        }
      }

      when(slotARFire) { RTxnIssuedAR(slot) := true.B }
      when(upRFire && io.in.R.RLAST) {
        rUpRLastSeen(slot) := true.B
        // 外部读事务的最后一个 R 已经交付，解除 LOCK 与事务的绑定。
        rLock(slot) := false.B
      }
      val rDnRLastFire = dnRFire && io.out.R.RLAST
      when(slotARFire =/= rDnRLastFire) {
        rDnOutstanding(slot) := Mux(
          slotARFire,
          rDnOutstanding(slot) + 1.U,
          rDnOutstanding(slot) - 1.U
        )
      }

      when(rDnOutstanding(slot) > 1.U) {
        assert(
          false.B,
          "AXI5WidthBridge: more than one downstream read outstanding"
        )
      }
      when(rState(slot) === rIdle) {
        assert(
          rDnOutstanding(slot) === 0.U,
          "AXI5WidthBridge: idle with downstream read outstanding"
        )
      }
      when(io.out.R.RVALID && dnRSelect) {
        assert(
          rDnOutstanding(slot) =/= 0.U ||
            (ARFire && ARSelect === slot.U),
          "AXI5WidthBridge: downstream R without outstanding AR"
        )
        assert(
          io.out.R.RID === ReadBurstShapes(slot).ID,
          "AXI5WidthBridge: downstream read response ID mismatch"
        )
      }
      when(io.in.R.RVALID && RSelect === slot.U) {
        assert(
          rState(slot) =/= rIdle,
          "AXI5WidthBridge: upstream R while idle"
        )
        assert(
          io.in.R.RID === ReadBurstShapes(slot).ID,
          "AXI5WidthBridge: upstream read response ID mismatch"
        )
        assert(
          !rUpRLastSeen(slot),
          "AXI5WidthBridge: duplicate upstream RLAST"
        )
        assert(
          io.in.R.RLAST === (ysyx_26030103_AXI5Beat.IsLast(rBeat(slot), ReadBurstShapes(slot).length)),
          "AXI5WidthBridge: upstream RLAST does not match ARLEN"
        )
        when(io.in.R.RLAST) {
          assert(
            rDnOutstanding(slot) === 0.U ||
              (io.out.R.RVALID && io.out.R.RLAST &&
                DnRSelect === slot.U) ||
              rState(slot) === rErrData,
            "AXI5WidthBridge: upstream final R while downstream read is outstanding"
          )
        }
        when(rState(slot) === rErrData) {
          assert(
            !RTxnIssuedAR(slot),
            "AXI5WidthBridge: local DECERR R after issuing downstream AR"
          )
        }
      }
    }

    when(io.out.AR.ARVALID) {
      assert(
        io.out.AR.ARID === ReadBurstShapes(ARSelect).ID,
        "AXI5WidthBridge: downstream read request ID mismatch"
      )
    }
    when(io.out.R.RVALID) {
      assert(
        DnRMask.orR,
        "AXI5WidthBridge: downstream R matched no active read"
      )
    }
    when(io.in.R.RVALID) {
      assert(
        RMask.orR,
        "AXI5WidthBridge: upstream R while idle"
      )
    }

    if (TrackExclusive) {
      io.OutARLock.get := rLock(ARSelect)
    }
  }
}
