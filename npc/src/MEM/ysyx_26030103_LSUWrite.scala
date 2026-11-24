package ysyx_26030103.mem

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_AXI5Lane.{
  LaneStrobe, LaneWord
}

private[mem] trait LSUWrite {
  this: ysyx_26030103_LSU =>

  def writeBufferBody(): Unit = {
    // Idle 可直接驱动已经寄存的队首，避免“发现非空→切 Issue→下一拍才
    // 拉 VALID”的固定空拍。失败/非对齐/等待 PA 的队首会阻断所有年轻副作用。
    val wbCanStart = !WBufEmpty && wbHeadReady && !wbHeadTrapBlocked &&
      !io.PMPFault && !storeFaultReg && !memFaultReg
    val wbDrivingIssue = wbState === wbIssue || (wbState === wbIdle && wbCanStart)
    val wbHeadIsWide = wbHeadWide
    // 对齐宽访问仍沿用原有的两拍逻辑；分段访问按配置粒度发请求。
    val wbHeadBusWide = wbHeadIsWide && busWideBit && !wbHeadMisaligned
    val wbHeadTwoBeat = wbHeadIsWide && !busWideBit && !wbHeadMisaligned
    val wbSegWide = wbHeadMisaligned && (ChunkLog2 == 3).B
    val wbSegBusWide = wbSegWide && busWideBit
    val wbSegTwoBeat = wbSegWide && !busWideBit
    val wbHeadTwoBeatAny = wbHeadTwoBeat || wbSegTwoBeat
    val wbBeat = RegInit(0.U(1.W))
    val wbSegData = wbHeadSegPayload._1
    val wbSegStrobe = wbHeadSegPayload._2
    val wbSegHighData =
      if (ChunkLog2 == 3) wbSegData(63, 32) else 0.U(32.W)
    val wbSegHighStrobe =
      (wbSegStrobe >> (ChunkBytes / 2))(ChunkBytes / 2 - 1, 0)
    when(wbDrivingIssue) {
      io.DataBus.AW.AWVALID := !WbAWDone
      io.DataBus.AW.AWADDR :=
        Mux(wbHeadMisaligned, wbHeadSegPaddr, wbHeadEffectiveAddress)
      io.DataBus.AW.AWLEN :=
        Mux(wbHeadTwoBeat || wbSegTwoBeat, 1.U, 0.U)
      io.DataBus.AW.AWSIZE :=
        Mux(wbHeadMisaligned, Mux(wbSegBusWide, 3.U, 2.U),
          Mux(wbHeadBusWide, 3.U, wbHeadSize))
      io.DataBus.AW.AWBURST := 1.U
      io.DataBus.W.WVALID := !wbWDone
      io.DataBus.W.WDATA :=
        Mux(wbHeadMisaligned,
          Mux(wbSegBusWide, wbSegData,
            Mux(wbSegTwoBeat && wbBeat === 1.U, wbSegHighData,
              LaneWord(wbSegData(31, 0), wbHeadSegPaddr, busWide))),
          Mux(wbHeadBusWide, Cat(wbHeadDataHi, wbHeadData),
            Mux(wbHeadTwoBeat && wbBeat === 1.U, wbHeadDataHi,
              LaneWord(wbHeadData, wbHeadEffectiveAddress, busWide))))
      io.DataBus.W.WSTRB :=
        Mux(wbHeadMisaligned,
          Mux(wbSegBusWide, wbSegStrobe,
            Mux(wbSegTwoBeat && wbBeat === 1.U, wbSegHighStrobe,
              LaneStrobe(wbSegStrobe(3, 0), wbHeadSegPaddr, busWide))),
          Mux(wbHeadBusWide, "hff".U,
            Mux(wbHeadTwoBeat && wbBeat === 1.U, "hf".U,
              LaneStrobe(wbHeadStrobe, wbHeadEffectiveAddress, busWide))))
      io.DataBus.W.WLAST :=
        Mux(wbHeadMisaligned, !wbSegTwoBeat || wbBeat === 1.U,
          !wbHeadIsWide || wbHeadBusWide || wbBeat === 1.U)
    }
    // 本拍 W 握手是否完成整笔 store 的全部数据拍。
    val wbWFinishes = wbWfire && Mux(
      wbHeadMisaligned,
      !wbSegTwoBeat || wbBeat === 1.U,
      !wbHeadIsWide || wbHeadBusWide || wbBeat === 1.U
    )
    switch(wbState) {
      is(wbIdle) {
        when(wbCanStart) {
          when(wbAWfire) { WbAWDone := true.B }
          when(wbWfire) {
            when(wbHeadTwoBeatAny && wbBeat === 0.U) { wbBeat := 1.U }
              .otherwise { wbWDone := true.B }
          }
          when(wbAWfire && wbWFinishes) {
            wbState := wbWaitB
          }.otherwise {
            wbState := wbIssue
          }
        }.otherwise {
          WbAWDone := false.B
          wbWDone := false.B
          wbBeat := 0.U
        }
      }
      is(wbIssue) {
        when(wbAWfire) { WbAWDone := true.B }
        when(wbWfire) {
          when(wbHeadTwoBeatAny && wbBeat === 0.U) { wbBeat := 1.U }
            .otherwise { wbWDone := true.B }
        }
        when((WbAWDone || wbAWfire) && (wbWDone || wbWFinishes)) {
          wbState := wbWaitB
        }
      }
      is(wbWaitB) {
        io.DataBus.B.BREADY := wbBReady
        // B 响应既决定写缓冲出队，也是 store 的精确退休点。
        when(wbBFire) {
          assert(
            io.DataBus.B.BID === 0.U,
            "LSU received a B response with an unexpected BID"
          )
          // 成功 B 后下一拍可直接驱动新队首，不插空拍。
          when(wbBMatch) {
            wbState := wbIdle
            WbAWDone := false.B
            wbWDone := false.B
            when(wbHeadMisaligned && !wbLastSegment) {
              // 中间段完成：继续同一 store 的下一段。
              wbSegIndex := wbSegIndex + 1.U
            }
          }
        }
      }
    }
    when(wbPop) {
      wbBeat := 0.U
      wbSegIndex := 0.U
      if (WBufDepth == 1) {
        wbHead := 0.U
      } else {
        wbHead := wbHead + 1.U
      }
    }
    when(memTrapCommit) {
      wbCount := 0.U
      wbHead := 0.U
      wbTail := 0.U
      wbState := wbIdle
      WbAWDone := false.B
      wbWDone := false.B
      wbBeat := 0.U
      wbSegIndex := 0.U
    }.otherwise {
      wbCount := wbCount + wbPush.asUInt - wbPop.asUInt
    }
  }

  def idleBody(): Unit = {
    when(startMem) {
      // 这些寄存器属于已接受的访存指令。总线状态机返回Idle后仍要保持其稳定，
      // 直到该级能在下游反压下真正提交。
      accessFaultReg := false.B
      accessFaultResponseReg := 0.U
      storeFaultReg := false.B
      loadBeat := 0.U
      isStoreTransaction := stageInputBits.MemoryWrite
      when(stageInputMisaligned || StageInputPMPFault) {
        // 非对齐/PMP 故障项只作为写缓冲屏障，不产生任何 AXI 请求，
        // 随后按 cause 4/6 或 5/7 精确提交。
        state := stateIdle
      }
        .elsewhen(stageInputBits.IsAtomic) {
          // LR/SC/AMO：先拉高 AtomicBusy 并等待总线上的 DMA 排空，
          // 再以独占读改写事务访问内存，完全绕开 DCache/写缓冲。
          atomicActive := true.B
          atomicOpReg := stageInputBits.AtomicOp
          atomicWide := stageInputBits.WidthSelect === "b11".U
          atomicAddressReg := 0.U
          atomicNewData := 0.U
          atomicResultData := 0.U
          atomicReadLow := 0.U
          atomicReadBeat := 0.U
          AtomicAWDone := false.B
          atomicWDone := false.B
          atomicWBeat := false.B
          state := stateAtomicQuiet
        }
        .elsewhen(stageInputBits.MemoryWrite) {
          // 所有 store（包括普通 RAM）都要等属于自己的 B 响应。
          state := stateWriteWaitB
        }
        .otherwise {
          // 非对齐或跨页普通 load 走 4B 段循环；其余保持原有时序。
          when(StageInputNeedsSegments) {
            MisalignedSegmentIndex := 0.U
            MisalignedWordRegisters.foreach(_ := 0.U)
            MisalignedBeat := 0.U
            MisalignedLowWordRegister := 0.U
            if (DCacheEnable) { dataCacheRequestActive := true.B }
            state := stateMisLoadRequest
          }.otherwise {
            if (DCacheEnable) {
              // 精确 store 队列只在全部老 store 已处理后才让 load 成为
              // StageInput，因此这里不再经过不可达的 LoadWaitBuf 空状态。
              dataCacheRequestActive := true.B
              state := stateReadRequest
            } else { // 关闭数据缓存时保持原来的单拍直读路径
              state := stateReadRequest
            }
          }
        }
    }
  }

  def storeWaitResponseBody(): Unit = {
    // 等到自己的写事务拿到B响应: 之前的项都已按序完成, 队首就是本指令的项
    when(wbPop && wbHead === storeBufferIndex) {
      storeFaultReg := io.DataBus.B.BRESP =/= 0.U
      accessFaultResponseReg := io.DataBus.B.BRESP
      transactionComplete := true.B
      transactionFault := io.DataBus.B.BRESP =/= 0.U
      state := stateIdle
    }
  }
}
