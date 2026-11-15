package ysyx_26030103.mem

import chisel3._
import chisel3.util._

private[mem] trait LSUStage {
  this: ysyx_26030103_LSU =>

  def stageFsmBody(): Unit = {
    switch(stageState) {
      is(stageIdle) {
        when(stageInputValid && isMemOp) {
          stageInputReady := true.B
          when(stageInputFire) {
            stageState := Mux(
              stageInputMisaligned || stageInputPMPFault,
              stageDone,
              stageWait
            )
          }
        }.otherwise {
          // 非访存指令单拍直通
          stageInputReady := io.out.ready
          io.out.valid := stageInputValid
        }
      }
      is(stageWait) {
        when(transactionComplete) {
          stageState := stageDone
        }
      }
      is(stageDone) {
        io.out.valid := true.B
        // A successful completed instruction may be replaced in the same cycle
        // that it leaves the stage.  Memory replacements enter StageWait;
        // non-memory replacements are held as an already completed StageDone item.
        stageInputReady := io.out.ready && !memFaultReg
        when(io.out.fire) {
          when(stageInputFire) {
            when(isMemOp) {
              stageState := Mux(
                stageInputMisaligned || stageInputPMPFault,
                stageDone,
                stageWait
              )
            }.otherwise {
              messageReg := stageInputBits
              stageState := stageDone
            }
          }.otherwise {
            stageState := stageIdle
          }
        }
      }
    }
  }

  def pendingStoreBody(): Unit = {
    when(pendingStorePush) {
      if (WBufDepth == 1) {
        pendingStore(0) := io.in.bits
        pendingStoreTail := 0.U
      } else {
        pendingStore(pendingStoreTail) := io.in.bits
        pendingStoreTail := pendingStoreTail + 1.U
      }
    }
    when(pendingStorePop) {
      if (WBufDepth == 1) {
        pendingStoreHead := 0.U
      } else {
        pendingStoreHead := pendingStoreHead + 1.U
      }
    }
    when(memTrapCommit) {
      // 队首访存失败：年轻 store 尚未发总线，可全部安全撤销。
      pendingStoreCount := 0.U
      pendingStoreHead := 0.U
      pendingStoreTail := 0.U
    }.otherwise {
      pendingStoreCount := pendingStoreCount +
        pendingStorePush.asUInt - pendingStorePop.asUInt
    }
  }
}
