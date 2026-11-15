package ysyx_26030103.infra

import chisel3._
import chisel3.util._

private[infra] trait AXI5WidthBridgeWrite {
  this: ysyx_26030103_AXI5WidthBridge =>

  def connectWrite(): Unit = {
    // 写通道
    val wIdle :: wUpsize :: wErrDrain :: wResp :: wDnBeat :: wDnSend :: wDnWaitB :: Nil =
      Enum(7)
    val wState = RegInit(wIdle)
    val wId = RegInit(0.U(IdWidth.W))
    val wAddr = RegInit(0.U(UpstreamAddressWidth.W))
    val wLen = RegInit(0.U(8.W))
    val wSize = RegInit(2.U(3.W))
    val wBurst = RegInit(0.U(2.W))
    val wProt = RegInit(0.U(3.W))
    val wCache = RegInit(0.U(4.W))
    val wQos = RegInit(0.U(4.W))
    val wLock = RegInit(false.B)
    val wError = RegInit(false.B)
    val wBeat = RegInit(0.U(8.W))
    val wChunk = RegInit(0.U(2.W))
    val wRespCode = RegInit(OKAY)
    val wAwSent = RegInit(false.B)
    val wWDone = RegInit(false.B)
    val wBeatValid = RegInit(false.B)
    val wBeatData = RegInit(0.U(UpstreamDataWidth.W))
    val wBeatStrb = RegInit(0.U(UpBytes.W))
    val wBeatLast = RegInit(false.B)
    val wChunkAwSent = RegInit(false.B)
    val wChunkWSent = RegInit(false.B)

    val wDnAwFire = io.out.AW.AWVALID && io.out.AW.AWREADY
    val wDnBFire = io.out.B.BVALID && io.out.B.BREADY
    val wDnOutstanding = RegInit(0.U(2.W))
    val wUpWLastSeen = RegInit(false.B)
    val wUpBSeen = RegInit(false.B)
    val wTxnIssuedAw = RegInit(false.B)
    val wDnWLastSeen = RegInit(false.B)

    val incomingWError = OutOfRange(
      io.in.AW.AWADDR,
      io.in.AW.AWLEN,
      io.in.AW.AWSIZE,
      io.in.AW.AWBURST
    )

    switch(wState) {
      is(wIdle) {
        io.in.AW.AWREADY := true.B
        when(io.in.AW.AWVALID) {
          assert(
            io.in.AW.AWSIZE <= UpLaneBits.U,
            "AXI5WidthBridge: AWSIZE exceeds upstream data bus width"
          )
          assert(
            io.in.AW.AWBURST =/= 3.U,
            "AXI5WidthBridge: reserved AWBURST encoding"
          )
          wId := io.in.AW.AWID
          wAddr := io.in.AW.AWADDR
          wLen := io.in.AW.AWLEN
          wSize := io.in.AW.AWSIZE
          wBurst := io.in.AW.AWBURST
          wProt := io.in.AW.AWPROT
          wCache := io.in.AW.AWCACHE
          wQos := io.in.AW.AWQOS
          wLock := InAWLock
          wError := incomingWError
          wBeat := 0.U
          wChunk := 0.U
          wRespCode := OKAY
          wAwSent := false.B
          wWDone := false.B
          wBeatValid := false.B
          wChunkAwSent := false.B
          wChunkWSent := false.B
          wState := Mux(
            incomingWError,
            wErrDrain,
            if (Downsize) { wDnBeat }
            else { wUpsize }
          )
        }
      }

      is(wErrDrain) {
        // 地址越界时不下发任何 AW，但必须吃完整笔 W burst 再回 DECERR，
        // 否则上游会停在 W 通道上等一个永远不会到来的 B。
        io.in.W.WREADY := true.B
        when(io.in.W.WVALID && io.in.W.WLAST) {
          wState := wResp
        }
      }

      is(wUpsize) {
        // 32->64：一个上游 beat 对应一个下游 beat，AW/AR 的 burst 形状
        // 保持不变。数据按当前 beat 地址搬到 64 位总线的对应半区。
        io.out.AW.AWVALID := !wAwSent
        io.out.AW.AWID := wId
        io.out.AW.AWADDR := DownAddress(wAddr)
        io.out.AW.AWLEN := wLen
        io.out.AW.AWSIZE := wSize
        io.out.AW.AWBURST := wBurst
        io.out.AW.AWPROT := wProt
        io.out.AW.AWCACHE := wCache
        io.out.AW.AWQOS := wQos
        when(io.out.AW.AWVALID && io.out.AW.AWREADY) {
          wAwSent := true.B
        }

        val BeatAddr = BeatAddress(wAddr, wBeat, wSize, wBurst, wLen)
        val Lane = UpsizeLane(BeatAddr)
        val SizeBytes = 1.U << wSize
        val Partial = wBeat === 0.U || wBurst === 0.U
        val BeatBytes =
          Mux(
            Partial,
            SizeBytes - (BeatAddr & (SizeBytes - 1.U)),
            SizeBytes
          )
        when(io.in.W.WVALID || wBeatValid) {
          assert(
            Lane + BeatBytes <= DownBytes.U,
            "AXI5WidthBridge: upstream beat does not fit a downstream wide beat"
          )
        }
        when(!wBeatValid && !wWDone) {
          io.in.W.WREADY := true.B
          when(io.in.W.WVALID) {
            wBeatData := io.in.W.WDATA
            wBeatStrb := io.in.W.WSTRB
            wBeatLast := io.in.W.WLAST
            wBeatValid := true.B
          }
        }.otherwise {
          io.out.W.WVALID := true.B
          io.out.W.WDATA := UpDataToDown(wBeatData, Lane)
          io.out.W.WSTRB := UpStrbToDown(wBeatStrb, Lane)
          io.out.W.WLAST := wBeatLast
          when(io.out.W.WREADY) {
            wBeatValid := false.B
            when(wBeatLast) {
              wWDone := true.B
            }.otherwise {
              wBeat := wBeat + 1.U
            }
          }
        }

        when(wWDone && wAwSent) {
          wState := wResp
        }
      }

      is(wDnBeat) {
        // 64->32：先把一个上游宽 beat 收到本地缓冲，再逐 4 字节块发出。
        // 这里不能直接透传，因为一个上游 beat 可能对应两笔下游 AW/B。
        io.in.W.WREADY := !wBeatValid
        when(!wBeatValid && io.in.W.WVALID) {
          wBeatData := io.in.W.WDATA
          wBeatStrb := io.in.W.WSTRB
          wBeatLast := io.in.W.WLAST
          wBeatValid := true.B
          wChunk := 0.U
          wChunkAwSent := false.B
          wChunkWSent := false.B
          wState := wDnSend
        }
      }

      is(wDnSend) {
        val BeatAddr = BeatAddress(wAddr, wBeat, wSize, wBurst, wLen)
        val ByteBase = BeatByteBase(BeatAddr)
        val ChunkAddr = ChunkAddress(BeatAddr, wChunk)
        val ByteOffset = (ChunkAddr - ByteBase)(UpLaneBits - 1, 0)

        io.out.AW.AWVALID := !wChunkAwSent
        io.out.AW.AWID := wId
        io.out.AW.AWADDR := DownAddress(ChunkAddr)
        io.out.AW.AWLEN := 0.U
        io.out.AW.AWSIZE := DownLaneBits.U(3.W)
        io.out.AW.AWBURST := 1.U
        io.out.AW.AWPROT := wProt
        io.out.AW.AWCACHE := wCache
        io.out.AW.AWQOS := wQos

        io.out.W.WVALID := !wChunkWSent
        io.out.W.WDATA := ExtractData(wBeatData, ByteOffset)
        io.out.W.WSTRB := ExtractStrb(wBeatStrb, ByteOffset)
        // 每个 chunk 都是独立的 AWLEN=0 单拍事务，WLAST 必须逐笔拉高；
        // 上游 burst 的最后一拍只用于决定何时聚合出上游 B。
        io.out.W.WLAST := true.B

        val AwFire = io.out.AW.AWVALID && io.out.AW.AWREADY
        val WFire = io.out.W.WVALID && io.out.W.WREADY
        when(AwFire) { wChunkAwSent := true.B }
        when(WFire) { wChunkWSent := true.B }
        when((wChunkAwSent || AwFire) && (wChunkWSent || WFire)) {
          wChunkAwSent := false.B
          wChunkWSent := false.B
          wState := wDnWaitB
        }
      }

      is(wDnWaitB) {
        val BeatAddr = BeatAddress(wAddr, wBeat, wSize, wBurst, wLen)
        val Partial = wBeat === 0.U || wBurst === 0.U
        val LastChunk = BeatChunkCount(BeatAddr, wSize, Partial) - 1.U
        io.out.B.BREADY := true.B
        when(io.out.B.BVALID) {
          assert(
            io.out.B.BID === wId,
            "AXI5WidthBridge: downstream write response ID mismatch"
          )
          when(io.out.B.BRESP =/= OKAY) {
            wRespCode := io.out.B.BRESP
          }
          when(wChunk === LastChunk) {
            when(wBeatLast) {
              wState := wResp
            }.otherwise {
              wBeat := wBeat + 1.U
              wBeatValid := false.B
              wChunk := 0.U
              wState := wDnBeat
            }
          }.otherwise {
            wChunk := wChunk + 1.U
            wState := wDnSend
          }
        }
      }

      is(wResp) {
        if (Downsize) {
          // 64->32：wDnWaitB 已经逐块收齐所有下游 B，本地聚合成一个
          // 上游 B。越界事务没有下发 AW/W，也在这里返回 DECERR。
          io.in.B.BVALID := true.B
          io.in.B.BID := wId
          io.in.B.BRESP := Mux(wError, DECERR, wRespCode)
          when(io.in.B.BREADY) {
            wState := wIdle
          }
        } else if (AddressNarrows) {
          // 地址变窄但数据同宽（RV64 CPU + 32 位 SoC）：AW/W 走 wUpsize，
          // 下游 B 仍在途，必须等它返回后再交给上游，不能提前伪造 B。
          // 越界事务没有下发 AW/W，必须本地生成 DECERR。
          when(wError) {
            io.in.B.BVALID := true.B
            io.in.B.BID := wId
            io.in.B.BRESP := DECERR
            when(io.in.B.BREADY) {
              wState := wIdle
            }
          }.otherwise {
            io.in.B.BVALID := io.out.B.BVALID
            io.in.B.BID := io.out.B.BID
            io.in.B.BRESP := io.out.B.BRESP
            io.out.B.BREADY := io.in.B.BREADY
            when(io.out.B.BVALID) {
              assert(
                io.out.B.BID === wId,
                "AXI5WidthBridge: downstream write response ID mismatch"
              )
            }
            when(io.out.B.BVALID && io.in.B.BREADY) {
              wState := wIdle
            }
          }
        } else {
          when(wError) {
            // 地址越界但数据同宽/变宽：没有下发任何下游 AW/W，本地
            // 返回 DECERR，不能等待一个不存在的下游 B。
            io.in.B.BVALID := true.B
            io.in.B.BID := wId
            io.in.B.BRESP := DECERR
            when(io.in.B.BREADY) {
              wState := wIdle
            }
          }.otherwise {
            // 数据同宽（包括 AddressNarrows）或 32->64：下游 AW/W 已经
            // 下发，必须等下游 B 返回后再透传给上游。地址窄化本身不
            // 产生本地响应。
            io.in.B.BVALID := io.out.B.BVALID
            io.in.B.BID := io.out.B.BID
            io.in.B.BRESP := io.out.B.BRESP
            io.out.B.BREADY := io.in.B.BREADY
            when(io.out.B.BVALID) {
              assert(
                io.out.B.BID === wId,
                "AXI5WidthBridge: downstream write response ID mismatch"
              )
            }
            when(io.out.B.BVALID && io.in.B.BREADY) {
              wState := wIdle
            }
          }
        }
      }
    }

    // 写通道协议断言
    when(wState === wIdle && io.in.AW.AWVALID && io.in.AW.AWREADY) {
      wUpWLastSeen := false.B
      wUpBSeen := false.B
      wTxnIssuedAw := false.B
      wDnWLastSeen := false.B
    }
    when(wDnAwFire) { wTxnIssuedAw := true.B }
    when(io.in.W.WVALID && io.in.W.WREADY && io.in.W.WLAST) {
      wUpWLastSeen := true.B
    }
    when(io.out.W.WVALID && io.out.W.WREADY && io.out.W.WLAST) {
      wDnWLastSeen := true.B
    }
    when(io.in.B.BVALID && io.in.B.BREADY) {
      wUpBSeen := true.B
      // 上一笔外部写事务已经完成，锁存值不再属于任何在途事务。
      wLock := false.B
    }
    when(wDnAwFire =/= wDnBFire) {
      wDnOutstanding := Mux(
        wDnAwFire,
        wDnOutstanding + 1.U,
        wDnOutstanding - 1.U
      )
    }

    when(wDnOutstanding > 1.U) {
      assert(
        false.B,
        "AXI5WidthBridge: more than one downstream write outstanding"
      )
    }
    when(wState === wIdle) {
      assert(
        wDnOutstanding === 0.U,
        "AXI5WidthBridge: idle with downstream write outstanding"
      )
    }
    when(io.out.B.BVALID) {
      assert(
        io.out.B.BID === wId,
        "AXI5WidthBridge: downstream write response ID mismatch"
      )
      assert(
        wDnWLastSeen || (io.out.W.WVALID && io.out.W.WREADY && io.out.W.WLAST),
        "AXI5WidthBridge: downstream B before downstream WLAST"
      )
    }
    when(io.in.B.BVALID) {
      assert(
        wUpWLastSeen,
        "AXI5WidthBridge: upstream B before last upstream W beat"
      )
      assert(!wUpBSeen, "AXI5WidthBridge: duplicate upstream B")
      assert(
        io.in.B.BID === wId,
        "AXI5WidthBridge: upstream write response ID mismatch"
      )
      assert(
        wDnOutstanding === 0.U || io.out.B.BVALID,
        "AXI5WidthBridge: upstream B while downstream write is outstanding"
      )
      when(wError) {
        assert(
          !wTxnIssuedAw,
          "AXI5WidthBridge: local DECERR B after issuing downstream AW"
        )
      }
    }
    when(io.in.W.WVALID && io.in.W.WREADY && !wError) {
      assert(
        wBeat <= wLen,
        "AXI5WidthBridge: upstream W beat exceeds AWLEN"
      )
      assert(
        io.in.W.WLAST === (wBeat === wLen),
        "AXI5WidthBridge: upstream WLAST does not match AWLEN"
      )
    }

    if (TrackExclusive) {
      io.outAWLock.get := wLock
    }
  }
}
