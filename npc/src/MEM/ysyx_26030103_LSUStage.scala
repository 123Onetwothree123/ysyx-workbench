package ysyx_26030103.mem

import chisel3._
import chisel3.util._

private[mem] trait LSUStage {
  this: ysyx_26030103_LSU =>

  def stageFsmBody(): Unit = {
    switch(stageState) {
      is(StageIdle) {
        when(StageInputValid && IsMemOp) {
          StageInputReady := true.B
          when(StageInputFire) {
            stageState := Mux(
              StageInputMisaligned || StageInputPmpFault,
              StageDone,
              StageWait
            )
          }
        }.otherwise {
          // 非访存指令单拍直通
          StageInputReady := io.out.ready
          io.out.valid := StageInputValid
        }
      }
      is(StageWait) {
        when(TransactionComplete) {
          stageState := StageDone
        }
      }
      is(StageDone) {
        io.out.valid := true.B
        // A successful completed instruction may be replaced in the same cycle
        // that it leaves the stage.  Memory replacements enter StageWait;
        // non-memory replacements are held as an already completed StageDone item.
        StageInputReady := io.out.ready && !MemFaultReg
        when(io.out.fire) {
          when(StageInputFire) {
            when(IsMemOp) {
              stageState := Mux(
                StageInputMisaligned || StageInputPmpFault,
                StageDone,
                StageWait
              )
            }.otherwise {
              MsgReg := StageInputBits
              stageState := StageDone
            }
          }.otherwise {
            stageState := StageIdle
          }
        }
      }
    }
  }

  def pendingStoreBody(): Unit = {
    when(PendingStorePush) {
      if (WBufDepth == 1) {
        PendingStore(0) := io.in.bits
        PendingStoreTail := 0.U
      } else {
        PendingStore(PendingStoreTail) := io.in.bits
        PendingStoreTail := PendingStoreTail + 1.U
      }
    }
    when(PendingStorePop) {
      if (WBufDepth == 1) {
        PendingStoreHead := 0.U
      } else {
        PendingStoreHead := PendingStoreHead + 1.U
      }
    }
    when(MemTrapCommit) {
      // 队首访存失败：年轻 store 尚未发总线，可全部安全撤销。
      PendingStoreCount := 0.U
      PendingStoreHead := 0.U
      PendingStoreTail := 0.U
    }.otherwise {
      PendingStoreCount := PendingStoreCount +
        PendingStorePush.asUInt - PendingStorePop.asUInt
    }
  }
}
