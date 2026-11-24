package ysyx_26030103.infra

import chisel3._
import chisel3.util._

private[infra] trait AXI5WidthBridgeWrite {
  this: ysyx_26030103_AXI5WidthBridge =>

  def connectWrite(): Unit = {
    // 写通道
    val wIdle :: wUpsize :: wErrDrain :: wResponse :: wDnBeat :: wDnSend :: wDnWaitB :: Nil =
      Enum(7)
    val wState = RegInit(wIdle)
    val WID = RegInit(0.U(IDWidth.W))
    val wAddress = RegInit(0.U(UpstreamAddressWidth.W))
    val WLength = RegInit(0.U(8.W))
    val wSize = RegInit(2.U(3.W))
    val wBurst = RegInit(0.U(2.W))
    val wProt = RegInit(0.U(3.W))
    val wCache = RegInit(0.U(4.W))
    val wQos = RegInit(0.U(4.W))
    val wLock = RegInit(false.B)
    val wError = RegInit(false.B)
    val wBeat = RegInit(0.U(8.W))
    val wChunk = RegInit(0.U(2.W))
    // 部分有效的 4 字节 chunk 会拆成逐字节事务；该寄存器保存当前 chunk
    // 内正在发送的字节下标，B 返回后从它的下一个字节继续。
    val DownstreamByteIndex = RegInit(0.U(2.W))
    val wResponseCode = RegInit(OKAY)
    val WAWSent = RegInit(false.B)
    val wWDone = RegInit(false.B)
    val wBeatValid = RegInit(false.B)
    val wBeatData = RegInit(0.U(UpstreamDataWidth.W))
    val wBeatStrobe = RegInit(0.U(upBytes.W))
    val wBeatLast = RegInit(false.B)
    val WChunkAWSent = RegInit(false.B)
    val wChunkWSent = RegInit(false.B)

    val WDnAWFire = io.out.AW.AWVALID && io.out.AW.AWREADY
    val wDnBFire = io.out.B.BVALID && io.out.B.BREADY
    val wDnOutstanding = RegInit(0.U(2.W))
    val wUpWLastSeen = RegInit(false.B)
    val wUpBSeen = RegInit(false.B)
    val WTxnIssuedAW = RegInit(false.B)
    val wDnWLastSeen = RegInit(false.B)

    val incomingWError = outOfRange(
      io.in.AW.AWADDR,
      io.in.AW.AWLEN,
      io.in.AW.AWSIZE,
      io.in.AW.AWBURST
    )

    // 当前 4 字节 chunk 完成后，推进到本拍的下一个 chunk；本拍也完成时
    // 再采集下一拍 W，或聚合出上游 B。
    def advanceWriteChunk(): Unit = {
      val beatAddress = beatAddressOf(wAddress, wBeat, wSize, wBurst, WLength)
      val lastChunk = beatChunkCount(
        beatAddress,
        wSize,
        wBeat === 0.U || wBurst === 0.U
      ) - 1.U
      when(wChunk === lastChunk) {
        when(wBeatLast) {
          wState := wResponse
        }.otherwise {
          wBeat := wBeat + 1.U
          wBeatValid := false.B
          wChunk := 0.U
          DownstreamByteIndex := 0.U
          wState := wDnBeat
        }
      }.otherwise {
        wChunk := wChunk + 1.U
        DownstreamByteIndex := 0.U
        wState := wDnSend
      }
    }

    switch(wState) {
      is(wIdle) {
        io.in.AW.AWREADY := true.B
        when(io.in.AW.AWVALID) {
          assert(
            io.in.AW.AWSIZE <= upLaneBits.U,
            "AXI5WidthBridge: AWSIZE exceeds upstream data bus width"
          )
          assert(
            io.in.AW.AWBURST =/= 3.U,
            "AXI5WidthBridge: reserved AWBURST encoding"
          )
          WID := io.in.AW.AWID
          wAddress := io.in.AW.AWADDR
          WLength := io.in.AW.AWLEN
          wSize := io.in.AW.AWSIZE
          wBurst := io.in.AW.AWBURST
          wProt := io.in.AW.AWPROT
          wCache := io.in.AW.AWCACHE
          wQos := io.in.AW.AWQOS
          wLock := InAWLock
          wError := incomingWError
          wBeat := 0.U
          wChunk := 0.U
          DownstreamByteIndex := 0.U
          wResponseCode := OKAY
          WAWSent := false.B
          wWDone := false.B
          wBeatValid := false.B
          WChunkAWSent := false.B
          wChunkWSent := false.B
          wState := Mux(
            incomingWError,
            wErrDrain,
            if (downsize) { wDnBeat }
            else { wUpsize }
          )
        }
      }

      is(wErrDrain) {
        // 地址越界时不下发任何 AW，但必须吃完整笔 W burst 再回 DECERR，
        // 否则上游会停在 W 通道上等一个永远不会到来的 B。
        io.in.W.WREADY := true.B
        when(io.in.W.WVALID && io.in.W.WLAST) {
          wState := wResponse
        }
      }

      is(wUpsize) {
        // 32->64：一个上游 beat 对应一个下游 beat，AW/AR 的 burst 形状
        // 保持不变。数据按当前 beat 地址搬到 64 位总线的对应半区。
        io.out.AW.AWVALID := !WAWSent
        io.out.AW.AWID := WID
        io.out.AW.AWADDR := downAddress(wAddress)
        io.out.AW.AWLEN := WLength
        io.out.AW.AWSIZE := wSize
        io.out.AW.AWBURST := wBurst
        io.out.AW.AWPROT := wProt
        io.out.AW.AWCACHE := wCache
        io.out.AW.AWQOS := wQos
        when(io.out.AW.AWVALID && io.out.AW.AWREADY) {
          WAWSent := true.B
        }

        val beatAddress = beatAddressOf(wAddress, wBeat, wSize, wBurst, WLength)
        val lane = upsizeLane(beatAddress)
        val sizeBytes = 1.U << wSize
        val partial = wBeat === 0.U || wBurst === 0.U
        val beatBytes =
          Mux(
            partial,
            sizeBytes - (beatAddress & (sizeBytes - 1.U)),
            sizeBytes
          )
        when(io.in.W.WVALID || wBeatValid) {
          assert(
            lane + beatBytes <= downBytes.U,
            "AXI5WidthBridge: upstream beat does not fit a downstream wide beat"
          )
        }
        when(!wBeatValid && !wWDone) {
          io.in.W.WREADY := true.B
          when(io.in.W.WVALID) {
            wBeatData := io.in.W.WDATA
            wBeatStrobe := io.in.W.WSTRB
            wBeatLast := io.in.W.WLAST
            wBeatValid := true.B
          }
        }.otherwise {
          io.out.W.WVALID := true.B
          io.out.W.WDATA := upDataToDown(wBeatData, lane)
          io.out.W.WSTRB := upStrobeToDown(wBeatStrobe, lane)
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

        when(wWDone && WAWSent) {
          wState := wResponse
        }
      }

      is(wDnBeat) {
        // 64->32：先把一个上游宽 beat 收到本地缓冲，再逐 4 字节块发出。
        // 这里不能直接透传，因为一个上游 beat 可能对应两笔下游 AW/B。
        io.in.W.WREADY := !wBeatValid
        when(!wBeatValid && io.in.W.WVALID) {
          wBeatData := io.in.W.WDATA
          wBeatStrobe := io.in.W.WSTRB
          wBeatLast := io.in.W.WLAST
          wBeatValid := true.B
          wChunk := 0.U
          DownstreamByteIndex := 0.U
          WChunkAWSent := false.B
          wChunkWSent := false.B
          wState := wDnSend
        }
      }

      is(wDnSend) {
        val beatAddress = beatAddressOf(wAddress, wBeat, wSize, wBurst, WLength)
        val byteBase = beatByteBase(beatAddress)
        val chunkAddress = chunkAddressOf(beatAddress, wChunk)
        val byteOffset = (chunkAddress - byteBase)(upLaneBits - 1, 0)
        val ChunkStrobe = extractStrobe(wBeatStrobe, byteOffset)
        val ChunkData = extractData(wBeatData, byteOffset)
        val FullWord = ChunkStrobe.andR
        val HasActiveByte = (ChunkStrobe >> DownstreamByteIndex).orR
        val ActiveByteIndex =
          DownstreamByteIndex + PriorityEncoder(ChunkStrobe >> DownstreamByteIndex)
        val TransactionValid = FullWord || HasActiveByte

        // 整 4 字节都有效时，仍发一个对齐的 4 字节事务；只要 WSTRB 只覆盖
        // 部分字节，就按真实字节地址逐字节发 AWSIZE=0。PSRAM 控制器用
        // AXI 地址的字节偏移选择写入位置，不按 WSTRB 调整字内偏移，因此
        // 发“字对齐地址 + 部分 WSTRB”会把字节写到字首。
        //
        // 字节事务的 WSTRB 保持在该字节原本的 lane 上，而不是搬到 lane0：
        // AWSIZE=0 时 WSTRB 的有效位必须等于地址的字节偏移；这样既满足
        // PSRAM 的地址语义，也让按 WSTRB 合并写的 32 位从设备（如 direct
        // AXIRAM）写到同一个字节。
        val DownstreamAddress =
          Mux(FullWord, chunkAddress, chunkAddress + ActiveByteIndex)
        val DownstreamData = ChunkData
        val DownstreamStrobe = Mux(
          FullWord,
          ChunkStrobe,
          (1.U(downBytes.W) << ActiveByteIndex)(downBytes - 1, 0)
        )

        io.out.AW.AWVALID := !WChunkAWSent && TransactionValid
        io.out.AW.AWID := WID
        io.out.AW.AWADDR := downAddress(DownstreamAddress)
        io.out.AW.AWLEN := 0.U
        io.out.AW.AWSIZE := Mux(FullWord, downLaneBits.U(3.W), 0.U(3.W))
        io.out.AW.AWBURST := 1.U
        io.out.AW.AWPROT := wProt
        io.out.AW.AWCACHE := wCache
        io.out.AW.AWQOS := wQos

        io.out.W.WVALID := !wChunkWSent && TransactionValid
        io.out.W.WDATA := DownstreamData
        io.out.W.WSTRB := DownstreamStrobe
        // 每个 chunk（或部分 chunk 内的每个字节）都是独立的 AWLEN=0
        // 单拍事务，WLAST 必须逐笔拉高；上游 burst 的最后一拍只用于
        // 决定何时聚合出上游 B。
        io.out.W.WLAST := true.B

        val AWFire = io.out.AW.AWVALID && io.out.AW.AWREADY
        val wFire = io.out.W.WVALID && io.out.W.WREADY
        when(AWFire) { WChunkAWSent := true.B }
        when(wFire) { wChunkWSent := true.B }
        when(TransactionValid &&
          (WChunkAWSent || AWFire) && (wChunkWSent || wFire)) {
          WChunkAWSent := false.B
          wChunkWSent := false.B
          // 记录本次真正发送的字节，wDnWaitB 从它的下一个有效字节继续。
          when(!FullWord) { DownstreamByteIndex := ActiveByteIndex }
          wState := wDnWaitB
        }
        // WSTRB=0 的 chunk 没有字节要写，不能发空事务，也不能等 B。
        when(!TransactionValid) { advanceWriteChunk() }
      }

      is(wDnWaitB) {
        val beatAddress = beatAddressOf(wAddress, wBeat, wSize, wBurst, WLength)
        val byteBase = beatByteBase(beatAddress)
        val chunkAddress = chunkAddressOf(beatAddress, wChunk)
        val byteOffset = (chunkAddress - byteBase)(upLaneBits - 1, 0)
        val ChunkStrobe = extractStrobe(wBeatStrobe, byteOffset)
        val FullWord = ChunkStrobe.andR
        // 部分 chunk 要按字节逐笔收 B；+& 保证下标 3 加一后仍能表示 4，
        // 否则 2 位加法回绕成 0，会把已经发过的字节再发一遍。
        val NextByteIndex = DownstreamByteIndex +& 1.U
        val HasRemainingByte = (ChunkStrobe >> NextByteIndex).orR

        io.out.B.BREADY := true.B
        when(io.out.B.BVALID) {
          assert(
            io.out.B.BID === WID,
            "AXI5WidthBridge: downstream write response ID mismatch"
          )
          when(io.out.B.BRESP =/= OKAY) {
            wResponseCode := io.out.B.BRESP
          }
          when(FullWord) {
            advanceWriteChunk()
          }.otherwise {
            when(HasRemainingByte) {
              DownstreamByteIndex := NextByteIndex(1, 0)
              wState := wDnSend
            }.otherwise {
              advanceWriteChunk()
            }
          }
        }
      }

      is(wResponse) {
        if (downsize) {
          // 64->32：wDnWaitB 已经逐块收齐所有下游 B，本地聚合成一个
          // 上游 B。越界事务没有下发 AW/W，也在这里返回 DECERR。
          io.in.B.BVALID := true.B
          io.in.B.BID := WID
          io.in.B.BRESP := Mux(wError, DECERR, wResponseCode)
          when(io.in.B.BREADY) {
            wState := wIdle
          }
        } else {
          when(wError) {
            // 地址越界：没有下发任何下游 AW/W，本地返回 DECERR，
            // 不能等待一个不存在的下游 B。
            io.in.B.BVALID := true.B
            io.in.B.BID := WID
            io.in.B.BRESP := DECERR
            when(io.in.B.BREADY) {
              wState := wIdle
            }
          }.otherwise {
            // 地址窄化/同宽/32->64 都已在上面下发下游 AW/W，必须等下游 B
            // 返回后再透传给上游；地址窄化本身不产生本地响应。
            io.in.B.BVALID := io.out.B.BVALID
            io.in.B.BID := io.out.B.BID
            io.in.B.BRESP := io.out.B.BRESP
            io.out.B.BREADY := io.in.B.BREADY
            when(io.out.B.BVALID) {
              assert(
                io.out.B.BID === WID,
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
      WTxnIssuedAW := false.B
      wDnWLastSeen := false.B
    }
    when(WDnAWFire) { WTxnIssuedAW := true.B }
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
    when(WDnAWFire =/= wDnBFire) {
      wDnOutstanding := Mux(
        WDnAWFire,
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
        io.out.B.BID === WID,
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
        io.in.B.BID === WID,
        "AXI5WidthBridge: upstream write response ID mismatch"
      )
      assert(
        wDnOutstanding === 0.U || io.out.B.BVALID,
        "AXI5WidthBridge: upstream B while downstream write is outstanding"
      )
      when(wError) {
        assert(
          !WTxnIssuedAW,
          "AXI5WidthBridge: local DECERR B after issuing downstream AW"
        )
      }
    }
    when(io.in.W.WVALID && io.in.W.WREADY && !wError) {
      assert(
        wBeat <= WLength,
        "AXI5WidthBridge: upstream W beat exceeds AWLEN"
      )
      assert(
        io.in.W.WLAST === (wBeat === WLength),
        "AXI5WidthBridge: upstream WLAST does not match AWLEN"
      )
    }

    if (TrackExclusive) {
      io.OutAWLock.get := wLock
    }
  }
}
