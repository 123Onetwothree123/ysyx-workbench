package ysyx_26030103.infra

import chisel3._
import chisel3.util._

private[infra] trait AXI5WidthBridgeRead {
  this: ysyx_26030103_AXI5WidthBridge =>

  def connectRead(): Unit = {
    // 读通道
    val rIdle :: rUpsize :: rErrData :: rDnSend :: rDnWait :: rDnOut :: Nil =
      Enum(6)
    val rState = RegInit(rIdle)
    val rID = RegInit(0.U(IDWidth.W))
    val rAddress = RegInit(0.U(UpstreamAddressWidth.W))
    val rLen = RegInit(0.U(8.W))
    val rSize = RegInit(2.U(3.W))
    val rBurst = RegInit(0.U(2.W))
    val rProt = RegInit(0.U(3.W))
    val rCache = RegInit(0.U(4.W))
    val rQos = RegInit(0.U(4.W))
    val rLock = RegInit(false.B)
    if (TrackExclusive) {
      io.outARLock.get := rLock
    }
    val rBeat = RegInit(0.U(8.W))
    val rChunk = RegInit(0.U(2.W))
    val rResponseCode = RegInit(OKAY)
    val rData = RegInit(0.U(UpstreamDataWidth.W))
    val rARSent = RegInit(false.B)

    val rDnARFire = io.out.AR.ARVALID && io.out.AR.ARREADY
    val rDnRLastFire = io.out.R.RVALID && io.out.R.RREADY && io.out.R.RLAST
    val rDnOutstanding = RegInit(0.U(2.W))
    val rUpRLastSeen = RegInit(false.B)
    val rTxnIssuedAR = RegInit(false.B)

    val incomingRError = outOfRange(
      io.in.AR.ARADDR,
      io.in.AR.ARLEN,
      io.in.AR.ARSIZE,
      io.in.AR.ARBURST
    )

    switch(rState) {
      is(rIdle) {
        io.in.AR.ARREADY := true.B
        when(io.in.AR.ARVALID) {
          assert(
            io.in.AR.ARSIZE <= upLaneBits.U,
            "AXI5WidthBridge: ARSIZE exceeds upstream data bus width"
          )
          assert(
            io.in.AR.ARBURST =/= 3.U,
            "AXI5WidthBridge: reserved ARBURST encoding"
          )
          rID := io.in.AR.ARID
          rAddress := io.in.AR.ARADDR
          rLen := io.in.AR.ARLEN
          rSize := io.in.AR.ARSIZE
          rBurst := io.in.AR.ARBURST
          rProt := io.in.AR.ARPROT
          rCache := io.in.AR.ARCACHE
          rQos := io.in.AR.ARQOS
          rLock := inARLock
          rBeat := 0.U
          rChunk := 0.U
          rResponseCode := OKAY
          rData := 0.U
          rARSent := false.B
          rState := Mux(
            incomingRError,
            rErrData,
            if (downsize) { rDnSend }
            else { rUpsize }
          )
        }
      }

      is(rUpsize) {
        // 32->64：AR 形状不变，R 数据按 beat 地址从宽总线对应半区取回。
        io.out.AR.ARVALID := !rARSent
        io.out.AR.ARID := rID
        io.out.AR.ARADDR := downAddress(rAddress)
        io.out.AR.ARLEN := rLen
        io.out.AR.ARSIZE := rSize
        io.out.AR.ARBURST := rBurst
        io.out.AR.ARPROT := rProt
        io.out.AR.ARCACHE := rCache
        io.out.AR.ARQOS := rQos
        when(io.out.AR.ARVALID && io.out.AR.ARREADY) {
          rARSent := true.B
        }

        val beatAddress = beatAddressOf(rAddress, rBeat, rSize, rBurst, rLen)
        val lane = upsizeLane(beatAddress)
        io.out.R.RREADY := io.in.R.RREADY
        io.in.R.RVALID := io.out.R.RVALID
        io.in.R.RID := io.out.R.RID
        io.in.R.RDATA := downDataToUp(io.out.R.RDATA, lane)
        io.in.R.RRESP := io.out.R.RRESP
        io.in.R.RLAST := io.out.R.RLAST
        when(io.out.R.RVALID) {
          assert(
            io.out.R.RID === rID,
            "AXI5WidthBridge: downstream read response ID mismatch"
          )
          assert(
            io.out.R.RLAST === (rBeat === rLen),
            "AXI5WidthBridge: downstream RLAST does not match ARLEN"
          )
          // 32 位上游数据放进 64 位总线的一半；非对齐首拍只占该 size
          // 自然窗口的剩余部分，后续拍必须是完整且对齐的 size 窗口。
          val sizeBytes = 1.U << rSize
          val partial = rBeat === 0.U || rBurst === 0.U
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
        when(io.out.R.RVALID && io.in.R.RREADY) {
          when(rBeat === rLen) {
            rState := rIdle
          }.otherwise {
            rBeat := rBeat + 1.U
          }
        }
      }

      is(rErrData) {
        // 地址越界读：不产生下游 AR，按 ARLEN 返回等长 DECERR burst。
        io.in.R.RVALID := true.B
        io.in.R.RID := rID
        io.in.R.RDATA := 0.U
        io.in.R.RRESP := DECERR
        io.in.R.RLAST := rBeat === rLen
        when(io.in.R.RREADY) {
          when(rBeat === rLen) {
            rState := rIdle
          }.otherwise {
            rBeat := rBeat + 1.U
          }
        }
      }

      is(rDnSend) {
        // 64->32：为当前上游 beat 的一个 4 字节块发一笔单拍 AR。
        val beatAddress = beatAddressOf(rAddress, rBeat, rSize, rBurst, rLen)
        val chunkAddress = chunkAddressOf(beatAddress, rChunk)
        io.out.AR.ARVALID := true.B
        io.out.AR.ARID := rID
        io.out.AR.ARADDR := downAddress(chunkAddress)
        io.out.AR.ARLEN := 0.U
        io.out.AR.ARSIZE := downLaneBits.U(3.W)
        io.out.AR.ARBURST := 1.U
        io.out.AR.ARPROT := rProt
        io.out.AR.ARCACHE := rCache
        io.out.AR.ARQOS := rQos
        when(io.out.AR.ARREADY) {
          rState := rDnWait
        }
      }

      is(rDnWait) {
        val beatAddress = beatAddressOf(rAddress, rBeat, rSize, rBurst, rLen)
        val byteBase = beatByteBase(beatAddress)
        val chunkAddress = chunkAddressOf(beatAddress, rChunk)
        val byteOffset = (chunkAddress - byteBase)(upLaneBits - 1, 0)
        val partial = rBeat === 0.U || rBurst === 0.U
        val lastChunk = beatChunkCount(beatAddress, rSize, partial) - 1.U
        io.out.R.RREADY := true.B
        when(io.out.R.RVALID) {
          assert(
            io.out.R.RID === rID,
            "AXI5WidthBridge: downstream read response ID mismatch"
          )
          assert(
            io.out.R.RLAST,
            "AXI5WidthBridge: single-beat downstream read missing RLAST"
          )
          val shift = byteOffset << 3
          rData := rData |
            ((io.out.R.RDATA.pad(UpstreamDataWidth) << shift)(
              UpstreamDataWidth - 1,
              0
            ))
          when(io.out.R.RRESP =/= OKAY) {
            rResponseCode := io.out.R.RRESP
          }
          when(rChunk === lastChunk) {
            rState := rDnOut
          }.otherwise {
            rChunk := rChunk + 1.U
            rState := rDnSend
          }
        }
      }

      is(rDnOut) {
        io.in.R.RVALID := true.B
        io.in.R.RID := rID
        io.in.R.RDATA := rData
        io.in.R.RRESP := rResponseCode
        io.in.R.RLAST := rBeat === rLen
        when(io.in.R.RREADY) {
          when(rBeat === rLen) {
            rState := rIdle
          }.otherwise {
            rBeat := rBeat + 1.U
            rChunk := 0.U
            rData := 0.U
            rResponseCode := OKAY
            rState := rDnSend
          }
        }
      }
    }

    // 读通道协议断言
    when(rState === rIdle && io.in.AR.ARVALID && io.in.AR.ARREADY) {
      rUpRLastSeen := false.B
      rTxnIssuedAR := false.B
    }
    when(rDnARFire) { rTxnIssuedAR := true.B }
    when(io.in.R.RVALID && io.in.R.RREADY && io.in.R.RLAST) {
      rUpRLastSeen := true.B
      // 外部读事务的最后一个 R 已经交付，解除 LOCK 与事务的绑定。
      rLock := false.B
    }
    when(rDnARFire =/= rDnRLastFire) {
      rDnOutstanding := Mux(
        rDnARFire,
        rDnOutstanding + 1.U,
        rDnOutstanding - 1.U
      )
    }

    when(rDnOutstanding > 1.U) {
      assert(
        false.B,
        "AXI5WidthBridge: more than one downstream read outstanding"
      )
    }
    when(rState === rIdle) {
      assert(
        rDnOutstanding === 0.U,
        "AXI5WidthBridge: idle with downstream read outstanding"
      )
    }
    when(io.out.AR.ARVALID) {
      assert(
        io.out.AR.ARID === rID,
        "AXI5WidthBridge: downstream read request ID mismatch"
      )
    }
    when(io.out.R.RVALID) {
      assert(
        rDnOutstanding =/= 0.U || rDnARFire,
        "AXI5WidthBridge: downstream R without outstanding AR"
      )
      assert(
        io.out.R.RID === rID,
        "AXI5WidthBridge: downstream read response ID mismatch"
      )
    }
    when(io.in.R.RVALID) {
      assert(rState =/= rIdle, "AXI5WidthBridge: upstream R while idle")
      assert(
        io.in.R.RID === rID,
        "AXI5WidthBridge: upstream read response ID mismatch"
      )
      assert(!rUpRLastSeen, "AXI5WidthBridge: duplicate upstream RLAST")
      assert(
        io.in.R.RLAST === (rBeat === rLen),
        "AXI5WidthBridge: upstream RLAST does not match ARLEN"
      )
      when(io.in.R.RLAST) {
        assert(
          rDnOutstanding === 0.U ||
            (io.out.R.RVALID && io.out.R.RLAST) ||
            rState === rErrData,
          "AXI5WidthBridge: upstream final R while downstream read is outstanding"
        )
      }
      when(rState === rErrData) {
        assert(
          !rTxnIssuedAR,
          "AXI5WidthBridge: local DECERR R after issuing downstream AR"
        )
      }
    }
  }
}
