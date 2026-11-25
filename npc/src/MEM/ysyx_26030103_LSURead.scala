package ysyx_26030103.mem

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_AXI5Lane.SelectWord

private[mem] trait LSURead {
  this: ysyx_26030103_LSU =>

  def readRequestBody(): Unit = {
    if (DCacheEnable) {
      // DCache 使能时所有 load 都由 DCache 受理，LSU 不直接发 AXI 读。
      when(dataCacheRequestActive) {
        when(DCache.io.request.fire) { state := stateReadResponse }
      }
    } else {
      io.DataBus.AR.ARVALID := true.B
      io.DataBus.AR.ARADDR := addressOf(activeInstruction.ALUResult)
      io.DataBus.AR.ARLEN := Mux(readIsWide && !busWideBit, 1.U, 0.U)
      io.DataBus.AR.ARSIZE := Mux(readIsWide && busWideBit, 3.U, AXISize)
      when(io.DataBus.AR.ARREADY) { state := stateReadResponse }
    }
  }

  def readResponseBody(): Unit = {
    if (DCacheEnable) {
      when(dataCacheRequestActive) {
        when(DCache.io.response.fire) {
          loadForwardValid := true.B
          loadForwardData := DCache.io.response.bits.data
          loadForwardFault := DCache.io.response.bits.fault
          when(DCache.io.response.bits.fault) {
            accessFaultReg := true.B
            accessFaultResponseReg := DCache.io.response.bits.FaultResponse
          }.otherwise {
            loadDataReg := DCache.io.response.bits.data
          }
          dataCacheRequestActive := false.B
          transactionComplete := true.B
          transactionFault := DCache.io.response.bits.fault
          state := stateIdle
        }
      }
    } else {
      io.DataBus.R.RREADY := true.B
      when(io.DataBus.R.RVALID) {
        when(io.DataBus.R.RRESP =/= 0.U) {
          accessFaultReg := true.B
          accessFaultResponseReg := io.DataBus.R.RRESP
        }
        if (wideMem && !busWide) {
          when(readIsWide) {
            when(loadBeat === 0.U) {
              when(io.DataBus.R.RLAST) {
                // 两拍读在第一个响应就结束：按协议错误提交访问故障。
                accessFaultReg := true.B
                accessFaultResponseReg := Mux(
                  io.DataBus.R.RRESP =/= 0.U, io.DataBus.R.RRESP, 2.U)
                transactionComplete := true.B
                transactionFault := true.B
                state := stateIdle
              }.otherwise {
                loadWord0Reg := io.DataBus.R.RDATA
                loadBeat := 1.U
              }
            }.otherwise {
              when(io.DataBus.R.RLAST) {
                val finalData = directLoadData
                when(!(accessFaultReg || io.DataBus.R.RRESP =/= 0.U)) {
                  loadDataReg := finalData
                }
                loadForwardValid := true.B
                loadForwardData := finalData
                loadForwardFault := accessFaultReg || io.DataBus.R.RRESP =/= 0.U
                transactionComplete := true.B
                transactionFault := accessFaultReg || io.DataBus.R.RRESP =/= 0.U
                state := stateIdle
              }.otherwise {
                accessFaultReg := true.B
                accessFaultResponseReg := Mux(
                  io.DataBus.R.RRESP =/= 0.U, io.DataBus.R.RRESP, 2.U)
                state := stateReadDrain
              }
            }
          }.otherwise {
            when(io.DataBus.R.RLAST) {
              val finalData = directLoadData
              when(!(accessFaultReg || io.DataBus.R.RRESP =/= 0.U)) {
                loadDataReg := finalData
              }
              loadForwardValid := true.B
              loadForwardData := finalData
              loadForwardFault := accessFaultReg || io.DataBus.R.RRESP =/= 0.U
              transactionComplete := true.B
              transactionFault := accessFaultReg || io.DataBus.R.RRESP =/= 0.U
              state := stateIdle
            }.otherwise {
              accessFaultReg := true.B
              accessFaultResponseReg := Mux(
                io.DataBus.R.RRESP =/= 0.U, io.DataBus.R.RRESP, 2.U)
              state := stateReadDrain
            }
          }
        } else {
          when(io.DataBus.R.RLAST) {
            val finalData = directLoadData
            when(!(accessFaultReg || io.DataBus.R.RRESP =/= 0.U)) {
              loadDataReg := finalData
            }
            loadForwardValid := true.B
            loadForwardData := finalData
            loadForwardFault := accessFaultReg || io.DataBus.R.RRESP =/= 0.U
            transactionComplete := true.B
            transactionFault := accessFaultReg || io.DataBus.R.RRESP =/= 0.U
            state := stateIdle
          }.otherwise {
            accessFaultReg := true.B
            accessFaultResponseReg := Mux(
              io.DataBus.R.RRESP =/= 0.U, io.DataBus.R.RRESP, 2.U)
            state := stateReadDrain
          }
        }
      }
    }
  }

  /** 非对齐 load：按配置粒度分块，最后一块到达后拼装。 */
  def MisalignedLoadRequestBody(): Unit = {
    if (DCacheEnable) {
      when(MisalignedSegmentReady && DCache.io.request.fire) {
        state := stateMisLoadResponse
      }
    } else {
      io.DataBus.AR.ARVALID := MisalignedSegmentReady
      io.DataBus.AR.ARADDR := MisalignedSegmentPhysicalAddress
      io.DataBus.AR.ARLEN := 0.U
      io.DataBus.AR.ARSIZE :=
        (if (ChunkLog2 == 3) 3.U else 2.U)
      io.DataBus.AR.ARBURST := 1.U
      when(MisalignedSegmentReady && io.DataBus.AR.ARREADY) {
        state := stateMisLoadResponse
      }
    }
  }

  private def MisalignedLoadSegment(index: Int, incoming: UInt): UInt =
    Mux(MisalignedSegmentIndex === index.U, incoming, MisalignedWordRegisters(index))

  private def finishMisLoad(incoming: UInt): Unit = {
    val segments = (0 until maxSegments).map(i => MisalignedLoadSegment(i, incoming))
    val assembled = assembleSegmentedLoad(
      segments,
      activeVa,
      activeInstruction.WidthSelect,
      activeInstruction.LoadSigned,
      ChunkLog2
    )
    loadDataReg := assembled
    loadForwardValid := true.B
    loadForwardData := assembled
    loadForwardFault := false.B
    transactionComplete := true.B
    transactionFault := false.B
    dataCacheRequestActive := false.B
    state := stateIdle
  }

  private def MisalignedLoadFault(response: UInt): Unit = {
    accessFaultReg := true.B
    accessFaultResponseReg := response
    transactionComplete := true.B
    transactionFault := true.B
    dataCacheRequestActive := false.B
    state := stateIdle
  }

  private def MisalignedLoadSegmentData(response: UInt): Unit = {
    val lastSegment = MisalignedSegmentIndex === activeSegCount - 1.U
    when(lastSegment) {
      finishMisLoad(response)
    }.otherwise {
      MisalignedWordRegisters(MisalignedSegmentIndex) := response
      MisalignedSegmentIndex := MisalignedSegmentIndex + 1.U
      state := stateMisLoadRequest
    }
  }

  def MisalignedLoadResponseBody(): Unit = {
    if (DCacheEnable) {
      when(DCache.io.response.fire) {
        when(DCache.io.response.bits.fault) {
          MisalignedLoadFault(DCache.io.response.bits.FaultResponse)
        }.otherwise {
          val data =
            if (ChunkLog2 == 3) DCache.io.response.bits.data
            else DCache.io.response.bits.data(31, 0)
          MisalignedLoadSegmentData(data)
        }
      }
    } else {
      io.DataBus.R.RREADY := true.B
      when(io.DataBus.R.RVALID) {
        val responseBad = io.DataBus.R.RRESP =/= 0.U
        val data =
          if (ChunkLog2 == 3) io.DataBus.R.RDATA
          else {
            SelectWord(io.DataBus.R.RDATA, MisalignedSegmentPhysicalAddress,
              busWide)
          }
        when(responseBad) {
          MisalignedLoadFault(io.DataBus.R.RRESP)
        }.elsewhen(!io.DataBus.R.RLAST) {
          accessFaultReg := true.B
          accessFaultResponseReg := 2.U
          state := stateReadDrain
        }.otherwise {
          MisalignedLoadSegmentData(data)
        }
      }
    }
  }

  def readDrainBody(): Unit = {
    io.DataBus.R.RREADY := true.B
    when(io.DataBus.R.RVALID && io.DataBus.R.RLAST) {
      transactionComplete := true.B
      transactionFault := true.B
      atomicActive := false.B
      state := stateIdle
    }.elsewhen(readDrainTimer === DrainTimeout.U) {
      // 缺 RLAST 超时后按访问故障完成，避免 LSU 永久忙。
      accessFaultReg := true.B
      when(accessFaultResponseReg === 0.U) {
        accessFaultResponseReg := 2.U
      }
      transactionComplete := true.B
      transactionFault := true.B
      atomicActive := false.B
      state := stateIdle
    }.otherwise {
      readDrainTimer := readDrainTimer + 1.U
    }
  }
}
