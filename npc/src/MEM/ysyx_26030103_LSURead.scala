package ysyx_26030103.mem

import chisel3._
import chisel3.util._

private[mem] trait LSURead {
  this: ysyx_26030103_LSU =>

  def readRequestBody(): Unit = {
    if (DCacheEnable) {
      when(dataCacheRequestActive) {
        when(DCache.io.request.fire) { state := stateReadResponse }
      }.otherwise {
        io.DataBus.AR.ARVALID := true.B
        io.DataBus.AR.ARADDR := addressOf(activeInstruction.ALUResult)
        io.DataBus.AR.ARLEN :=
          Mux(readIsWide && !busWideBit, 1.U, 0.U)
        io.DataBus.AR.ARSIZE :=
          Mux(readIsWide && busWideBit, 3.U, AXISize)
        when(io.DataBus.AR.ARREADY) { state := stateReadResponse }
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
      }.otherwise {
        io.DataBus.R.RREADY := true.B
        when(io.DataBus.R.RVALID) {
          when(io.DataBus.R.RRESP =/= 0.U) {
            accessFaultReg := true.B
            accessFaultResponseReg := io.DataBus.R.RRESP
          }.otherwise {
            loadDataReg := directLoadData
          }
          when(io.DataBus.R.RLAST) {
            loadForwardValid := true.B
            loadForwardData := directLoadData
            loadForwardFault := accessFaultReg || io.DataBus.R.RRESP =/= 0.U
            transactionComplete := true.B
            transactionFault := accessFaultReg || io.DataBus.R.RRESP =/= 0.U
            state := stateIdle
          }.otherwise {
            // 该请求是单拍AXI读，因此不带RLAST的响应属于协议错误。应继续消费
            // 此通道，直到违规突发传输排空，而不是提前退休。
            accessFaultReg := true.B
            accessFaultResponseReg := Mux(
              io.DataBus.R.RRESP =/= 0.U,
              io.DataBus.R.RRESP,
              2.U
            )
            state := stateReadDrain
          }
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
