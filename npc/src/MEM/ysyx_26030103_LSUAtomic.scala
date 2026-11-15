package ysyx_26030103.mem

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._

/** LSU A 扩展原子事务：LR/SC/AMO 读改写状态分支与宽读完成逻辑。 */
private[mem] trait LSUAtomic {
  this: ysyx_26030103_LSU =>

  def finishWideAtomicRead(WideData: UInt, RespBad: Bool): Unit = {
    val Fault = AccessFaultReg || RespBad
    when(Fault) {
      TransactionComplete := true.B
      TransactionFault := true.B
      AtomicActive := false.B
      state := StatesIdle
    }.elsewhen(ysyx_26030103_AtomicOp.IsLr(AtomicOpReg)) {
      LoadDataReg := WideData
      ReservationValid := true.B
      ReservationAddr := AtomicAddrReg
      AtomicActive := false.B
      TransactionComplete := true.B
      state := StatesIdle
    }.otherwise {
      AtomicResultData := WideData
      AtomicNewData := ysyx_26030103_AtomicOp.Compute(
        AtomicOpReg, WideData, ActiveInstruction.StoreData,
        word = false)
      AtomicAWDone := false.B
      AtomicWDone := false.B
      AtomicWBeat := false.B
      state := StatesAtomicWriteRequest
    }
  }

  def atomicQuietBody(): Unit = {
    // 等到物理地址（VIPT 并行）与总线都安静后，才允许发第一笔请求。
    // AtomicBusy 已在本状态之前拉高，仲裁器会挡住新的 DMA 读写。
    // DCache 可能正在后台完成一次 load miss 的回填；此时 AR/R 仍被它
    // 占用，必须等 axi_active 与 DCacheRequestActive 都归零，否则原子
    // 会把 DCache 的 ARREADY/R beat 误当成自己的事务。
    when(AtomicPAddrReady && io.BusQuiet && !DCacheBusActive) {
      AtomicAddrReg := AddrOf(EffectiveALUResult)
      when(ysyx_26030103_AtomicOp.IsSc(AtomicOpReg)) {
        // SC 失败路径完全不碰内存：rd=1，清除 reservation。
        when(ReservationValid && ReservationAddr === AddrOf(EffectiveALUResult)) {
          AtomicResultData := 0.U
          AtomicNewData := ActiveInstruction.StoreData
          AtomicAWDone := false.B
          AtomicWDone := false.B
          AtomicWBeat := false.B
          state := StatesAtomicWriteRequest
        }.otherwise {
          ReservationValid := false.B
          LoadDataReg := 1.U
          AtomicActive := false.B
          TransactionComplete := true.B
          state := StatesIdle
        }
      }.otherwise {
        // LR/AMO 先读。
        AtomicReadBeat := 0.U
        AtomicReadLow := 0.U
        AtomicReadFaultReg := false.B
        state := StatesAtomicReadRequest
      }
    }
  }

  def atomicReadRequestBody(): Unit = {
    io.DataBus.AR.ARVALID := true.B
    io.DataBus.AR.ARADDR := AtomicAddrReg
    io.DataBus.AR.ARLEN := Mux(AtomicWide && !BusWideBit, 1.U, 0.U)
    io.DataBus.AR.ARSIZE := Mux(AtomicWide && BusWideBit, 3.U, 2.U)
    io.DataBus.AR.ARBURST := 1.U
    // 只有 AR 确实由原子事务驱动时，ARREADY 才算自己的握手。
    when(!DCacheBusActive && io.DataBus.AR.ARVALID &&
        io.DataBus.AR.ARREADY) {
      state := StatesAtomicReadResponse
    }
  }

  def atomicReadResponseBody(): Unit = {
    io.DataBus.R.RREADY := true.B
    when(io.DataBus.R.RVALID) {
      val RespBad = io.DataBus.R.RRESP =/= 0.U
      when(RespBad) {
        AccessFaultReg := true.B
        AccessFaultRespReg := io.DataBus.R.RRESP
        AtomicReadFaultReg := true.B
      }
      if (XLEN == 64) {
        when(AtomicWide && BusWideBit) {
          when(io.DataBus.R.RLAST) {
            finishWideAtomicRead(io.DataBus.R.RDATA, RespBad)
          }.otherwise {
            AccessFaultReg := true.B
            AtomicReadFaultReg := true.B
            AccessFaultRespReg := Mux(RespBad, io.DataBus.R.RRESP, 2.U)
            state := StatesReadDrain
          }
        }.elsewhen(AtomicWide) {
          when(AtomicReadBeat === 0.U) {
            when(io.DataBus.R.RLAST) {
              // 两拍读在第一个响应就结束：协议错误，按访问故障提交。
              AccessFaultReg := true.B
              AccessFaultRespReg := Mux(RespBad,
                io.DataBus.R.RRESP, 2.U)
              AtomicReadFaultReg := true.B
              TransactionComplete := true.B
              TransactionFault := true.B
              AtomicActive := false.B
              state := StatesIdle
            }.otherwise {
              AtomicReadLow := io.DataBus.R.RDATA
              AtomicReadBeat := 1.U
            }
          }.otherwise {
            when(io.DataBus.R.RLAST) {
              finishWideAtomicRead(
                Cat(io.DataBus.R.RDATA, AtomicReadLow), RespBad)
            }.otherwise {
              // 超长 burst：继续排空到 RLAST，最终按访问故障提交。
              AccessFaultReg := true.B
              AtomicReadFaultReg := true.B
              AccessFaultRespReg := Mux(RespBad,
                io.DataBus.R.RRESP, 2.U)
              state := StatesReadDrain
            }
          }
        }.otherwise {
          when(io.DataBus.R.RLAST) {
            val WordData =
              AtomicSext32(BusSelectWord(io.DataBus.R.RDATA, AtomicAddrReg))
            val Fault = AccessFaultReg || RespBad
            when(Fault) {
              TransactionComplete := true.B
              TransactionFault := true.B
              AtomicActive := false.B
              state := StatesIdle
            }.elsewhen(ysyx_26030103_AtomicOp.IsLr(AtomicOpReg)) {
              // LR 成功：建立 reservation，写回旧值。
              LoadDataReg := WordData
              ReservationValid := true.B
              ReservationAddr := AtomicAddrReg
              AtomicActive := false.B
              TransactionComplete := true.B
              state := StatesIdle
            }.otherwise {
              AtomicResultData := WordData
              AtomicNewData := ysyx_26030103_AtomicOp.Compute(
                AtomicOpReg, WordData, ActiveInstruction.StoreData,
                word = true)
              AtomicAWDone := false.B
              AtomicWDone := false.B
              AtomicWBeat := false.B
              state := StatesAtomicWriteRequest
            }
          }.otherwise {
            AccessFaultReg := true.B
            AtomicReadFaultReg := true.B
            AccessFaultRespReg := Mux(RespBad, io.DataBus.R.RRESP, 2.U)
            state := StatesReadDrain
          }
        }
      } else {
        when(io.DataBus.R.RLAST) {
          val WordData =
            BusSelectWord(io.DataBus.R.RDATA, AtomicAddrReg)
          val Fault = AccessFaultReg || RespBad
          when(Fault) {
            TransactionComplete := true.B
            TransactionFault := true.B
            AtomicActive := false.B
            state := StatesIdle
          }.elsewhen(ysyx_26030103_AtomicOp.IsLr(AtomicOpReg)) {
            LoadDataReg := WordData
            ReservationValid := true.B
            ReservationAddr := AtomicAddrReg
            AtomicActive := false.B
            TransactionComplete := true.B
            state := StatesIdle
          }.otherwise {
            AtomicResultData := WordData
            AtomicNewData := ysyx_26030103_AtomicOp.Compute(
              AtomicOpReg, WordData, ActiveInstruction.StoreData,
              word = true)
            AtomicAWDone := false.B
            AtomicWDone := false.B
            AtomicWBeat := false.B
            state := StatesAtomicWriteRequest
          }
        }.otherwise {
          AccessFaultReg := true.B
          AtomicReadFaultReg := true.B
          AccessFaultRespReg := Mux(RespBad, io.DataBus.R.RRESP, 2.U)
          state := StatesReadDrain
        }
      }
    }
  }

  def atomicWriteRequestBody(): Unit = {
    val AtomicWideBus = AtomicWide && BusWideBit
    val AtomicTwoBeat = AtomicWide && !BusWideBit
    val AtomicHigh =
      if (XLEN == 64) AtomicNewData(63, 32) else 0.U(32.W)
    io.DataBus.AW.AWVALID := !AtomicAWDone
    io.DataBus.AW.AWADDR := AtomicAddrReg
    io.DataBus.AW.AWLEN := Mux(AtomicTwoBeat, 1.U, 0.U)
    io.DataBus.AW.AWSIZE := Mux(AtomicWideBus, 3.U, 2.U)
    io.DataBus.AW.AWBURST := 1.U
    io.DataBus.W.WVALID := !AtomicWDone
    io.DataBus.W.WDATA :=
      Mux(AtomicWideBus, Cat(AtomicHigh, AtomicNewData(31, 0)),
        Mux(AtomicTwoBeat && AtomicWBeat, AtomicHigh,
          BusWord(AtomicNewData(31, 0), AtomicAddrReg)))
    io.DataBus.W.WSTRB :=
      Mux(AtomicWideBus, "hff".U,
        Mux(AtomicTwoBeat && AtomicWBeat, "hf".U,
          BusStrb("hf".U, AtomicAddrReg)))
    io.DataBus.W.WLAST :=
      !AtomicWide || AtomicWideBus || AtomicWBeat
    val AtomicAWFire =
      io.DataBus.AW.AWVALID && io.DataBus.AW.AWREADY
    val AtomicWFire =
      io.DataBus.W.WVALID && io.DataBus.W.WREADY
    val AtomicWFinishes =
      AtomicWFire && (!AtomicWide || AtomicWideBus || AtomicWBeat)
    when(AtomicAWFire) { AtomicAWDone := true.B }
    when(AtomicWFire) {
      when(AtomicTwoBeat && !AtomicWBeat) { AtomicWBeat := true.B }
        .otherwise { AtomicWDone := true.B }
    }
    when((AtomicAWDone || AtomicAWFire) &&
        (AtomicWDone || AtomicWFinishes)) {
      state := StatesAtomicWriteWaitB
    }
  }

  def atomicWriteWaitResponseBody(): Unit = {
    io.DataBus.B.BREADY := true.B
    when(io.DataBus.B.BVALID) {
      assert(io.DataBus.B.BID === 0.U,
        "atomic write received an unexpected BID")
      val Fault = io.DataBus.B.BRESP =/= 0.U
      when(Fault) {
        AccessFaultReg := true.B
        AccessFaultRespReg := io.DataBus.B.BRESP
      }
      // 成功时写回旧值（AMO）或 0（SC）；失败时 MemFaultReg 会屏蔽写回。
      LoadDataReg := AtomicResultData
      // AMO 也是同 hart store，写完成后 reservation 必须失效。
      ReservationValid := false.B
      // 无论 BRESP 是否正确都失效缓存，覆盖从设备可能的部分写副作用。
      AtomicWriteComplete := true.B
      AtomicActive := false.B
      TransactionComplete := true.B
      TransactionFault := Fault
      state := StatesIdle
    }
  }
}
