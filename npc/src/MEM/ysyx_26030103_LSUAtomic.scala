package ysyx_26030103.mem

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._

private[mem] trait LSUAtomic {
  this: ysyx_26030103_LSU =>

  def finishWideAtomicRead(wideData: UInt, responseBad: Bool): Unit = {
    val fault = accessFaultReg || responseBad
    when(fault) {
      transactionComplete := true.B
      transactionFault := true.B
      atomicActive := false.B
      state := stateIdle
    }.elsewhen(ysyx_26030103_AtomicOp.IsLr(atomicOpReg)) {
      loadDataReg := wideData
      reservationValid := true.B
      reservationAddress := atomicAddressReg
      atomicActive := false.B
      transactionComplete := true.B
      state := stateIdle
    }.otherwise {
      atomicResultData := wideData
      atomicNewData := ysyx_26030103_AtomicOp.Compute(
        atomicOpReg, wideData, activeInstruction.StoreData,
        word = false)
      AtomicAWDone := false.B
      atomicWDone := false.B
      atomicWBeat := false.B
      state := stateAtomicWriteRequest
    }
  }

  def atomicQuietBody(): Unit = {
    when(atomicPhysicalAddressReady && io.BusQuiet && !dataCacheBusActive) {
      atomicAddressReg := addressOf(EffectiveALUResult)
      when(ysyx_26030103_AtomicOp.IsSc(atomicOpReg)) {
        // SC 失败路径完全不碰内存：rd=1，清除 reservation。
        when(reservationValid && reservationAddress === addressOf(EffectiveALUResult)) {
          atomicResultData := 0.U
          atomicNewData := activeInstruction.StoreData
          AtomicAWDone := false.B
          atomicWDone := false.B
          atomicWBeat := false.B
          state := stateAtomicWriteRequest
        }.otherwise {
          reservationValid := false.B
          loadDataReg := 1.U
          atomicActive := false.B
          transactionComplete := true.B
          state := stateIdle
        }
      }.otherwise {
        // LR/AMO 先读。
        atomicReadBeat := 0.U
        atomicReadLow := 0.U
        atomicReadFaultReg := false.B
        state := stateAtomicReadRequest
      }
    }
  }

  def atomicReadRequestBody(): Unit = {
    io.DataBus.AR.ARVALID := true.B
    io.DataBus.AR.ARADDR := atomicAddressReg
    io.DataBus.AR.ARLEN := Mux(atomicWide && !busWideBit, 1.U, 0.U)
    io.DataBus.AR.ARSIZE := Mux(atomicWide && busWideBit, 3.U, 2.U)
    io.DataBus.AR.ARBURST := 1.U
    // 只有 AR 确实由原子事务驱动时，ARREADY 才算自己的握手。
    when(!dataCacheBusActive && io.DataBus.AR.ARVALID &&
        io.DataBus.AR.ARREADY) {
      state := stateAtomicReadResponse
    }
  }

  def atomicReadResponseBody(): Unit = {
    io.DataBus.R.RREADY := true.B
    when(io.DataBus.R.RVALID) {
      val responseBad = io.DataBus.R.RRESP =/= 0.U
      when(responseBad) {
        accessFaultReg := true.B
        accessFaultResponseReg := io.DataBus.R.RRESP
        atomicReadFaultReg := true.B
      }
      if (XLEN == 64) {
        when(atomicWide && busWideBit) {
          when(io.DataBus.R.RLAST) {
            finishWideAtomicRead(io.DataBus.R.RDATA, responseBad)
          }.otherwise {
            accessFaultReg := true.B
            atomicReadFaultReg := true.B
            accessFaultResponseReg := Mux(responseBad, io.DataBus.R.RRESP, 2.U)
            state := stateReadDrain
          }
        }.elsewhen(atomicWide) {
          when(atomicReadBeat === 0.U) {
            when(io.DataBus.R.RLAST) {
              // 两拍读在第一个响应就结束：协议错误，按访问故障提交。
              accessFaultReg := true.B
              accessFaultResponseReg := Mux(responseBad,
                io.DataBus.R.RRESP, 2.U)
              atomicReadFaultReg := true.B
              transactionComplete := true.B
              transactionFault := true.B
              atomicActive := false.B
              state := stateIdle
            }.otherwise {
              atomicReadLow := io.DataBus.R.RDATA
              atomicReadBeat := 1.U
            }
          }.otherwise {
            when(io.DataBus.R.RLAST) {
              finishWideAtomicRead(
                Cat(io.DataBus.R.RDATA, atomicReadLow), responseBad)
            }.otherwise {
              // 超长 burst：继续排空到 RLAST，最终按访问故障提交。
              accessFaultReg := true.B
              atomicReadFaultReg := true.B
              accessFaultResponseReg := Mux(responseBad,
                io.DataBus.R.RRESP, 2.U)
              state := stateReadDrain
            }
          }
        }.otherwise {
          when(io.DataBus.R.RLAST) {
            val wordData =
              atomicSignExtend32(busSelectWord(io.DataBus.R.RDATA, atomicAddressReg))
            val fault = accessFaultReg || responseBad
            when(fault) {
              transactionComplete := true.B
              transactionFault := true.B
              atomicActive := false.B
              state := stateIdle
            }.elsewhen(ysyx_26030103_AtomicOp.IsLr(atomicOpReg)) {
              // LR 成功：建立 reservation，写回旧值。
              loadDataReg := wordData
              reservationValid := true.B
              reservationAddress := atomicAddressReg
              atomicActive := false.B
              transactionComplete := true.B
              state := stateIdle
            }.otherwise {
              atomicResultData := wordData
              atomicNewData := ysyx_26030103_AtomicOp.Compute(
                atomicOpReg, wordData, activeInstruction.StoreData,
                word = true)
              AtomicAWDone := false.B
              atomicWDone := false.B
              atomicWBeat := false.B
              state := stateAtomicWriteRequest
            }
          }.otherwise {
            accessFaultReg := true.B
            atomicReadFaultReg := true.B
            accessFaultResponseReg := Mux(responseBad, io.DataBus.R.RRESP, 2.U)
            state := stateReadDrain
          }
        }
      } else {
        when(io.DataBus.R.RLAST) {
          val wordData =
            busSelectWord(io.DataBus.R.RDATA, atomicAddressReg)
          val fault = accessFaultReg || responseBad
          when(fault) {
            transactionComplete := true.B
            transactionFault := true.B
            atomicActive := false.B
            state := stateIdle
          }.elsewhen(ysyx_26030103_AtomicOp.IsLr(atomicOpReg)) {
            loadDataReg := wordData
            reservationValid := true.B
            reservationAddress := atomicAddressReg
            atomicActive := false.B
            transactionComplete := true.B
            state := stateIdle
          }.otherwise {
            atomicResultData := wordData
            atomicNewData := ysyx_26030103_AtomicOp.Compute(
              atomicOpReg, wordData, activeInstruction.StoreData,
              word = true)
            AtomicAWDone := false.B
            atomicWDone := false.B
            atomicWBeat := false.B
            state := stateAtomicWriteRequest
          }
        }.otherwise {
          accessFaultReg := true.B
          atomicReadFaultReg := true.B
          accessFaultResponseReg := Mux(responseBad, io.DataBus.R.RRESP, 2.U)
          state := stateReadDrain
        }
      }
    }
  }

  def atomicWriteRequestBody(): Unit = {
    val atomicWideBus = atomicWide && busWideBit
    val atomicTwoBeat = atomicWide && !busWideBit
    val atomicHigh =
      if (XLEN == 64) atomicNewData(63, 32) else 0.U(32.W)
    io.DataBus.AW.AWVALID := !AtomicAWDone
    io.DataBus.AW.AWADDR := atomicAddressReg
    io.DataBus.AW.AWLEN := Mux(atomicTwoBeat, 1.U, 0.U)
    io.DataBus.AW.AWSIZE := Mux(atomicWideBus, 3.U, 2.U)
    io.DataBus.AW.AWBURST := 1.U
    io.DataBus.W.WVALID := !atomicWDone
    io.DataBus.W.WDATA :=
      Mux(atomicWideBus, Cat(atomicHigh, atomicNewData(31, 0)),
        Mux(atomicTwoBeat && atomicWBeat, atomicHigh,
          busWord(atomicNewData(31, 0), atomicAddressReg)))
    io.DataBus.W.WSTRB :=
      Mux(atomicWideBus, "hff".U,
        Mux(atomicTwoBeat && atomicWBeat, "hf".U,
          busStrobe("hf".U, atomicAddressReg)))
    io.DataBus.W.WLAST :=
      !atomicWide || atomicWideBus || atomicWBeat
    val AtomicAWFire =
      io.DataBus.AW.AWVALID && io.DataBus.AW.AWREADY
    val atomicWFire =
      io.DataBus.W.WVALID && io.DataBus.W.WREADY
    val atomicWFinishes =
      atomicWFire && (!atomicWide || atomicWideBus || atomicWBeat)
    when(AtomicAWFire) { AtomicAWDone := true.B }
    when(atomicWFire) {
      when(atomicTwoBeat && !atomicWBeat) { atomicWBeat := true.B }
        .otherwise { atomicWDone := true.B }
    }
    when((AtomicAWDone || AtomicAWFire) &&
        (atomicWDone || atomicWFinishes)) {
      state := stateAtomicWriteWaitB
    }
  }

  def atomicWriteWaitResponseBody(): Unit = {
    io.DataBus.B.BREADY := true.B
    when(io.DataBus.B.BVALID) {
      assert(io.DataBus.B.BID === 0.U,
        "atomic write received an unexpected BID")
      val fault = io.DataBus.B.BRESP =/= 0.U
      when(fault) {
        accessFaultReg := true.B
        accessFaultResponseReg := io.DataBus.B.BRESP
      }
      // 成功时写回旧值（AMO）或 0（SC）；失败时 MemFaultReg 会屏蔽写回。
      loadDataReg := atomicResultData
      // AMO 也是同 hart store，写完成后 reservation 必须失效。
      reservationValid := false.B
      // 无论 BRESP 是否正确都失效缓存，覆盖从设备可能的部分写副作用。
      atomicWriteComplete := true.B
      atomicActive := false.B
      transactionComplete := true.B
      transactionFault := fault
      state := stateIdle
    }
  }
}
