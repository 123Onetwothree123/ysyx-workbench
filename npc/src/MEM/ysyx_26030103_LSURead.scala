package ysyx_26030103.mem

import chisel3._
import chisel3.util._

/** LSU 普通读事务：AR/R 直读、DCache demand 与 RLAST 排空。 */
private[mem] trait LSURead {
  this: ysyx_26030103_LSU =>

  def readRequestBody(): Unit = {
    if (DCacheEnable) {
      when(DCacheRequestActive) {
        when(DCache.io.req.fire) { state := StatesReadResponse }
      }.otherwise {
        io.DataBus.AR.ARVALID := true.B
        io.DataBus.AR.ARADDR := AddrOf(ActiveInstruction.ALUResult)
        io.DataBus.AR.ARLEN :=
          Mux(ReadIsWide && !BusWideBit, 1.U, 0.U)
        io.DataBus.AR.ARSIZE :=
          Mux(ReadIsWide && BusWideBit, 3.U, AXISize)
        when(io.DataBus.AR.ARREADY) { state := StatesReadResponse }
      }
    } else {
      io.DataBus.AR.ARVALID := true.B
      io.DataBus.AR.ARADDR := AddrOf(ActiveInstruction.ALUResult)
      io.DataBus.AR.ARLEN := Mux(ReadIsWide && !BusWideBit, 1.U, 0.U)
      io.DataBus.AR.ARSIZE := Mux(ReadIsWide && BusWideBit, 3.U, AXISize)
      when(io.DataBus.AR.ARREADY) { state := StatesReadResponse }
    }
  }

  def readResponseBody(): Unit = {
    if (DCacheEnable) {
      when(DCacheRequestActive) {
        when(DCache.io.resp.fire) {
          LoadForwardValid := true.B
          LoadForwardData := DCache.io.resp.bits.data
          LoadForwardFault := DCache.io.resp.bits.fault
          when(DCache.io.resp.bits.fault) {
            AccessFaultReg := true.B
            AccessFaultRespReg := DCache.io.resp.bits.FaultResp
          }.otherwise {
            LoadDataReg := DCache.io.resp.bits.data
          }
          DCacheRequestActive := false.B
          TransactionComplete := true.B
          TransactionFault := DCache.io.resp.bits.fault
          state := StatesIdle
        }
      }.otherwise {
        io.DataBus.R.RREADY := true.B
        when(io.DataBus.R.RVALID) {
          when(io.DataBus.R.RRESP =/= 0.U) {
            AccessFaultReg := true.B
            AccessFaultRespReg := io.DataBus.R.RRESP
          }.otherwise {
            LoadDataReg := DirectLoadData
          }
          when(io.DataBus.R.RLAST) {
            LoadForwardValid := true.B
            LoadForwardData := DirectLoadData
            LoadForwardFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
            TransactionComplete := true.B
            TransactionFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
            state := StatesIdle
          }.otherwise {
            // 该请求是单拍AXI读，因此不带RLAST的响应属于协议错误。应继续消费
            // 此通道，直到违规突发传输排空，而不是提前退休。
            AccessFaultReg := true.B
            AccessFaultRespReg := Mux(
              io.DataBus.R.RRESP =/= 0.U,
              io.DataBus.R.RRESP,
              2.U
            )
            state := StatesReadDrain
          }
        }
      }
    } else {
      io.DataBus.R.RREADY := true.B
      when(io.DataBus.R.RVALID) {
        when(io.DataBus.R.RRESP =/= 0.U) {
          AccessFaultReg := true.B
          AccessFaultRespReg := io.DataBus.R.RRESP
        }
        if (WideMem && !BusWide) {
          when(ReadIsWide) {
            when(LoadBeat === 0.U) {
              when(io.DataBus.R.RLAST) {
                // 两拍读在第一个响应就结束：按协议错误提交访问故障。
                AccessFaultReg := true.B
                AccessFaultRespReg := Mux(
                  io.DataBus.R.RRESP =/= 0.U, io.DataBus.R.RRESP, 2.U)
                TransactionComplete := true.B
                TransactionFault := true.B
                state := StatesIdle
              }.otherwise {
                LoadWord0Reg := io.DataBus.R.RDATA
                LoadBeat := 1.U
              }
            }.otherwise {
              when(io.DataBus.R.RLAST) {
                val FinalData = DirectLoadData
                when(!(AccessFaultReg || io.DataBus.R.RRESP =/= 0.U)) {
                  LoadDataReg := FinalData
                }
                LoadForwardValid := true.B
                LoadForwardData := FinalData
                LoadForwardFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
                TransactionComplete := true.B
                TransactionFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
                state := StatesIdle
              }.otherwise {
                AccessFaultReg := true.B
                AccessFaultRespReg := Mux(
                  io.DataBus.R.RRESP =/= 0.U, io.DataBus.R.RRESP, 2.U)
                state := StatesReadDrain
              }
            }
          }.otherwise {
            when(io.DataBus.R.RLAST) {
              val FinalData = DirectLoadData
              when(!(AccessFaultReg || io.DataBus.R.RRESP =/= 0.U)) {
                LoadDataReg := FinalData
              }
              LoadForwardValid := true.B
              LoadForwardData := FinalData
              LoadForwardFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
              TransactionComplete := true.B
              TransactionFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
              state := StatesIdle
            }.otherwise {
              AccessFaultReg := true.B
              AccessFaultRespReg := Mux(
                io.DataBus.R.RRESP =/= 0.U, io.DataBus.R.RRESP, 2.U)
              state := StatesReadDrain
            }
          }
        } else {
          when(io.DataBus.R.RLAST) {
            val FinalData = DirectLoadData
            when(!(AccessFaultReg || io.DataBus.R.RRESP =/= 0.U)) {
              LoadDataReg := FinalData
            }
            LoadForwardValid := true.B
            LoadForwardData := FinalData
            LoadForwardFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
            TransactionComplete := true.B
            TransactionFault := AccessFaultReg || io.DataBus.R.RRESP =/= 0.U
            state := StatesIdle
          }.otherwise {
            AccessFaultReg := true.B
            AccessFaultRespReg := Mux(
              io.DataBus.R.RRESP =/= 0.U, io.DataBus.R.RRESP, 2.U)
            state := StatesReadDrain
          }
        }
      }
    }
  }

  def readDrainBody(): Unit = {
    io.DataBus.R.RREADY := true.B
    when(io.DataBus.R.RVALID && io.DataBus.R.RLAST) {
      TransactionComplete := true.B
      TransactionFault := true.B
      AtomicActive := false.B
      state := StatesIdle
    }.elsewhen(ReadDrainTimer === DrainTimeout.U) {
      // 从设备缺少 RLAST：继续等待只会让 LSU 永久忙。按访问故障完成
      // 当前指令，让流水线恢复（迟到的 beat 由后续事务承担风险，和
      // DCache 的 drain 超时恢复策略一致）。
      AccessFaultReg := true.B
      when(AccessFaultRespReg === 0.U) {
        AccessFaultRespReg := 2.U
      }
      TransactionComplete := true.B
      TransactionFault := true.B
      AtomicActive := false.B
      state := StatesIdle
    }.otherwise {
      ReadDrainTimer := ReadDrainTimer + 1.U
    }
  }
}
